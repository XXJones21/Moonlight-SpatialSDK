package com.example.moonlight_spatialsdk.systems.heroLighting

import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import com.example.moonlight_spatialsdk.quadTriangleMesh
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.mruk.getSize
import com.meta.spatial.mruk.hasLabel
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.UUID

/**
 * System that creates overlay meshes on MRUK room surfaces for hero lighting reflections.
 * 
 * This system:
 * - Detects MRUK planes (walls, floor, ceiling)
 * - Creates subdivided quad meshes with custom shader materials
 * - Registers materials with HeroLightingSystem for texture updates
 * - Manages visibility of overlay meshes
 * 
 * The overlay meshes display reflected/emissive lighting from the video panel
 * onto real-world room surfaces tracked by MRUK.
 * 
 * Ported from PremiumMediaSample's WallLightingSystem.
 * 
 * @param materialsMap Optional custom materials map for each face type
 * @param _isVisible Initial visibility state
 */
class WallLightingSystem(
    private var materialsMap: Map<WallLightingFace, SceneMaterial>? = null,
    private var _isVisible: Boolean = true,
) : SystemBase() {

    companion object {
        private const val TAG = "WallLightingSystem"
        const val MESH_PREFIX = "mesh://WallLightingSystem_"

        /**
         * Creates default materials map with hero lighting shader for walls, floor, and ceiling.
         */
        fun getDefaultMaterialsMap(): Map<WallLightingFace, SceneMaterial> {
            val p3: ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            val color = Color.valueOf(1.0f, 1.0f, 1.0f, 1.0f, p3)
            val wallMaterial = getDefaultCustomShader("mruk_hero_lighting", SceneTexture(color))

            return mapOf(
                Pair(WallLightingFace(MRUKLabel.WALL_FACE, null), wallMaterial),
                Pair(WallLightingFace(MRUKLabel.CEILING, null), wallMaterial),
                Pair(WallLightingFace(MRUKLabel.FLOOR, null), wallMaterial),
            )
        }

        /**
         * Creates a custom shader material for hero lighting.
         */
        private fun getDefaultCustomShader(
            shaderName: String,
            albedoTexture: SceneTexture = SceneTexture(Color()),
            depthWrite: DepthWrite = DepthWrite.DISABLE,
        ): SceneMaterial {
            return SceneMaterial.custom(
                shaderName,
                arrayOf(
                    SceneMaterialAttribute("albedoSampler", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("roughnessMetallicTexture", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("emissive", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("occlusion", SceneMaterialDataType.Texture2D),
                    SceneMaterialAttribute("emissiveFactor", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("albedoFactor", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("matParams", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("stereoParams", SceneMaterialDataType.Vector4),
                ),
            ).apply {
                setDepthWrite(depthWrite)
                setAttribute("emissiveFactor", Vector4(1.0f, 1.0f, 0f, 0f))
                setAttribute("albedoFactor", Vector4(0.0f, 0.0f, 1f, 0f))
                setAttribute("matParams", Vector4(1.0f, 0.0f, 0f, 0f))
                setAttribute("stereoParams", Vector4(1.0f, 0.0f, 0f, 0f))
                setTexture("albedoSampler", albedoTexture)
                setTexture("roughnessMetallicTexture", SceneTexture(Color()))
                setTexture("emissive", SceneTexture(Color()))
                setTexture("occlusion", SceneTexture(Color()))
                setBlendMode(BlendMode.OPAQUE)
                setStereoMode(StereoMode.None)
            }
        }
    }

    private var planes = mutableListOf<Entity>()
    private var planeScales = mutableListOf<Vector3>()
    private var roomUuid: UUID? = null

    private val meshNames: Array<MeshNameLabel>
    private val validLabels = arrayOf(
        MRUKLabel.WALL_FACE,
        MRUKLabel.FLOOR,
        MRUKLabel.CEILING,
        MRUKLabel.WALL_ART,
        MRUKLabel.SCREEN,
        MRUKLabel.DOOR_FRAME,
        MRUKLabel.WINDOW_FRAME,
    )

    val isVisible: Boolean
        get() = _isVisible

    init {
        val activity = SpatialActivityManager.getAppSystemActivity()
        if (materialsMap == null || materialsMap!!.isEmpty()) {
            materialsMap = getDefaultMaterialsMap()
        }

        val meshNamesList = mutableListOf<MeshNameLabel>()
        val heroLightingSystem = activity.getSystemManager().findSystem<HeroLightingSystem>()
        val labels = mutableListOf<MRUKLabel>()

        if (heroLightingSystem == null) {
            Log.e(TAG, "HeroLightingSystem not found - wall lighting materials will not receive updates")
        }

        for (materialEntry in materialsMap!!) {
            if (!validLabels.contains(materialEntry.key.label)) {
                throw IllegalArgumentException(
                    "Cannot use a material map with label type: ${materialEntry.key.label}"
                )
            }

            // Add labels for quick search later
            if (!labels.contains(materialEntry.key.label)) {
                labels.add(materialEntry.key.label)
            }

            // Register Name+Direction for finding later
            val meshName = MESH_PREFIX + materialEntry.key.toString()
            var meshNameLabel = meshNamesList.find { it.label == materialEntry.key.label }
            if (meshNameLabel == null) {
                meshNameLabel = MeshNameLabel(materialEntry.key.label, null, mutableMapOf())
                meshNamesList.add(meshNameLabel)
            }
            if (materialEntry.key.direction == null) {
                meshNameLabel.defaultMesh = meshName
            } else {
                meshNameLabel.directions[materialEntry.key.direction!!] = meshName
            }

            // Allow creation of custom mesh with material
            activity.registerMeshCreator(meshName) {
                SceneMesh.fromTriangleMesh(
                    quadTriangleMesh(1f, 1f, materialEntry.value, 4, 4),
                    false,
                )
            }

            // Register material with lighting system (null-safe)
            if (heroLightingSystem != null && !heroLightingSystem.hasRegisteredMaterial(materialEntry.value)) {
                heroLightingSystem.registerMaterial(materialEntry.value, true)
            }
        }

        meshNames = meshNamesList.toTypedArray()
        Log.i(TAG, "WallLightingSystem initialized with ${meshNames.size} mesh types")
    }

    override fun execute() {
        val anchorQuery = Query.where { changed(MRUKPlane.id) }
        for (mrukPlane in anchorQuery.eval()) {
            val anchor = mrukPlane.getComponent<MRUKAnchor>()

            if (roomUuid == null) {
                roomUuid = SpatialActivityManager.getAppSystemActivity()
                    .tryFindFeature<MRUKFeature>()
                    ?.getCurrentRoom()
                    ?.anchor
                    ?.uuid
            }

            if (anchor.roomUuid != roomUuid) continue

            val transform = mrukPlane.getComponent<Transform>()
            val normal = transform.transform.forward()
            val meshName = getMeshName(anchor, normal) ?: continue

            val plane = mrukPlane.getComponent<MRUKPlane>()
            val size = plane.getSize()
            val scale = Vector3(size.x, size.y, 1f)
            planeScales.add(scale)

            planes.add(
                Entity.create(
                    Mesh(Uri.parse(meshName)),
                    Transform(transform.transform),
                    Hittable(MeshCollision.NoCollision),
                    Scale(if (_isVisible) scale else Vector3(0f)),
                    Visible(_isVisible),
                )
            )
        }
    }

    private fun getMeshName(anchor: MRUKAnchor, normal: Vector3): String? {
        for (meshNameLabel in meshNames) {
            if (!anchor.hasLabel(meshNameLabel.label)) continue

            if (meshNameLabel.directions.isNotEmpty()) {
                for (directionName in meshNameLabel.directions) {
                    if (normal.dot(directionName.key) >= 0.67f) { // ~45 degree threshold
                        return directionName.value
                    }
                }
            }
            return meshNameLabel.defaultMesh
        }
        return null
    }

    /**
     * Instantly transitions visibility of all wall lighting meshes.
     * 
     * @param visible Whether meshes should be visible
     */
    fun transitionInstant(visible: Boolean) {
        _isVisible = visible
        planes.forEachIndexed { index, entity ->
            val targetScale = if (visible) {
                planeScales[index]
            } else {
                Vector3(0f, planeScales[index].y, planeScales[index].z)
            }
            entity.setComponent(Scale(targetScale))
            entity.setComponent(Visible(visible))
        }
        Log.d(TAG, "Wall lighting visibility set to: $visible")
    }

    /**
     * Internal data class for mapping mesh names to labels and directions.
     */
    data class MeshNameLabel(
        var label: MRUKLabel,
        var defaultMesh: String?,
        var directions: MutableMap<Vector3, String>,
    )
}


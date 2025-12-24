package com.example.moonlight_spatialsdk.systems.heroLighting

import android.util.Log
import com.example.moonlight_spatialsdk.HeroLighting
import com.example.moonlight_spatialsdk.ReceiveLighting
import com.example.moonlight_spatialsdk.getSize
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.getAbsoluteTransform

/**
 * System that manages hero lighting effects from video panels.
 * 
 * This system:
 * - Detects entities with the HeroLighting component (video panel sources)
 * - Extracts their video texture for use in lighting shaders
 * - Tracks position, rotation, and size of the light source
 * - Updates registered materials with lighting uniforms
 * 
 * The lighting effect creates a soft ambient glow from the video panel onto
 * MRUK room surfaces (walls, floor, ceiling).
 * 
 * Ported from PremiumMediaSample's HeroLightingSystem.
 * 
 * @param autoDetectTexture If true, automatically detect texture from HeroLighting entities
 * @param isProcessingShaders If true, process ReceiveLighting entities for shader assignment
 */
class HeroLightingSystem(
    private val autoDetectTexture: Boolean = true,
    private var isProcessingShaders: Boolean = true,
) : SystemBase() {

    companion object {
        private const val TAG = "HeroLightingSystem"
    }

    // Screen position data: xyz = position, w = width
    private val screenPositionData = Vector4(0f)
    // Screen direction data: xyz = euler angles, w = height
    private val screenDirectionData = Vector4(0f, 0f, 0f, 0.5f)

    private val registeredMaterials: MutableList<SceneMaterial> = mutableListOf()
    private val registeredMaterialsCustom: MutableList<SceneMaterial> = mutableListOf()
    private val unprocessedEntities: MutableList<Entity> = mutableListOf()
    private val materialsToGrabLater = mutableListOf<Entity>()

    private var stereoMode: StereoMode = StereoMode.LeftRight
    private var texture: SceneTexture? = null

    // Video lighting parameters: x = lightingAlpha, y = farPlane, z/w reserved
    private val _videoLightingData = Vector4(0.5f, 0.5f, 0.5f, 0.5f)

    /**
     * Lighting intensity multiplier.
     * Value from 0.0 (no lighting) to 1.0 (full intensity).
     */
    var lightingAlpha: Float
        get() = _videoLightingData.x
        set(value) {
            _videoLightingData.x = value
            forceUpdateMaterials()
        }

    /**
     * Far plane distance for lighting falloff calculations.
     */
    var lightingDebugFarPlane: Float
        get() = _videoLightingData.y
        set(value) {
            _videoLightingData.y = value
            forceUpdateMaterials()
        }

    /**
     * Sets the video texture to be used for lighting calculations.
     * The texture is sampled in the shader to determine light color.
     */
    private fun setTexture(newTexture: SceneTexture?) {
        texture = newTexture
        if (texture != null) {
            for (mat in registeredMaterials) {
                mat.setTexture("emissive", texture!!)
            }
            for (mat in registeredMaterialsCustom) {
                mat.setTexture("emissive", texture!!)
            }
            Log.d(TAG, "Texture set on ${registeredMaterials.size + registeredMaterialsCustom.size} materials")
        }
    }

    override fun execute() {
        var forcePositionUpdate = false

        if (autoDetectTexture) {
            // If we find a new texture, update position regardless of transform movement
            forcePositionUpdate = checkNewHeroLightingTexture()
        }
        checkNewReceiveLightingMaterials()
        checkCurrentHeroLightingUpdates(forcePositionUpdate)
    }

    /**
     * Checks for new or changed HeroLighting entities and extracts their texture.
     * @return True if a new texture was found
     */
    private fun checkNewHeroLightingTexture(): Boolean {
        var newTextureFound = false
        val lightingEntities = Query.where { changed(HeroLighting.id) }.eval()
        
        for (lightingEntity in lightingEntities) {
            val heroLighting = lightingEntity.getComponent<HeroLighting>()
            if (heroLighting.isEnabled) {
                systemManager.findSystem<SceneObjectSystem>().getSceneObject(lightingEntity)?.thenAccept { so ->
                    val newTexture = so.mesh?.materials?.get(0)?.texture
                    if (newTexture != null) {
                        setTexture(newTexture)
                        newTextureFound = true
                        Log.d(TAG, "Found HeroLighting texture from entity")
                    }
                }
            }
        }
        return newTextureFound
    }

    /**
     * Checks for new ReceiveLighting entities and registers their materials.
     */
    private fun checkNewReceiveLightingMaterials() {
        val receiveLightingMaterials = Query.where { changed(ReceiveLighting.id) }.eval()

        for (addedEntity in materialsToGrabLater) {
            registerOrUnregisterMaterials(addedEntity)
        }
        materialsToGrabLater.clear()

        for (receiveLightingEntity in receiveLightingMaterials) {
            if (!isProcessingShaders) {
                unprocessedEntities.add(receiveLightingEntity)
            } else {
                setShaderAndProcessLater(receiveLightingEntity)
            }
        }
    }

    private fun registerOrUnregisterMaterials(entity: Entity) {
        systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { so ->
            val materials = so?.mesh?.materials
            if (materials != null) {
                val receiveLighting = entity.getComponent<ReceiveLighting>()
                if (receiveLighting.isEnabled) {
                    if (receiveLighting.hasProcessed) {
                        // Unregister materials
                        receiveLighting.hasProcessed = false
                        entity.setComponent(receiveLighting)
                        materials.forEach { unregisterMaterial(it) }
                    } else {
                        // Register materials
                        receiveLighting.hasProcessed = true
                        entity.setComponent(receiveLighting)
                        materials.forEach { registerMaterial(it) }
                    }
                }
            }
        }
    }

    /**
     * Registers a material to receive hero lighting updates.
     * 
     * @param material The material to register
     * @param custom If true, material uses custom shader with matParams attribute
     */
    fun registerMaterial(material: SceneMaterial, custom: Boolean = false) {
        if (texture != null) {
            material.setTexture("emissive", texture!!)
        }
        material.setStereoMode(stereoMode)
        updateMaterial(material, custom)
        if (custom) {
            registeredMaterialsCustom.add(material)
        } else {
            registeredMaterials.add(material)
        }
        Log.d(TAG, "Registered material (custom=$custom)")
    }

    /**
     * Unregisters a material from receiving hero lighting updates.
     */
    private fun unregisterMaterial(material: SceneMaterial, custom: Boolean = false) {
        if (custom) {
            registeredMaterialsCustom.remove(material)
        } else {
            registeredMaterials.remove(material)
        }
    }

    /**
     * Checks for HeroLighting entities with changed transforms and updates lighting data.
     */
    private fun checkCurrentHeroLightingUpdates(forceUpdate: Boolean = false) {
        val query = Query.where {
            if (forceUpdate) {
                has(HeroLighting.id, Transform.id, Scale.id)
            } else {
                has(HeroLighting.id, Transform.id, Scale.id) and
                    (changed(Transform.id) or changed(Scale.id))
            }
        }

        var updatedPositionOrScale = false

        for (entity in query.eval()) {
            if (!entity.getComponent<HeroLighting>().isEnabled) continue

            val transform = getAbsoluteTransform(entity)
            val screenDirection = transform.q.toEuler()
            val scale = getSize(entity)

            screenPositionData.x = transform.t.x
            screenPositionData.y = transform.t.y
            screenPositionData.z = transform.t.z
            screenPositionData.w = scale.x

            screenDirectionData.x = screenDirection.x
            screenDirectionData.y = screenDirection.y
            screenDirectionData.z = screenDirection.z
            screenDirectionData.w = scale.y

            updatedPositionOrScale = true
        }

        if (updatedPositionOrScale) {
            forceUpdateMaterials()
        }
    }

    /**
     * Forces update of all registered materials with current lighting data.
     */
    private fun forceUpdateMaterials() {
        for (material in registeredMaterials) {
            updateMaterial(material, false)
        }
        for (material in registeredMaterialsCustom) {
            updateMaterial(material, true)
        }
    }

    /**
     * Updates a material's shader uniforms with current lighting data.
     * 
     * Uniforms set:
     * - emissiveFactor: screen position (xyz) and width (w)
     * - albedoFactor: screen rotation (xyz) and height (w)
     * - matParams (custom only): lighting parameters
     */
    private fun updateMaterial(material: SceneMaterial, custom: Boolean) {
        material.setAttribute("emissiveFactor", screenPositionData)
        material.setAttribute("albedoFactor", screenDirectionData)
        if (custom) {
            material.setAttribute("matParams", _videoLightingData)
        } else {
            material.setRoughnessMetallicness(_videoLightingData.x, _videoLightingData.y)
        }
    }

    private fun updateShader(entity: Entity) {
        val receiveLighting = entity.getComponent<ReceiveLighting>()
        if (receiveLighting.customShader != "") {
            val mesh = entity.getComponent<Mesh>()
            mesh.defaultShaderOverride = receiveLighting.customShader
            entity.setComponent(mesh)
        }
    }

    private fun setShaderAndProcessLater(entity: Entity) {
        materialsToGrabLater.add(entity) // Process next frame when mesh has loaded default shader
        updateShader(entity)
    }

    /**
     * Checks if a material is registered with this system.
     */
    fun hasRegisteredMaterial(material: SceneMaterial): Boolean {
        return registeredMaterials.contains(material) || registeredMaterialsCustom.contains(material)
    }
}


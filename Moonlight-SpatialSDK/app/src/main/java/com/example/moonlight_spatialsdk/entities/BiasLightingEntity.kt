package com.example.moonlight_spatialsdk.entities

import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import com.example.moonlight_spatialsdk.systems.heroLighting.HeroLightingSystem
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialContext
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.runtime.TriangleMesh
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible

/**
 * Creates a bias lighting effect around a video panel.
 * 
 * Bias lighting samples the edge colors of the video and projects them outward
 * with a soft falloff, creating an ambient glow effect similar to LED bias
 * lighting on physical displays.
 * 
 * The entity creates 4 edge meshes (top, bottom, left, right) that:
 * - Sample their corresponding video edge (top row, bottom row, etc.)
 * - Apply a hard-to-soft gradient falloff
 * - Use additive blending for light emission effect
 * 
 * The meshes maintain fixed thickness regardless of panel scale, but their
 * length follows the panel dimensions.
 */
class BiasLightingEntity(
    private val heroLightingSystem: HeroLightingSystem?
) {
    companion object {
        private const val TAG = "BiasLightingEntity"
        private const val MESH_PREFIX = "mesh://BiasLighting_"
        
        // DEBUG: Set to true to use solid colored materials for mesh placement debugging
        private const val DEBUG_MODE = false
        
        // Fixed thickness for the glow effect (in meters)
        private const val GLOW_THICKNESS = 0.1f
        
        // Z offset behind the panel
        private const val Z_OFFSET = -0.005f
        
        // Edge type constants for shader
        private const val EDGE_TOP = 0f
        private const val EDGE_BOTTOM = 1f
        private const val EDGE_LEFT = 2f
        private const val EDGE_RIGHT = 3f
        
        // Subdivisions for smooth color gradients
        private const val EDGE_SUBDIVISIONS = 16
    }
    
    private var parentPanel: Entity? = null
    private var panelSize: Vector2 = Vector2(1f, 1f)
    
    private val edgeMeshes = mutableListOf<Entity>()
    private val edgeMaterials = mutableMapOf<Float, SceneMaterial>()
    
    private var _isVisible = false
    val isVisible: Boolean get() = _isVisible
    
    init {
        registerMeshesAndMaterials()
    }
    
    private fun registerMeshesAndMaterials() {
        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { activity ->
            // Create materials for each edge type
            val edgeTypes = listOf(EDGE_TOP, EDGE_BOTTOM, EDGE_LEFT, EDGE_RIGHT)
            
            for (edgeType in edgeTypes) {
                val material = createBiasLightingMaterial(edgeType)
                edgeMaterials[edgeType] = material
                
                // Register material with HeroLightingSystem to receive video texture (skip in debug mode)
                if (!DEBUG_MODE) {
                    heroLightingSystem?.registerMaterial(material, true)
                }
                
                // Register mesh creator with edge-specific geometry
                val meshName = getMeshName(edgeType)
                val currentEdgeType = edgeType  // Capture for lambda
                activity.registerMeshCreator(meshName) {
                    SceneMesh.fromTriangleMesh(
                        createEdgeMesh(material, currentEdgeType),
                        false
                    )
                }
            }
            
            Log.d(TAG, "Bias lighting meshes and materials registered")
        }
    }
    
    private fun getMeshName(edgeType: Float): String {
        val edgeName = when {
            edgeType < 0.5f -> "top"
            edgeType < 1.5f -> "bottom"
            edgeType < 2.5f -> "left"
            else -> "right"
        }
        return MESH_PREFIX + edgeName
    }
    
    private fun createBiasLightingMaterial(edgeType: Float): SceneMaterial {
        val p3: ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        
        // Debug mode: use solid colors for mesh placement verification
        // Top = Red, Bottom = Blue, Left = Green, Right = Yellow
        val debugColor: Color? = if (DEBUG_MODE) {
            when {
                edgeType < 0.5f -> Color.valueOf(1.0f, 0.0f, 0.0f, 1.0f, p3)  // Top = Red
                edgeType < 1.5f -> Color.valueOf(0.0f, 0.0f, 1.0f, 1.0f, p3)  // Bottom = Blue
                edgeType < 2.5f -> Color.valueOf(0.0f, 1.0f, 0.0f, 1.0f, p3)  // Left = Green
                else -> Color.valueOf(1.0f, 1.0f, 0.0f, 1.0f, p3)              // Right = Yellow
            }
        } else null
        
        return SceneMaterial.custom(
            "bias_lighting",
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
            setDepthWrite(DepthWrite.DISABLE)
            setAttribute("emissiveFactor", Vector4(1.0f, 1.0f, 0f, 0f))
            setAttribute("albedoFactor", Vector4(0.0f, 0.0f, 1f, 0f))
            // matParams: controlled by HeroLightingSystem (x = lightingAlpha)
            setAttribute("matParams", Vector4(0.8f, 0f, 0f, 0f))
            // stereoParams: x = stereoMode, y = edgeType, z = debugMode (not touched by HeroLightingSystem)
            setAttribute("stereoParams", Vector4(1.0f, edgeType, if (DEBUG_MODE) 1f else 0f, 0f))
            val whiteColor = Color.valueOf(1.0f, 1.0f, 1.0f, 1.0f, p3)
            val blackColor = Color.valueOf(0.0f, 0.0f, 0.0f, 1.0f, p3)
            setTexture("albedoSampler", SceneTexture(whiteColor))
            setTexture("roughnessMetallicTexture", SceneTexture(blackColor))
            // In debug mode, use colored texture; otherwise black (video will be applied later)
            setTexture("emissive", SceneTexture(debugColor ?: blackColor))
            setTexture("occlusion", SceneTexture(whiteColor))
            setBlendMode(BlendMode.ADDITIVE)
            setStereoMode(StereoMode.None)
        }
    }
    
    private fun createEdgeMesh(material: SceneMaterial, edgeType: Float): TriangleMesh {
        // Create edge-specific mesh geometry
        // Each edge has vertices extending in the correct world-space direction
        // UV.x = position along edge (0-1)
        // UV.y = falloff distance (0 at panel edge, 1 at outer edge)
        // Normal = +Z for all (camera is at -Z looking toward +Z based on testing)
        
        val edgeDivisions = EDGE_SUBDIVISIONS
        val falloffDivisions = 2
        
        val vertexCount = (edgeDivisions + 1) * (falloffDivisions + 1)
        val triangleCount = edgeDivisions * falloffDivisions * 2
        
        val mesh = TriangleMesh(
            vertexCount,
            triangleCount * 3,
            intArrayOf(0, triangleCount * 3),
            arrayOf(material)
        )
        
        val vertices = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        val colors = IntArray(vertexCount) { Color.WHITE }
        val triangles = IntArray(triangleCount * 3)
        
        // Generate vertices based on edge type
        // Edge types: 0=top, 1=bottom, 2=left, 3=right
        for (falloff in 0..falloffDivisions) {
            for (edge in 0..edgeDivisions) {
                val index = falloff * (edgeDivisions + 1) + edge
                
                val edgePos = edge.toFloat() / edgeDivisions - 0.5f  // -0.5 to 0.5
                val falloffPos = falloff.toFloat() / falloffDivisions  // 0 to 1
                
                when {
                    edgeType < 0.5f -> {
                        // TOP: extends upward (+Y), spans X
                        vertices[index * 3] = edgePos
                        vertices[index * 3 + 1] = falloffPos  // 0 at panel, 1 extending up
                        vertices[index * 3 + 2] = 0f
                    }
                    edgeType < 1.5f -> {
                        // BOTTOM: extends downward (-Y), spans X
                        vertices[index * 3] = edgePos
                        vertices[index * 3 + 1] = -falloffPos  // 0 at panel, -1 extending down
                        vertices[index * 3 + 2] = 0f
                    }
                    edgeType < 2.5f -> {
                        // LEFT: extends leftward (-X), spans Y
                        vertices[index * 3] = -falloffPos  // 0 at panel, -1 extending left
                        vertices[index * 3 + 1] = edgePos
                        vertices[index * 3 + 2] = 0f
                    }
                    else -> {
                        // RIGHT: extends rightward (+X), spans Y
                        vertices[index * 3] = falloffPos  // 0 at panel, 1 extending right
                        vertices[index * 3 + 1] = edgePos
                        vertices[index * 3 + 2] = 0f
                    }
                }
                
                // Normal: +Z for all edges (facing camera at -Z looking toward +Z)
                normals[index * 3] = 0f
                normals[index * 3 + 1] = 0f
                normals[index * 3 + 2] = 1f
                
                // UV: x = position along edge, y = falloff distance
                uvs[index * 2] = edge.toFloat() / edgeDivisions
                uvs[index * 2 + 1] = falloff.toFloat() / falloffDivisions
            }
        }
        
        // Generate triangles
        // TOP and LEFT use counter-clockwise winding (faces +Z)
        // BOTTOM and RIGHT use clockwise winding (faces +Z after vertex flip)
        val reverseWinding = edgeType > 0.5f && edgeType < 1.5f || edgeType > 2.5f  // BOTTOM or RIGHT
        
        var triIndex = 0
        for (falloff in 0 until falloffDivisions) {
            for (edge in 0 until edgeDivisions) {
                val bottomLeft = falloff * (edgeDivisions + 1) + edge
                val topLeft = bottomLeft + (edgeDivisions + 1)
                val topRight = topLeft + 1
                val bottomRight = bottomLeft + 1
                
                if (reverseWinding) {
                    // Clockwise winding for BOTTOM and RIGHT
                    triangles[triIndex++] = bottomLeft
                    triangles[triIndex++] = topLeft
                    triangles[triIndex++] = bottomRight
                    
                    triangles[triIndex++] = bottomRight
                    triangles[triIndex++] = topLeft
                    triangles[triIndex++] = topRight
                } else {
                    // Counter-clockwise winding for TOP and LEFT
                    triangles[triIndex++] = bottomLeft
                    triangles[triIndex++] = bottomRight
                    triangles[triIndex++] = topLeft
                    
                    triangles[triIndex++] = bottomRight
                    triangles[triIndex++] = topRight
                    triangles[triIndex++] = topLeft
                }
            }
        }
        
        mesh.updateGeometry(0, vertices, normals, uvs, colors)
        mesh.updatePrimitives(0, triangles)
        
        return mesh
    }
    
    /**
     * Attaches the bias lighting to a video panel.
     */
    fun attachToPanel(panel: Entity) {
        parentPanel = panel
        
        val dimensions = panel.tryGetComponent<PanelDimensions>()
        if (dimensions != null) {
            panelSize = dimensions.dimensions
        }
        
        createEdgeEntities()
        updateEdgeTransforms()
        
        Log.d(TAG, "Bias lighting attached to panel, size: $panelSize")
    }
    
    private fun createEdgeEntities() {
        // Clear any existing meshes
        edgeMeshes.forEach { it.destroy() }
        edgeMeshes.clear()
        
        val parent = parentPanel ?: return
        
        val edgeTypes = listOf(EDGE_TOP, EDGE_BOTTOM, EDGE_LEFT, EDGE_RIGHT)
        
        for (edgeType in edgeTypes) {
            val meshName = getMeshName(edgeType)
            
            val entity = Entity.create(
                Mesh(Uri.parse(meshName)),
                Transform(Pose()),
                TransformParent(parent),
                Hittable(MeshCollision.NoCollision),
                Scale(Vector3(1f)),
                Visible(_isVisible)
            )
            
            edgeMeshes.add(entity)
        }
        
        Log.d(TAG, "Created ${edgeMeshes.size} edge mesh entities")
    }
    
    private fun updateEdgeTransforms() {
        if (edgeMeshes.size < 4) return
        
        val halfWidth = panelSize.x * 0.5f
        val halfHeight = panelSize.y * 0.5f
        
        // Each mesh has edge-specific geometry - no rotations needed
        // Meshes extend in correct world-space direction with +Z normals
        
        // Top edge: mesh extends upward from Y=0
        // Position at panel top edge, scale X to panel width, Y to glow thickness
        edgeMeshes[0].setComponent(Transform(Pose(
            Vector3(0f, halfHeight, Z_OFFSET)
        )))
        edgeMeshes[0].setComponent(Scale(Vector3(panelSize.x, GLOW_THICKNESS, 1f)))
        
        // Bottom edge: mesh extends downward from Y=0
        // Position at panel bottom edge
        edgeMeshes[1].setComponent(Transform(Pose(
            Vector3(0f, -halfHeight, Z_OFFSET)
        )))
        edgeMeshes[1].setComponent(Scale(Vector3(panelSize.x, GLOW_THICKNESS, 1f)))
        
        // Left edge: mesh extends leftward from X=0
        // Position at panel left edge, scale X to glow thickness, Y to panel height
        edgeMeshes[2].setComponent(Transform(Pose(
            Vector3(-halfWidth, 0f, Z_OFFSET)
        )))
        edgeMeshes[2].setComponent(Scale(Vector3(GLOW_THICKNESS, panelSize.y, 1f)))
        
        // Right edge: mesh extends rightward from X=0
        // Position at panel right edge
        edgeMeshes[3].setComponent(Transform(Pose(
            Vector3(halfWidth, 0f, Z_OFFSET)
        )))
        edgeMeshes[3].setComponent(Scale(Vector3(GLOW_THICKNESS, panelSize.y, 1f)))
    }
    
    /**
     * Updates the bias lighting when panel size changes.
     */
    fun updatePanelSize(newSize: Vector2) {
        if (panelSize != newSize) {
            panelSize = newSize
            updateEdgeTransforms()
        }
    }
    
    /**
     * Updates the bias lighting based on parent panel's current scale.
     * Should be called when the parent panel's Scale component changes.
     */
    fun updateFromParentScale() {
        val parent = parentPanel ?: return
        val dimensions = parent.tryGetComponent<PanelDimensions>() ?: return
        val scale = parent.tryGetComponent<Scale>()?.scale ?: Vector3(1f)
        
        // Calculate effective size = dimensions * scale
        val effectiveSize = Vector2(
            dimensions.dimensions.x * scale.x,
            dimensions.dimensions.y * scale.y
        )
        
        updatePanelSize(effectiveSize)
    }
    
    /**
     * Sets the visibility of the bias lighting effect.
     */
    fun setVisible(visible: Boolean) {
        _isVisible = visible
        edgeMeshes.forEach { entity ->
            entity.setComponent(Visible(visible))
        }
        Log.d(TAG, "Bias lighting visibility set to: $visible")
    }
    
    /**
     * Sets the intensity of the bias lighting effect.
     */
    fun setIntensity(intensity: Float) {
        edgeMaterials.forEach { (edgeType, material) ->
            material.setAttribute("matParams", Vector4(intensity, edgeType, 0f, 0f))
        }
    }
    
    /**
     * Cleans up all entities and resources.
     */
    fun destroy() {
        edgeMeshes.forEach { it.destroy() }
        edgeMeshes.clear()
        edgeMaterials.clear()
        parentPanel = null
        Log.d(TAG, "Bias lighting destroyed")
    }
}


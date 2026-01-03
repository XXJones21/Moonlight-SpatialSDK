package com.example.moonlight_spatialsdk.entities

import android.graphics.Color
import android.util.Log
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.SpatialActivityManager

/**
 * Creates a custom SceneMesh quad for stereoscopic 3D video rendering.
 * 
 * Uses SceneMesh.quad() with a SceneMaterial that applies the stereo_video shader.
 * This allows proper testing and debugging of StereoMode.LeftRight functionality
 * with debug red/blue textures before integrating actual video.
 */
class Stereo3DVideoPanelEntity {
    companion object {
        private const val TAG = "Stereo3DVideoPanelEntity"
        private const val MESH_NAME = "mesh://3DVideoPanel"
    }
    
    private var stereoMaterial: SceneMaterial? = null
    private var simpleBlackMaterial: SceneMaterial? = null
    
    // Depth control: 0.0 = true monoscopic (both eyes identical), 1.0 = full original parallax
    var depthFactor: Float = 0.5f
        private set
    
    // Stereo format: 0.0 = side-by-side, 1.0 = over-under
    var stereoFormat: Float = 0.0f
        private set
    
    // Debug mode: when true, shader outputs solid colors (red=left eye, blue=right eye)
    var debugMode: Boolean = true
        private set
    
    /**
     * Registers the mesh creator and creates the material with stereo_video shader.
     * Must be called from within executeOnVrActivity block.
     */
    fun registerMeshAndMaterial(activity: AppSystemActivity) {
        Log.d(TAG, "registerMeshAndMaterial: Starting mesh and material registration")
        
        // Create the material once - it will be reused for the mesh
        val material = createStereoMaterial()
        stereoMaterial = material
        
        // Register quad mesh creator
        // The material is created once and reused - SceneMesh.quad will use this material instance
        activity.registerMeshCreator(MESH_NAME) {
            SceneMesh.quad(
                Vector3(-0.5f, -0.5f, 0f),
                Vector3(0.5f, 0.5f, 0f),
                material
            )
        }
        
        Log.d(TAG, "Mesh and material registered successfully")
    }
    
    /**
     * Creates a SceneObject for the entity using a simple black material first.
     * This ensures the mesh spawns correctly before applying custom shader.
     * Must be called after entity is created.
     */
    fun createSceneObject(entity: com.meta.spatial.core.Entity, scene: com.meta.spatial.runtime.Scene, systemManager: com.meta.spatial.core.SystemManager) {
        Log.d(TAG, "createSceneObject: START - entity=$entity, scene=$scene, systemManager=$systemManager")
        
        // Step 1: Create simple black material to verify mesh spawning
        val material = createSimpleBlackMaterial()
        simpleBlackMaterial = material
        Log.d(TAG, "createSceneObject: Material created successfully")
        
        // Create SceneObject directly with the mesh (like MediaPlayerSample pattern)
        val sceneObject = SceneObject(
            scene,
            SceneMesh.quad(
                Vector3(-0.5f, -0.5f, 0f),
                Vector3(0.5f, 0.5f, 0f),
                material
            ),
            "3DVideoPanel",
            entity
        )
        Log.d(TAG, "createSceneObject: SceneObject instance created")
        
        // Add SceneObject to the scene via SceneObjectSystem
        val sceneObjectSystem = systemManager.findSystem<com.meta.spatial.toolkit.SceneObjectSystem>()
        if (sceneObjectSystem == null) {
            Log.e(TAG, "createSceneObject: ERROR - SceneObjectSystem not found!")
            return
        }
        Log.d(TAG, "createSceneObject: SceneObjectSystem found, adding SceneObject")
        
        sceneObjectSystem.addSceneObject(
            entity,
            java.util.concurrent.CompletableFuture<SceneObject>().apply { complete(sceneObject) }
        )
        Log.d(TAG, "createSceneObject: SceneObject added to SceneObjectSystem successfully")
    }
    
    /**
     * Applies the custom stereo shader material to the existing SceneObject.
     * Call this after verifying the mesh spawns correctly with the simple black material.
     * TODO: Implement material swapping once mesh spawning is verified.
     */
    fun applyCustomShader(entity: com.meta.spatial.core.Entity, scene: com.meta.spatial.runtime.Scene, systemManager: com.meta.spatial.core.SystemManager) {
        Log.d(TAG, "applyCustomShader: Custom shader application - to be implemented after mesh spawning verification")
        // Will be implemented in next step after verifying mesh spawns with simple black material
    }
    
    /**
     * Creates a simple black material for initial mesh spawning verification.
     * Uses stereo_video shader with all black textures/factors to ensure mesh renders.
     */
    private fun createSimpleBlackMaterial(): SceneMaterial {
        val p3 = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        val blackColor = android.graphics.Color.valueOf(0f, 0f, 0f, 1f, p3)
        val blackTexture = SceneTexture(blackColor)
        
        return SceneMaterial.custom(
            "stereo_video", // Use existing stereo_video shader (will output black with these settings)
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
            setBlendMode(BlendMode.OPAQUE)
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setStereoMode(StereoMode.None) // No stereo for simple black material
            
            // Set all textures to black
            setTexture("albedoSampler", blackTexture)
            setTexture("roughnessMetallicTexture", blackTexture)
            setTexture("emissive", blackTexture)
            setTexture("occlusion", blackTexture)
            
            // Set factors to output black (debug mode disabled, all black)
            setAttribute("emissiveFactor", Vector4(0f, 0f, 0f, 1f))
            setAttribute("albedoFactor", Vector4(0f, 0f, 0f, 1f))
            setAttribute("matParams", Vector4(0f, 0f, 0f, 0f))
            setAttribute("stereoParams", Vector4(0f, 0f, 0f, 0f)) // debugMode = 0 (disabled)
            
            Log.d(TAG, "Simple black material created with stereo_video shader")
        }
    }
    
    /**
     * Creates the SceneMaterial with stereo_video shader and StereoMode.LeftRight.
     */
    private fun createStereoMaterial(): SceneMaterial {
        val p3 = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        val blackColor = android.graphics.Color.valueOf(0f, 0f, 0f, 1f, p3)
        val redColor = android.graphics.Color.valueOf(1f, 0f, 0f, 1f, p3)
        val blackTexture = SceneTexture(blackColor)
        val redTexture = SceneTexture(redColor)
        
        return SceneMaterial.custom(
            "stereo_video",
            arrayOf(
                // Textures (bindings 1-4) - must match customBindingFrag.glsl
                SceneMaterialAttribute("albedoSampler", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("roughnessMetallicTexture", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("emissive", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("occlusion", SceneMaterialDataType.Texture2D),
                // Uniforms (binding 0)
                SceneMaterialAttribute("emissiveFactor", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("albedoFactor", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("matParams", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("stereoParams", SceneMaterialDataType.Vector4),
            ),
        ).apply {
            setBlendMode(BlendMode.OPAQUE)
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setStereoMode(StereoMode.LeftRight)
            
            // matParams: x = depthFactor (S), y = stereoFormat
            // S = 0.0: true monoscopic, S = 1.0: full parallax
            setAttribute("matParams", Vector4(depthFactor, stereoFormat, 0f, 0f))
            
            // stereoParams: x = unused, y = unused, z = debugMode (1.0 = enable debug colors)
            setAttribute("stereoParams", Vector4(0f, 0f, if (debugMode) 1.0f else 0.0f, 0f))
            
            // Textures - using placeholder textures for now (debug mode will override)
            setTexture("albedoSampler", blackTexture)
            setTexture("roughnessMetallicTexture", blackTexture)
            setTexture("emissive", redTexture) // Placeholder - will be replaced with video texture later
            setTexture("occlusion", blackTexture)
            
            // Standard PBR attributes (not used by stereo_video shader but required)
            setAttribute("emissiveFactor", Vector4(1.0f, 1.0f, 1.0f, 1.0f))
            setAttribute("albedoFactor", Vector4(1.0f, 1.0f, 1.0f, 1.0f))
            
            Log.d(TAG, "Material created with StereoMode.LeftRight, depthFactor=$depthFactor, debugMode=$debugMode")
        }
    }
    
    /**
     * Updates material uniforms with current depth and stereo parameters.
     */
    fun updateMaterialUniforms() {
        val material = stereoMaterial ?: return
        
        // matParams: x = depthFactor (S), y = stereoFormat
        material.setAttribute("matParams", Vector4(depthFactor, stereoFormat, 0f, 0f))
        
        // stereoParams: x = unused, y = unused, z = debugMode (1.0 = enable debug colors)
        material.setAttribute("stereoParams", Vector4(0f, 0f, if (debugMode) 1.0f else 0.0f, 0f))
        
        Log.d(TAG, "Material uniforms updated: depthFactor=$depthFactor, stereoFormat=$stereoFormat, debugMode=$debugMode")
    }
    
    /**
     * Updates the depth factor (0.0 to 1.0) for runtime depth control.
     */
    fun updateDepthFactor(value: Float) {
        depthFactor = value.coerceIn(0.0f, 1.0f)
        updateMaterialUniforms()
    }
    
    /**
     * Sets the stereo format (0.0 = side-by-side, 1.0 = over-under).
     */
    fun setStereoFormat(format: Float) {
        stereoFormat = format.coerceIn(0.0f, 1.0f)
        updateMaterialUniforms()
    }
    
    /**
     * Enables or disables debug mode (solid red/blue colors per eye).
     */
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        updateMaterialUniforms()
        Log.d(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Binds video texture to emissive sampler (for future use when video is integrated).
     */
    fun setVideoTexture(texture: SceneTexture) {
        val material = stereoMaterial ?: return
        material.setTexture("emissive", texture)
        Log.d(TAG, "Video texture bound to emissive sampler")
    }
    
    /**
     * Gets the material for external updates (e.g., from StereoVideoSystem).
     */
    fun getMaterial(): SceneMaterial? = stereoMaterial
    
    /**
     * Cleans up resources.
     */
    fun destroy() {
        stereoMaterial?.destroy()
        stereoMaterial = null
        Log.d(TAG, "Stereo3DVideoPanelEntity destroyed")
    }
}

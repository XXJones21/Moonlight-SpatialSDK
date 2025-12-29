package com.example.moonlight_spatialsdk.systems.stereo

import android.util.Log
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.SceneObjectSystem

/**
 * System that manages stereoscopic 3D video rendering with GPU-based frame splitting.
 * 
 * This system:
 * - Extracts video texture from ReadableVideoSurfacePanelRegistration panel
 * - Creates custom material with stereo_video shader
 * - Applies depth-based convergence control
 * - Supports side-by-side and over-under stereo formats
 * 
 * @param stereoFormat 0.0 = side-by-side, 1.0 = over-under
 */
class StereoVideoSystem(
    private var stereoFormat: Float = 0.0f // 0.0 = side-by-side, 1.0 = over-under
) : SystemBase() {
    
    override fun execute() {
        // This system doesn't need per-frame updates
        // Material updates are triggered by slider changes
    }

    companion object {
        private const val TAG = "StereoVideoSystem"
        
        // Comfort limits for stereoscopic viewing (not used in shader, but documented for future use)
        // Maximum comfortable disparity: ~2-3% of screen width
        // For 1920px width: ~38-57 pixels maximum disparity
        private const val MAX_COMFORTABLE_DISPARITY_RATIO = 0.03f
        
        // Negative parallax (pop-out) should be more aggressive: max 1-2% screen width
        private const val MAX_NEGATIVE_PARALLAX_RATIO = 0.02f
    }

    private var videoTexture: SceneTexture? = null
    private var videoEntity: Entity? = null
    
    // Depth control: 0.0 = true monoscopic (both eyes identical), 1.0 = full original parallax
    // Implements 3DS-style depth scaling with center-based convergence
    var depthFactor: Float = 0.5f
        private set
    
    // Debug mode: when true, shader outputs solid colors (red=left eye, blue=right eye)
    // Enabled by default for testing - should see red in left eye, blue in right eye
    var debugMode: Boolean = true
        private set

    /**
     * Registers the video texture from a panel entity.
     * Extracts texture from the panel's SceneObject mesh.
     * Retries multiple times to ensure SceneObject is ready (ReadableVideoSurfacePanelRegistration may initialize asynchronously).
     */
    fun registerVideoTexture(entity: Entity) {
        videoEntity = entity
        var retryCount = 0
        val maxRetries = 10
        
        fun tryRegister() {
            systemManager.findSystem<SceneObjectSystem>()?.getSceneObject(entity)?.thenAccept { sceneObject ->
                val mesh = sceneObject.mesh
                val texture = mesh?.materials?.get(0)?.texture
                if (texture != null && mesh != null) {
                    videoTexture = texture
                    Log.d(TAG, "Video texture extracted from panel entity on attempt ${retryCount + 1}")
                    createAndApplyStereoMaterial()
                } else {
                    retryCount++
                    if (retryCount < maxRetries) {
                        Log.d(TAG, "SceneObject not ready yet, retrying... (attempt $retryCount/$maxRetries)")
                        // Retry after a short delay using execute() in next frame
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            tryRegister()
                        }, 100)
                    } else {
                        Log.e(TAG, "Failed to extract video texture after $maxRetries attempts")
                    }
                }
            }?.exceptionally { throwable ->
                retryCount++
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Error getting SceneObject, retrying... (attempt $retryCount/$maxRetries): $throwable")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tryRegister()
                    }, 100)
                } else {
                    Log.e(TAG, "Failed to get SceneObject after $maxRetries attempts", throwable)
                }
                null
            }
        }
        
        tryRegister()
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
     * Applies StereoMode to the video entity's material and custom shader for eye inversion fix.
     * Uses custom shader to swap UV mapping to correct inverted left/right eyes.
     */
    private fun createAndApplyStereoMaterial() {
        val entity = videoEntity ?: return
        val texture = videoTexture ?: return

        Log.d(TAG, "createAndApplyStereoMaterial: Starting material setup for entity=$entity")
        
        // Update existing material with StereoMode and apply custom shader to fix eye inversion
        systemManager.findSystem<SceneObjectSystem>()?.getSceneObject(entity)?.thenAccept { sceneObject ->
            Log.d(TAG, "createAndApplyStereoMaterial: SceneObject received, mesh=${sceneObject.mesh}")
            val mesh = sceneObject.mesh
            if (mesh != null) {
                val existingMaterials = mesh.materials
                Log.d(TAG, "createAndApplyStereoMaterial: Found ${existingMaterials?.size ?: 0} materials")
                if (existingMaterials != null && existingMaterials.isNotEmpty()) {
                    val existingMaterial = existingMaterials[0]
                    Log.d(TAG, "createAndApplyStereoMaterial: Updating material=$existingMaterial")
                    
                    // Use StereoMode.LeftRight to ensure separate render passes per eye
                    // This is required for getStereoPassId() to work correctly in the shader
                    // Our custom shader will then apply depth control and fix eye inversion
                    existingMaterial.setStereoMode(StereoMode.LeftRight)
                    Log.d(TAG, "createAndApplyStereoMaterial: Set StereoMode.LeftRight for separate eye passes")
                    
                    // Set initial material uniforms for 3DS-style depth control
                    // matParams.x = depthFactor (S): 0.0 = monoscopic, 1.0 = full parallax
                    // matParams.y = stereoFormat: 0.0 = side-by-side, 1.0 = over-under
                    existingMaterial.setAttribute("matParams", Vector4(depthFactor, stereoFormat, 0f, 0f))
                    // stereoParams: x = unused, y = unused, z = debugMode (1.0 = enable debug colors)
                    existingMaterial.setAttribute("stereoParams", Vector4(0f, 0f, if (debugMode) 1.0f else 0.0f, 0f))
                    Log.d(TAG, "createAndApplyStereoMaterial: Set uniforms: matParams=($depthFactor, $stereoFormat), debugMode=$debugMode")
                    
                    // Apply custom shader that manually splits texture based on eye index
                    val meshComponent = entity.getComponent<Mesh>()
                    if (meshComponent != null) {
                        Log.d(TAG, "createAndApplyStereoMaterial: Found Mesh component, setting shader override to 'stereo_video'")
                        meshComponent.defaultShaderOverride = "stereo_video"
                        entity.setComponent(meshComponent)
                        Log.d(TAG, "createAndApplyStereoMaterial: Shader override set and component updated")
                    } else {
                        Log.e(TAG, "createAndApplyStereoMaterial: ERROR - Mesh component not found on entity!")
                    }
                    
                    Log.d(TAG, "createAndApplyStereoMaterial: Material setup complete")
                } else {
                    Log.w(TAG, "createAndApplyStereoMaterial: No existing materials found on mesh")
                }
            } else {
                Log.w(TAG, "createAndApplyStereoMaterial: SceneObject mesh is null")
            }
        }?.exceptionally { throwable ->
            Log.e(TAG, "createAndApplyStereoMaterial: ERROR getting SceneObject", throwable)
            null
        }
    }

    /**
     * Updates material uniforms with current depth and stereo parameters.
     */
    private fun updateMaterialUniforms() {
        val entity = videoEntity ?: return
        
        systemManager.findSystem<SceneObjectSystem>()?.getSceneObject(entity)?.thenAccept { sceneObject ->
            val mesh = sceneObject.mesh
            val existingMaterials = mesh?.materials
            if (existingMaterials != null && existingMaterials.isNotEmpty()) {
                val material = existingMaterials[0]
                
                // matParams: x = depthFactor (S), y = stereoFormat
                // S = 0.0: true monoscopic, S = 1.0: full parallax
                material.setAttribute("matParams", Vector4(depthFactor, stereoFormat, 0f, 0f))
                
                // stereoParams: x = unused, y = unused, z = debugMode (1.0 = enable debug colors)
                material.setAttribute("stereoParams", Vector4(0f, 0f, if (debugMode) 1.0f else 0.0f, 0f))
                
                Log.d(TAG, "Material uniforms updated: depthFactor=$depthFactor, stereoFormat=$stereoFormat")
            }
        }
    }

    /**
     * Cleans up resources when stereo mode is disabled.
     */
    fun cleanup() {
        videoTexture = null
        videoEntity = null
        Log.d(TAG, "StereoVideoSystem cleaned up")
    }
}


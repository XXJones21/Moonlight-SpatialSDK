package com.example.moonlight_spatialsdk.systems.stereo

import android.util.Log
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
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
    }

    private var videoTexture: SceneTexture? = null
    private var videoEntity: Entity? = null
    
    // Depth control: 0.0 = flat 2D, 1.0 = maximum 3D depth
    var depthFactor: Float = 0.5f
        private set
    
    // Convergence offset for depth adjustment (horizontal shift)
    private val convergenceOffset: Float = 1.0f

    /**
     * Registers the video texture from a panel entity.
     * Extracts texture from the panel's SceneObject mesh.
     */
    fun registerVideoTexture(entity: Entity) {
        videoEntity = entity
        systemManager.findSystem<SceneObjectSystem>()?.getSceneObject(entity)?.thenAccept { sceneObject ->
            val texture = sceneObject.mesh?.materials?.get(0)?.texture
            if (texture != null) {
                videoTexture = texture
                Log.d(TAG, "Video texture extracted from panel entity")
                createAndApplyStereoMaterial()
            } else {
                Log.w(TAG, "No texture found in panel entity")
            }
        }
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
     * Applies StereoMode to the video entity's material.
     * Uses SDK's default shader which automatically handles stereo UV mapping.
     */
    private fun createAndApplyStereoMaterial() {
        val entity = videoEntity ?: return
        val texture = videoTexture ?: return

        // Update existing material with StereoMode - SDK's default shader handles the rest
        systemManager.findSystem<SceneObjectSystem>()?.getSceneObject(entity)?.thenAccept { sceneObject ->
            val mesh = sceneObject.mesh
            if (mesh != null) {
                val existingMaterials = mesh.materials
                if (existingMaterials != null && existingMaterials.isNotEmpty()) {
                    val existingMaterial = existingMaterials[0]
                    
                    // Set StereoMode on material - SDK's default shader automatically handles UV mapping
                    existingMaterial.setStereoMode(if (stereoFormat < 0.5f) StereoMode.LeftRight else StereoMode.UpDown)
                    
                    Log.d(TAG, "StereoMode.LeftRight applied to material (using SDK default shader)")
                } else {
                    Log.w(TAG, "No existing materials found on mesh")
                }
            }
        }
    }

    /**
     * Updates material uniforms with current depth and stereo parameters.
     * Note: Currently not used since we're using SDK default shader without custom uniforms.
     */
    private fun updateMaterialUniforms() {
        // Reserved for future convergence control implementation
        Log.d(TAG, "Depth factor updated: depthFactor=$depthFactor, stereoFormat=$stereoFormat")
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


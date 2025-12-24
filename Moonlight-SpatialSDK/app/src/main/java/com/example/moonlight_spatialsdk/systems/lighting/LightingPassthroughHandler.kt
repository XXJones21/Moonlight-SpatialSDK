package com.example.moonlight_spatialsdk.systems.lighting

import android.util.Log
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Lut
import com.meta.spatial.runtime.Scene

/**
 * Handles passthrough tinting and lighting intensity for immersive mode.
 * 
 * This handler controls the visual environment when streaming:
 * - Room dimming: Applies a color lookup table (LUT) to darken the passthrough view
 * - Lighting intensity: Controls the brightness of hero lighting effects
 * 
 * The implementation uses Meta Spatial SDK's setPassthroughLUT to modify the
 * passthrough appearance without affecting the video panel itself.
 * 
 * Ported from PremiumMediaSample's LightingPassthroughHandler.
 */
class LightingPassthroughHandler(
    private val scene: Scene
) {
    companion object {
        private const val TAG = "LightingPassthroughHandler"
        
        // Preset values for different streaming states
        // 0 = full passthrough (no tint), 1 = no passthrough (fully dimmed)
        const val STREAMING_ACTIVE_PASSTHROUGH = 0.3f   // 30% visibility when streaming
        const val STREAMING_PAUSED_PASSTHROUGH = 1.0f   // 100% visibility when paused
        const val DISABLED_PASSTHROUGH = 1.0f           // 100% visibility when disabled
    }

    // Base passthrough value (0 = fully dimmed, 1 = full passthrough)
    private var currentPassthrough: Float = 1f

    // Multiplier applied to passthrough based on state
    private var tintMultiplier: Float = 1f

    // Current lighting intensity (for hero lighting system)
    private var _currentLighting: Float = 0.5f
    val currentLighting: Float
        get() = _currentLighting

    // Lighting multiplier for transitions
    private var lightingMultiplier: Float = 1f

    /**
     * Sets the passthrough tint value.
     * 
     * @param passthroughValue Value from 0.0 to 1.0 where:
     *   - 0.0 = fully dimmed (black passthrough)
     *   - 1.0 = full passthrough (no tint)
     */
    fun tintPassthrough(passthroughValue: Float) {
        currentPassthrough = passthroughValue.coerceIn(0f, 1f)
        val multipliedPassthrough = currentPassthrough * tintMultiplier
        applyPassthroughLUT(Color4(multipliedPassthrough, multipliedPassthrough, multipliedPassthrough, 1f))
    }

    /**
     * Applies a color tint to the passthrough view using a LUT.
     * 
     * @param color The color to tint with (RGB values determine brightness)
     */
    private fun applyPassthroughLUT(color: Color4) {
        val tintR = (16 * color.red).toInt()
        val tintG = (16 * color.green).toInt()
        val tintB = (16 * color.blue).toInt()

        // Initialize LUT with 16x16x16 color cube
        val tbl = Lut()
        for (r in 0..15) {
            for (g in 0..15) {
                for (b in 0..15) {
                    // Apply tint mapping: each color channel is scaled by the tint value
                    tbl.setMapping(
                        r, g, b,
                        r * tintR + r / 4,
                        g * tintG + g / 4,
                        b * tintB + b / 4
                    )
                }
            }
        }

        try {
            scene.setPassthroughLUT(tbl)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying passthrough LUT", e)
        }
    }

    /**
     * Sets the lighting intensity for hero lighting effects.
     * 
     * @param value Lighting intensity from 0.0 to 1.0
     */
    fun setLighting(value: Float) {
        _currentLighting = value.coerceIn(0f, 1f)
        // This will be connected to HeroLightingSystem when implemented
        Log.d(TAG, "Lighting intensity set to: $_currentLighting")
    }

    /**
     * Sets the tint multiplier for passthrough dimming.
     * Used to modify the base passthrough level based on streaming state.
     * 
     * @param multiplier Value from 0.0 to 1.0 where lower values dim more
     */
    fun setTintMultiplier(multiplier: Float) {
        tintMultiplier = multiplier.coerceIn(0f, 1f)
        tintPassthrough(currentPassthrough)
    }

    /**
     * Enables room dimming for active streaming.
     * Dims the passthrough to reduce visual distraction.
     */
    fun enableRoomDimming() {
        Log.i(TAG, "Room dimming enabled - dimming passthrough to ${STREAMING_ACTIVE_PASSTHROUGH * 100}%")
        tintPassthrough(STREAMING_ACTIVE_PASSTHROUGH)
    }

    /**
     * Disables room dimming, restoring full passthrough visibility.
     */
    fun disableRoomDimming() {
        Log.i(TAG, "Room dimming disabled - restoring full passthrough")
        tintPassthrough(DISABLED_PASSTHROUGH)
    }

    /**
     * Resets all lighting and passthrough to default values.
     */
    fun reset() {
        currentPassthrough = 1f
        tintMultiplier = 1f
        _currentLighting = 0.5f
        lightingMultiplier = 1f
        tintPassthrough(DISABLED_PASSTHROUGH)
    }
}


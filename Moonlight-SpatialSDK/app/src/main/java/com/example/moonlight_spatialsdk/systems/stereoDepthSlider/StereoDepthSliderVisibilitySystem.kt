package com.example.moonlight_spatialsdk.systems.stereoDepthSlider

import com.meta.spatial.core.SystemBase
import com.example.moonlight_spatialsdk.entities.StereoDepthSliderEntity
import com.example.moonlight_spatialsdk.systems.pointerInfo.PointerInfoSystem
import com.example.moonlight_spatialsdk.systems.scalable.TouchScalableSystem
import com.meta.spatial.core.Entity
import com.meta.spatial.toolkit.Grabbable

/**
 * System that manages visibility of the stereo depth slider based on user interaction.
 * 
 * Behavior matches ButtonShelfVisibilitySystem:
 * - Shows slider when user hovers over video panel (if stereoscopic depth enabled)
 * - Hides after 1.5 seconds of inactivity
 * - Hides when video panel is being scaled
 * - Hides when video panel is grabbed
 * 
 * Only active when stereoscopic depth feature is enabled.
 */
class StereoDepthSliderVisibilitySystem(
    private val slider: StereoDepthSliderEntity,
    private val videoPanelEntity: Entity
) : SystemBase() {
    private var sliderVisible: Boolean = false
    private var sliderActiveTime = 0L
    private val sliderShowDuration = 1500L // 1.5 seconds
    private var wasGrabbingVideo = false

    private var activelyTracking = false

    fun startTracking() {
        activelyTracking = true
        setSliderVisibility(isVisible = true)
    }

    fun stopTracking() {
        activelyTracking = false
        setSliderVisibility(isVisible = false)
    }

    override fun execute() {
        if (!activelyTracking) return

        // Hide slider after inactivity
        val now = System.currentTimeMillis()
        handleInactivity(now)
        updateSliderActiveTime()
        handleGrabbing()
        handleScaling()
    }

    private fun handleScaling() {
        if (activelyTracking) {
            // If we are scaling the video panel, hide slider
            val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
            if (touchScalableSystem != null && touchScalableSystem.currentlyScaling.contains(videoPanelEntity)) {
                setSliderVisibility(isVisible = false)
            }
        }
    }

    private fun handleInactivity(now: Long) {
        if (sliderVisible && now > (sliderActiveTime + sliderShowDuration)) {
            setSliderVisibility(isVisible = false)
        }
    }

    private fun updateSliderActiveTime() {
        // If we're pointing at the video panel, then keep slider alive
        val pointerInfoSystem = systemManager.findSystem<PointerInfoSystem>()
        if (pointerInfoSystem.checkHover(videoPanelEntity)) {
            sliderActiveTime = System.currentTimeMillis()
            if (!sliderVisible) {
                setSliderVisibility(isVisible = true)
            }
        }
    }

    private fun handleGrabbing() {
        if (activelyTracking) {
            // If we're grabbing the video panel, hide slider
            val grabComponent = videoPanelEntity.tryGetComponent<Grabbable>()
            if (grabComponent != null) {
                val isGrabbingVideo = grabComponent.isGrabbed
                if (isGrabbingVideo != wasGrabbingVideo) {
                    if (isGrabbingVideo) {
                        setSliderVisibility(isVisible = false)
                    }
                    wasGrabbingVideo = isGrabbingVideo
                }
            }
        }
    }

    private fun setSliderVisibility(isVisible: Boolean) {
        if (isVisible) {
            sliderActiveTime = System.currentTimeMillis()
        }
        sliderVisible = isVisible
        slider.setVisible(isVisible)
    }
}


package com.example.moonlight_spatialsdk.systems.buttonShelfVisibility

import com.meta.spatial.core.SystemBase
import com.example.moonlight_spatialsdk.entities.ButtonShelfEntity
import com.example.moonlight_spatialsdk.systems.pointerInfo.PointerInfoSystem
import com.example.moonlight_spatialsdk.systems.scalable.TouchScalableSystem
import com.meta.spatial.core.Entity
import com.meta.spatial.toolkit.Grabbable

class ButtonShelfVisibilitySystem(
    private val buttonShelf: ButtonShelfEntity,
    private val videoPanelEntity: Entity
) : SystemBase() {
  private var shelfVisible: Boolean = false
  private var shelfActiveTime = 0L
  private val shelfShowDuration = 3000L // 3 seconds
  private var wasGrabbingVideo = false

  private var activelyTracking = false

  fun startTracking() {
    activelyTracking = true
    setShelfVisibility(isVisible = true)
  }

  fun stopTracking() {
    activelyTracking = false
    setShelfVisibility(isVisible = false)
  }

  override fun execute() {
    if (!activelyTracking) return

    // Hide shelf after inactivity
    val now = System.currentTimeMillis()
    handleInactivity(now)
    updateShelfActiveTime()
    handleGrabbing()
    handleScaling()
  }
  
  private fun handleScaling() {
    if (activelyTracking) {
      // If we are scaling the video panel, hide shelf
      val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
      if (touchScalableSystem != null && touchScalableSystem.currentlyScaling.contains(videoPanelEntity)) {
        setShelfVisibility(isVisible = false)
      }
    }
  }

  private fun handleInactivity(now: Long) {
    if (shelfVisible && now > (shelfActiveTime + shelfShowDuration)) {
      setShelfVisibility(isVisible = false)
    }
  }

  private fun updateShelfActiveTime() {
    // If we're pointing at the video panel, then keep shelf alive
    val pointerInfoSystem = systemManager.findSystem<PointerInfoSystem>()
    if (pointerInfoSystem.checkHover(videoPanelEntity)) {
      shelfActiveTime = System.currentTimeMillis()
      if (!shelfVisible) {
        setShelfVisibility(isVisible = true)
      }
    }
  }

  private fun handleGrabbing() {
    if (activelyTracking) {
      // If we're grabbing the video panel, hide shelf
      val grabComponent = videoPanelEntity.tryGetComponent<Grabbable>()
      if (grabComponent != null) {
        val isGrabbingVideo = grabComponent.isGrabbed
        if (isGrabbingVideo != wasGrabbingVideo) {
          if (isGrabbingVideo) {
            setShelfVisibility(isVisible = false)
          }
          wasGrabbingVideo = isGrabbingVideo
        }
      }
    }
  }

  private fun setShelfVisibility(isVisible: Boolean) {
    if (isVisible) {
      shelfActiveTime = System.currentTimeMillis()
    }
    shelfVisible = isVisible
    buttonShelf.setVisible(isVisible)
  }
}

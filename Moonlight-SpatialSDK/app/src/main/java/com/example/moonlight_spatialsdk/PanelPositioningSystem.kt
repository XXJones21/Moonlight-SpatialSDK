package com.example.moonlight_spatialsdk

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.getAbsoluteTransform
import kotlin.math.atan2

class PanelPositioningSystem : SystemBase() {
  private val TAG = "PanelPositioningSystem"
  private var panelIsPositioned = false
  private var retryCount = 0
  private val maxRetries = 60
  private var panelManagerEntity: Entity? = null
  private var distanceInMeters: Float = 1.0f
  private var eyeLevelOffsetMeters: Float = 0.1f

  override fun execute() {
    if (panelIsPositioned) return

    if (retryCount < maxRetries) {
      retryCount++
      panelIsPositioned = updatePanelPosition()
    } else {
      Log.w(TAG, "Failed to position panel after $maxRetries attempts")
    }
  }

  fun setPanelEntity(entity: Entity) {
    panelManagerEntity = entity
    Log.d(TAG, "PanelManager entity reference set for positioning system")
  }

  fun setDistance(distance: Float) {
    distanceInMeters = distance
    Log.d(TAG, "Panel distance set to ${distance}m")
  }

  fun setEyeLevelOffset(offset: Float) {
    eyeLevelOffsetMeters = offset
    Log.d(TAG, "Eye level offset set to ${offset}m")
  }

  private fun updatePanelPosition(): Boolean {
    val head = getHmd()
    if (head == null) {
      return false
    }

    if (!head.hasComponent<Transform>()) {
      return false
    }

    val headPose = getAbsoluteTransform(head)
    if (headPose == Pose()) {
      return false
    }

    val headPosition = headPose.t
    if (headPosition.y < 0.5f) {
      Log.d(TAG, "Head position Y=${headPosition.y} too low, head tracking not ready yet")
      return false
    }

    val panelManagerEntity = this.panelManagerEntity
    if (panelManagerEntity == null) {
      return false
    }

    val forward = headPose.q * Vector3(0f, 0f, 1f)
    val forwardHorizontal = Vector3(forward.x, 0f, forward.z)
    val forwardNormalized = forwardHorizontal.normalize()
    if (forwardNormalized.length() < 0.1f) {
      Log.w(TAG, "Forward vector too small, cannot normalize")
      return false
    }
    val offsetFromHead = forwardNormalized * distanceInMeters

    val panelPosition = headPosition + offsetFromHead
    val panelPositionWithY = Vector3(panelPosition.x, headPosition.y - eyeLevelOffsetMeters, panelPosition.z)

    val toUser = headPosition - panelPositionWithY
    val targetYaw = Math.toDegrees(atan2(toUser.x.toDouble(), toUser.z.toDouble())).toFloat() + 180f
    val panelRotation = Quaternion.fromSequentialPYR(0f, targetYaw, 0f)

    val panelPose = Pose(panelPositionWithY, panelRotation)
    panelManagerEntity.setComponent(Transform(panelPose))
    Log.i(TAG, "PanelManager positioned ${distanceInMeters}m in front of user. Head (world): ${headPosition}, Panel: ${panelPositionWithY}, Forward: ${forward}")

    val verified = verifyPanelPosition(headPose, panelManagerEntity)
    if (verified) {
      Log.i(TAG, "PanelManager position verified via raycast, disabling system")
    }

    return verified
  }

  private fun verifyPanelPosition(headPose: Pose, panelManagerEntity: Entity): Boolean {
    val forward = headPose.q * Vector3(0f, 0f, 1f)
    val forwardHorizontal = Vector3(forward.x, 0f, forward.z)
    val forwardNormalized = forwardHorizontal.normalize()
    if (forwardNormalized.length() < 0.1f) {
      return false
    }
    val worldOrigin = headPose.t
    val worldTarget = worldOrigin + forwardNormalized * distanceInMeters

    val intersection = getScene().lineSegmentIntersect(worldOrigin, worldTarget)
    val hitEntity = intersection?.entity

    return hitEntity == panelManagerEntity
  }

  private fun getHmd(): Entity? {
    return systemManager
        .tryFindSystem<PlayerBodyAttachmentSystem>()
        ?.tryGetLocalPlayerAvatarBody()
        ?.head
  }
}


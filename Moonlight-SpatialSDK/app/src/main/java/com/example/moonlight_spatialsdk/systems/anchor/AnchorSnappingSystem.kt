package com.example.moonlight_spatialsdk.systems.anchor

import android.util.Log
import com.example.moonlight_spatialsdk.Anchorable
import com.example.moonlight_spatialsdk.AnchorOnLoad
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.mruk.getSize
import com.meta.spatial.mruk.hasLabel
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.getAbsoluteTransform
import kotlin.math.absoluteValue

/**
 * System that snaps entities with the Anchorable component to MRUK-detected planes
 * (walls, ceiling, floor) when they are grabbed and moved near those surfaces.
 * 
 * This system provides auto-snap-while-grabbed behavior, meaning:
 * - When a user grabs a panel and moves it near a wall, the panel automatically
 *   snaps to the wall surface and rotates to face the wall normal.
 * - When moved near the ceiling or floor, the panel snaps to that surface.
 * 
 * Based on PremiumMediaSample's AnchorSnappingSystem implementation.
 */
class AnchorSnappingSystem : SystemBase() {
    companion object {
        private const val TAG = "AnchorSnappingSystem"
    }
    
    private val rotationMap = mutableMapOf<Entity, Quaternion>()
    private var planeSnapSize = 0.1f
    private var anchorsLoaded = false
    
    override fun execute() {
        // Handle one-time placement on app start
        if (!anchorsLoaded) {
            if (!areMrukAndHeadLoaded()) return
            anchorsLoaded = true
            initSnapToAnchors(getHeadPose())
            return
        }
        
        // Grab all the anchorable objects, and also the walls/ceiling/floors
        val anchorables = Query.where { has(Anchorable.id, Transform.id, Grabbable.id) }.eval()
        val planes = Query.where { has(MRUKPlane.id, Transform.id, MRUKAnchor.id) }.eval()
        
        for (anchorable in anchorables) {
            if (!anchorable.getComponent<Grabbable>().isGrabbed) continue
            processGrabbedAnchorable(anchorable, planes)
        }
    }
    
    /**
     * Check if the MRUK Planes and head position are loaded.
     */
    private fun areMrukAndHeadLoaded(): Boolean {
        val planesQuery = Query.where { has(MRUKPlane.id, Transform.id, MRUKAnchor.id) }
        if (planesQuery.eval().count() == 0) return false
        
        val headPose = getHeadPose()
        return !(headPose.t.x == 0f && headPose.t.y == 0f && headPose.t.z == 0f)
    }
    
    /**
     * Every frame, find the best anchor plane for grabbed anchorables.
     */
    private fun processGrabbedAnchorable(anchorable: Entity, planes: Sequence<Entity>) {
        val anchorablePose = getAbsoluteTransform(anchorable)
        var hitPlane = false
        
        for (plane in planes) {
            if (trySnapToPlaneAnchor(anchorable, anchorablePose, plane)) {
                hitPlane = true
                break
            }
        }
        
        if (!hitPlane) {
            // Not hit box, but we're grabbing and there are anchors, so store the previous rotation
            rotationMap[anchorable] = anchorablePose.q
        }
    }
    
    /**
     * Check if there are valid anchor planes to anchor to.
     */
    private fun trySnapToPlaneAnchor(
        anchorable: Entity,
        anchorablePose: Pose,
        plane: Entity,
    ): Boolean {
        // Get values
        val planeTransform = getAbsoluteTransform(plane)
        val planeAnchor = plane.getComponent<MRUKAnchor>()
        
        // Ensure plane is either WALL, CEILING OR FLOOR
        if (!isValidPlaneAnchor(planeAnchor)) return false
        
        val planePosition = planeTransform.t
        val planeNormal = planeTransform.forward().normalize()
        val planeSize: Vector2 = plane.getComponent<MRUKPlane>().getSize()
        
        val headPosition = getHeadPose().t
        val movementOffset = (anchorablePose.t - headPosition)
        
        // Check if anchorable object centerpoint is hitting the walls/floor/ceiling
        if (
            hitTestBox(
                anchorablePose.t,
                planeTransform.t,
                Vector3(planeSize.x, planeSize.y, planeSnapSize),
                planeTransform.q,
            )
        ) {
            // Snap to plane
            snapToAnchorViaGrab(
                anchorable,
                anchorablePose,
                planeNormal,
                projectPointOntoPlane(anchorablePose.t, planePosition, planeNormal) +
                    planeNormal.times(anchorable.getComponent<Anchorable>().offset),
                planeAnchor,
            )
            return true
        } else {
            // Use backup raycast check if box test fails
            val hitInfo =
                doesRayIntersectPlane(
                    headPosition,
                    movementOffset,
                    plane,
                    maxRayLength = movementOffset.length(),
                )
            if (hitInfo != null) {
                // Snap to plane
                snapToAnchorViaGrab(anchorable, anchorablePose, planeNormal, hitInfo.point, planeAnchor)
                return true
            }
        }
        return false
    }
    
    /**
     * Continually snap/animate grabbed anchorable to discovered plane anchor every frame.
     */
    private fun snapToAnchorViaGrab(
        anchorable: Entity,
        anchorablePose: Pose,
        planeNormal: Vector3,
        snappedPosition: Vector3,
        planeAnchor: MRUKAnchor,
    ) {
        // If wall, snap position and also adjust rotation
        if (planeAnchor.hasLabel(MRUKLabel.WALL_FACE)) {
            // Find the wall normal
            var adjustedRotation = lookAt(Vector3(0f, 0f, 1f), Vector3(0f, 1f, 0f), planeNormal)
            // It's back to front, Flip on X axis 180
            adjustedRotation =
                adjustedRotation.times(Quaternion(0f, 1f, 0f, 0f))
            
            // Slerp with previous rotation
            val previousRotation = rotationMap.getOrDefault(anchorable, anchorablePose.q)
            val slerpedRotation = previousRotation.slerp(adjustedRotation, 0.1f)
            
            // Lock rotation on walls
            anchorable.setComponent(
                Transform(fromAbsoluteToLocal(Pose(snappedPosition, slerpedRotation), anchorable))
            )
            rotationMap[anchorable] = slerpedRotation
        } else {
            // Snap just position (not rotation) to ceiling and floor
            anchorable.setComponent(
                Transform(fromAbsoluteToLocal(Pose(snappedPosition, anchorablePose.q), anchorable))
            )
        }
    }
    
    /**
     * One-time anchor snapping for all AnchorOnLoad entities when app starts.
     */
    private fun initSnapToAnchors(headPose: Pose) {
        val anchorOnLoadEntities = Query.where { has(AnchorOnLoad.id, Transform.id) }.eval()
        val planes = Query.where { has(MRUKPlane.id, Transform.id, MRUKAnchor.id) }.eval()
        
        for (anchorable in anchorOnLoadEntities) {
            snapToAnchorViaGaze(anchorable, headPose, planes)
        }
    }
    
    /**
     * Find and snap to plane anchors one time, when the app starts.
     */
    private fun snapToAnchorViaGaze(
        anchorable: Entity,
        headPose: Pose,
        planesToCheck: Sequence<Entity>? = null,
        rescale: Boolean = false,
    ) {
        val anchorablePose = getAbsoluteTransform(anchorable)
        val anchorOnLoad = anchorable.tryGetComponent<AnchorOnLoad>() ?: return
        var hitPlane = false
        
        val planes: Sequence<Entity> =
            if (planesToCheck != null) planesToCheck
            else {
                val planesQuery = Query.where { has(MRUKPlane.id, Transform.id, MRUKAnchor.id) }
                planesQuery.eval()
            }
        
        for (plane in planes) {
            val planeAnchor = plane.getComponent<MRUKAnchor>()
            
            if (!isValidPlaneAnchor(planeAnchor)) {
                continue
            }
            val planeTransform = getAbsoluteTransform(plane)
            val planeNormal = planeTransform.forward().normalize()
            val headToAnchorableDirection: Vector3 = (anchorablePose.t - headPose.t).normalize()
            val hitInfo = doesRayIntersectPlane(headPose.t, headToAnchorableDirection, plane)
            if (hitInfo != null) {
                val distanceToWall = (hitInfo.point - headPose.t).length()
                
                if (distanceToWall > anchorOnLoad.distanceCheck) {
                    continue
                }
                
                hitPlane = true
                
                val newPoseAbsolute =
                    calculatePoseFromAnchorPlane(
                        anchorable,
                        anchorablePose,
                        planeAnchor,
                        planeNormal,
                        hitInfo.point,
                    )
                anchorable.setComponent(Transform(fromAbsoluteToLocal(newPoseAbsolute, anchorable)))
                
                if (rescale || anchorOnLoad.scaleProportional) {
                    val scalePercent =
                        (headPose.t - newPoseAbsolute.t).length() / (headPose.t - anchorablePose.t).length()
                    val scale = anchorable.tryGetComponent<Scale>()
                    if (scale != null) {
                        scale.scale *= scalePercent
                        anchorable.setComponent(scale)
                    }
                }
            }
            
            if (hitPlane) break
        }
    }
    
    private fun isValidPlaneAnchor(anchor: MRUKAnchor): Boolean {
        return anchor.hasLabel(MRUKLabel.WALL_FACE) ||
            anchor.hasLabel(MRUKLabel.CEILING) ||
            anchor.hasLabel(MRUKLabel.FLOOR)
    }
    
    private fun calculatePoseFromAnchorPlane(
        anchorable: Entity,
        anchorablePose: Pose,
        planeAnchor: MRUKAnchor,
        planeNormal: Vector3,
        hitPoint: Vector3,
    ): Pose {
        val newPoseAbsolute = Pose()
        if (planeAnchor.hasLabel(MRUKLabel.WALL_FACE)) {
            var adjustedRotation = lookAt(Vector3(0f, 0f, 1f), Vector3(0f, 1f, 0f), planeNormal)
            adjustedRotation =
                adjustedRotation.times(Quaternion(0f, 1f, 0f, 0f))
            
            val anchorableComponent = anchorable.tryGetComponent<Anchorable>()
            val offset = anchorableComponent?.offset ?: 0f
            val normalOffset = planeNormal * offset
            newPoseAbsolute.t = hitPoint + normalOffset
            newPoseAbsolute.q = adjustedRotation
        } else {
            newPoseAbsolute.t = hitPoint
            newPoseAbsolute.q = anchorablePose.q
        }
        
        return newPoseAbsolute
    }
    
    private fun doesRayIntersectPlane(
        rayOrigin: Vector3,
        rayDirection: Vector3,
        plane: Entity,
        epsilon: Float = 1e-6f,
        maxRayLength: Float = Float.MAX_VALUE,
    ): HitInfo? {
        val planePose = getAbsoluteTransform(plane)
        val mrukPlane = plane.getComponent<MRUKPlane>()
        
        val planePosition = planePose.t
        val planeRotation = planePose.q
        
        val localPlaneNormal = Vector3(0f, 0f, 1f)
        val localPlaneUp = Vector3(0f, 1f, 0f)
        val planeNormal = planeRotation.times(localPlaneNormal)
        val planeUp = planeRotation.times(localPlaneUp)
        
        val planeRight = planeNormal.cross(planeUp).normalize()
        
        val normalizedRayDirection = rayDirection.normalize()
        val denominator = normalizedRayDirection.dot(planeNormal)
        
        if (denominator.absoluteValue < epsilon) {
            return null
        }
        
        val t = (planePosition - rayOrigin).dot(planeNormal) / denominator
        if (t < 0f || t > maxRayLength) {
            return null
        }
        
        val intersectionPoint = rayOrigin + normalizedRayDirection * t
        
        val relativeIntersection = intersectionPoint - planePosition
        
        val distanceOnRight = relativeIntersection.dot(planeRight)
        val distanceOnUp = relativeIntersection.dot(planeUp)
        
        val minBounds = mrukPlane.min
        val maxBounds = mrukPlane.max
        
        if (
            !((distanceOnRight >= minBounds.x && distanceOnRight <= maxBounds.x) &&
                (distanceOnUp >= minBounds.y && distanceOnUp <= maxBounds.y))
        ) {
            return null
        }
        
        return HitInfo(Entity.nullEntity(), 0, 0, 0, 0f, intersectionPoint, planeNormal, Vector2())
    }
    
    // Helper functions
    
    private fun getHeadPose(): Pose {
        return try {
            val head =
                Query.where { has(AvatarAttachment.id) }
                    .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }
                    .eval()
                    .firstOrNull()
            head?.getComponent<Transform>()?.transform ?: Pose()
        } catch (e: Exception) {
            Pose()
        }
    }
    
    private fun projectPointOntoPlane(point: Vector3, planePoint: Vector3, planeNormal: Vector3): Vector3 {
        val pointToPlane = point - planePoint
        val distanceToPlane = pointToPlane.dot(planeNormal)
        val projection = point - planeNormal * distanceToPlane
        return projection
    }
    
    private fun hitTestBox(
        point: Vector3,
        boxCenter: Vector3,
        boxSize: Vector3,
        boxRotation: Quaternion,
    ): Boolean {
        val inverseRotation = boxRotation.inverse()
        val localPoint = inverseRotation * (point - boxCenter)
        
        val halfSizeX = boxSize.x * 0.5f
        val halfSizeY = boxSize.y * 0.5f
        val halfSizeZ = boxSize.z * 0.5f
        
        val insideX = localPoint.x >= -halfSizeX && localPoint.x <= halfSizeX
        val insideY = localPoint.y >= -halfSizeY && localPoint.y <= halfSizeY
        val insideZ = localPoint.z >= -halfSizeZ && localPoint.z <= halfSizeZ
        
        return insideX && insideY && insideZ
    }
    
    private fun lookAt(forward: Vector3, up: Vector3, targetDirection: Vector3): Quaternion {
        val targetDirNormalized = targetDirection.normalize()
        val forwardNormalized = forward.normalize()
        
        if (forwardNormalized == targetDirNormalized) {
            return Quaternion()
        }
        
        val rotationAxis = forwardNormalized.cross(targetDirNormalized).normalize()
        
        val dotProduct = forwardNormalized.dot(targetDirNormalized).coerceIn(-1f, 1f)
        val angle = Math.acos(dotProduct.toDouble()).toFloat()
        
        return fromAxisAngle(rotationAxis, angle)
    }
    
    private fun fromAxisAngle(axis: Vector3, angle: Float): Quaternion {
        val halfAngle = angle * 0.5f
        val sinHalfAngle = kotlin.math.sin(halfAngle)
        return Quaternion(
            axis.x * sinHalfAngle,
            axis.y * sinHalfAngle,
            axis.z * sinHalfAngle,
            kotlin.math.cos(halfAngle),
        )
    }
    
    private fun fromAbsoluteToLocal(globalPose: Pose = Pose(), localRelativeTo: Entity): Pose {
        if (localRelativeTo.hasComponent<TransformParent>()) {
            val parentGlobal = getAbsoluteTransform(localRelativeTo.getComponent<TransformParent>().entity)
            return parentGlobal.inverse().times(globalPose)
        }
        return globalPose
    }
}

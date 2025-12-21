package com.example.moonlight_spatialsdk.systems.anchor

import android.util.Log
import com.example.moonlight_spatialsdk.Anchorable
import com.example.moonlight_spatialsdk.AnchorOnLoad
import com.example.moonlight_spatialsdk.WallSnap
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
 * System that handles two types of anchor snapping behavior:
 * 
 * 1. **Anchorable Component**: Proximity-based snapping to MRUK-detected planes
 *    (walls, ceiling, floor) when entities are grabbed and moved near those surfaces.
 * 
 * 2. **WallSnap Component**: Wall-plane-constrained movement where:
 *    - On grab: Entity snaps to the nearest wall
 *    - While grabbed: Movement is constrained to the wall plane (X/Y sliding, Z locked)
 *    - On release: Entity stays on the wall
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
        
        // Get all planes (walls/ceiling/floors) for snapping
        val planes = Query.where { has(MRUKPlane.id, Transform.id, MRUKAnchor.id) }.eval()
        
        // Process Anchorable entities (proximity-based snapping)
        val anchorables = Query.where { has(Anchorable.id, Transform.id, Grabbable.id) }.eval()
        for (anchorable in anchorables) {
            if (!anchorable.getComponent<Grabbable>().isGrabbed) continue
            processGrabbedAnchorable(anchorable, planes)
        }
        
        // Process WallSnap entities (wall-plane-constrained movement)
        val wallSnapEntities = Query.where { has(WallSnap.id, Transform.id, Grabbable.id) }.eval()
        for (entity in wallSnapEntities) {
            val wallSnap = entity.getComponent<WallSnap>()
            val grabbable = entity.getComponent<Grabbable>()
            
            if (!wallSnap.isEnabled) continue
            
            if (grabbable.isGrabbed) {
                processWallSnapGrabbed(entity, wallSnap, planes)
            } else if (wallSnap.isSnappedToWall) {
                // Released - clear the snapped state for next grab
                wallSnap.isSnappedToWall = false
                entity.setComponent(wallSnap)
            }
        }
    }
    
    /**
     * Process a WallSnap entity that is currently grabbed.
     * 
     * On first frame grabbed: Find the nearest wall and store plane data in the component.
     * While grabbed: Project the entity position onto the stored wall plane.
     */
    private fun processWallSnapGrabbed(entity: Entity, wallSnap: WallSnap, planes: Sequence<Entity>) {
        val entityPose = getAbsoluteTransform(entity)
        
        if (!wallSnap.isSnappedToWall) {
            // First frame grabbed: find and snap to nearest wall
            val nearestWall = findNearestWall(entityPose.t, planes)
            if (nearestWall != null) {
                wallSnap.wallPlaneNormal = nearestWall.normal
                wallSnap.wallPlanePoint = nearestWall.point
                wallSnap.isSnappedToWall = true
                entity.setComponent(wallSnap)
                Log.d(TAG, "WallSnap: Snapped to wall, normal=${nearestWall.normal}, point=${nearestWall.point}")
            }
        }
        
        if (wallSnap.isSnappedToWall) {
            // Project current position onto the stored wall plane
            val projectedPos = projectPointOntoPlane(
                entityPose.t,
                wallSnap.wallPlanePoint,
                wallSnap.wallPlaneNormal
            ) + wallSnap.wallPlaneNormal * wallSnap.wallOffset
            
            // Calculate wall-facing rotation
            val wallRotation = calculateWallFacingRotation(wallSnap.wallPlaneNormal)
            
            // Apply constrained position and rotation
            entity.setComponent(
                Transform(fromAbsoluteToLocal(Pose(projectedPos, wallRotation), entity))
            )
        }
    }
    
    /**
     * Data class representing a wall plane for WallSnap.
     */
    private data class WallPlaneInfo(val normal: Vector3, val point: Vector3)
    
    /**
     * Find the nearest wall to the given position using a raycast from head through the entity.
     * Only considers walls (not ceiling or floor) for WallSnap behavior.
     */
    private fun findNearestWall(entityPosition: Vector3, planes: Sequence<Entity>): WallPlaneInfo? {
        val headPose = getHeadPose()
        val rayDirection = (entityPosition - headPose.t).normalize()
        
        var nearestWall: WallPlaneInfo? = null
        var nearestDistance = Float.MAX_VALUE
        
        for (plane in planes) {
            val planeAnchor = plane.getComponent<MRUKAnchor>()
            
            // Only consider walls for WallSnap
            if (!planeAnchor.hasLabel(MRUKLabel.WALL_FACE)) continue
            
            val hitInfo = doesRayIntersectPlane(headPose.t, rayDirection, plane)
            if (hitInfo != null) {
                val distance = (hitInfo.point - headPose.t).length()
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    val planeTransform = getAbsoluteTransform(plane)
                    nearestWall = WallPlaneInfo(
                        normal = planeTransform.forward().normalize(),
                        point = hitInfo.point
                    )
                }
            }
        }
        
        // If raycast didn't find a wall, try finding the closest wall by proximity
        if (nearestWall == null) {
            for (plane in planes) {
                val planeAnchor = plane.getComponent<MRUKAnchor>()
                if (!planeAnchor.hasLabel(MRUKLabel.WALL_FACE)) continue
                
                val planeTransform = getAbsoluteTransform(plane)
                val planeNormal = planeTransform.forward().normalize()
                val projectedPoint = projectPointOntoPlane(entityPosition, planeTransform.t, planeNormal)
                val distance = (projectedPoint - entityPosition).length()
                
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestWall = WallPlaneInfo(normal = planeNormal, point = projectedPoint)
                }
            }
        }
        
        return nearestWall
    }
    
    /**
     * Calculate the rotation for an entity to face a wall (opposite to wall normal).
     */
    private fun calculateWallFacingRotation(wallNormal: Vector3): Quaternion {
        var adjustedRotation = lookAt(Vector3(0f, 0f, 1f), Vector3(0f, 1f, 0f), wallNormal)
        // Flip 180 degrees to face opposite the wall normal (facing outward from wall)
        adjustedRotation = adjustedRotation.times(Quaternion(0f, 1f, 0f, 0f))
        return adjustedRotation
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

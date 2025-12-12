package com.example.moonlight_spatialsdk

import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun Quaternion.Companion.fromAxisAngle(axis: Vector3, angleDegrees: Float): Quaternion {
  val angleRadians = angleDegrees * PI / 180f
  val halfAngle = angleRadians / 2
  val sinHalfAngle = sin(halfAngle).toFloat()

  return Quaternion(
          cos(halfAngle).toFloat(),
          axis.x * sinHalfAngle,
          axis.y * sinHalfAngle,
          axis.z * sinHalfAngle,
      )
      .normalize()
}

fun Quaternion.Companion.fromSequentialPYR(
    pitchDeg: Float,
    yawDeg: Float,
    rollDeg: Float,
): Quaternion {
  return Quaternion.fromAxisAngle(Vector3.Right, pitchDeg)
      .times(Quaternion.fromAxisAngle(Vector3.Up, yawDeg))
      .times(Quaternion.fromAxisAngle(Vector3.Forward, rollDeg))
      .normalize()
}

// Function to project a ray onto a plane
fun projectRayOntoPlane(
    rayOrigin: Vector3,
    rayDirection: Vector3,
    planePoint: Vector3,
    planeNormal: Vector3,
): Vector3? {
  // Normalize the plane normal and ray direction
  val normalizedPlaneNormal = planeNormal.normalize()
  val normalizedRayDirection = rayDirection.normalize()

  // Compute the dot product between the ray direction and the plane normal
  val denominator = normalizedRayDirection.dot(normalizedPlaneNormal)

  // If the denominator is 0, the ray is parallel to the plane (no intersection)
  if (denominator == 0f) {
    return null // No intersection
  }

  // Compute the parameter t for the intersection point
  val t = (planePoint - rayOrigin).dot(normalizedPlaneNormal) / denominator

  // If t < 0, the intersection is behind the ray origin, so ignore it
  if (t < 0f) {
    return null // No valid intersection in the ray's forward direction
  }

  // Calculate the intersection point
  val intersectionPoint = rayOrigin + normalizedRayDirection * t

  return intersectionPoint
}


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


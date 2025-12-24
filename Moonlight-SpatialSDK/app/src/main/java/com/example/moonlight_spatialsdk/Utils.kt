package com.example.moonlight_spatialsdk

import android.graphics.Color
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.TriangleMesh
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.Scale
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

/**
 * Gets the effective size of an entity by combining PanelDimensions and Scale components.
 * 
 * @param entity The entity to get the size from
 * @return Vector3 containing the final computed size (width, height, depth)
 */
fun getSize(entity: Entity): Vector3 {
  val panelDimensions = entity.tryGetComponent<PanelDimensions>()
  val scale = entity.tryGetComponent<Scale>()
  if (panelDimensions != null && scale != null) {
    return Vector3(
        panelDimensions.dimensions.x * scale.scale.x,
        panelDimensions.dimensions.y * scale.scale.y,
        scale.scale.z,
    )
  } else if (panelDimensions != null) {
    return Vector3(panelDimensions.dimensions.x, panelDimensions.dimensions.y, 1f)
  } else if (scale != null) {
    return scale.scale
  }

  return Vector3(1f)
}

/**
 * Creates a subdivided quad TriangleMesh for use with custom shaders.
 * 
 * Subdivision creates smooth lighting gradients across the mesh surface.
 * Used by WallLightingSystem to create overlay meshes on MRUK surfaces.
 * 
 * @param width Width of the quad in meters
 * @param height Height of the quad in meters
 * @param material Material to apply to the mesh
 * @param xSubDivisions Number of subdivisions along x-axis
 * @param ySubdivisions Number of subdivisions along y-axis
 * @return TriangleMesh ready for use with SceneMesh.fromTriangleMesh
 */
fun quadTriangleMesh(
    width: Float = 1f,
    height: Float = 1f,
    material: SceneMaterial,
    xSubDivisions: Int = 1,
    ySubdivisions: Int = 1,
): TriangleMesh {
    val halfWidth = width * 0.5f
    val halfHeight = height * 0.5f

    val vertexLength = (xSubDivisions + 1) * (ySubdivisions + 1)
    val trianglesLength = xSubDivisions * ySubdivisions * 2

    val triangleMesh = TriangleMesh(
        vertexLength,
        trianglesLength * 3,
        intArrayOf(0, trianglesLength * 3),
        arrayOf(material),
    )

    // Array Creations
    val vertices = FloatArray(vertexLength * 3)
    val normals = FloatArray(vertexLength * 3)
    val uvs = FloatArray(vertexLength * 2)
    val colors = IntArray(vertexLength) { Color.WHITE }
    val triangles = IntArray(trianglesLength * 3)

    // Initial setup of values
    val uStep = 1f / xSubDivisions
    val vStep = 1f / ySubdivisions
    var index = 0
    val widthStep = uStep * width
    val heightStep = vStep * height

    for (y in 0 until ySubdivisions + 1) {
        for (x in 0 until xSubDivisions + 1) {
            index = y * (xSubDivisions + 1) + x

            uvs[index * 2] = x * uStep
            uvs[index * 2 + 1] = 1 - y * vStep

            vertices[index * 3] = x * widthStep - halfWidth
            vertices[index * 3 + 1] = y * heightStep - halfHeight

            normals[index * 3 + 2] = 1f // Forward facing normal
        }
    }

    // Assign triangles
    var triangleIndex = 0

    for (y in 0 until ySubdivisions) {
        for (x in 0 until xSubDivisions) {
            // Calculate vertex indices for the current square
            val bottomLeft = y * (xSubDivisions + 1) + x
            val topLeft = bottomLeft + (xSubDivisions + 1)
            val topRight = topLeft + 1
            val bottomRight = bottomLeft + 1

            triangles[triangleIndex++] = bottomLeft
            triangles[triangleIndex++] = topLeft
            triangles[triangleIndex++] = bottomRight

            triangles[triangleIndex++] = bottomRight
            triangles[triangleIndex++] = topLeft
            triangles[triangleIndex++] = topRight
        }
    }

    // Update the triangles
    triangleMesh.updateGeometry(0, vertices, normals, uvs, colors)
    triangleMesh.updatePrimitives(0, triangles)

    return triangleMesh
}

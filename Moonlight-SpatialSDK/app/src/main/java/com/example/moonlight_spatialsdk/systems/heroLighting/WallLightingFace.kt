package com.example.moonlight_spatialsdk.systems.heroLighting

import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKLabel

/**
 * Data class representing an MRUK surface face for lighting.
 * 
 * @param label The MRUK label type (WALL_FACE, FLOOR, CEILING, etc.)
 * @param direction Optional direction vector for directional faces
 */
data class WallLightingFace(
    val label: MRUKLabel,
    val direction: Vector3? = null,
) {
    override fun toString(): String {
        if (direction == null) return label.name
        return label.name + "_" + direction.toString()
    }
}


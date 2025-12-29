package com.example.moonlight_spatialsdk.entities

import com.example.moonlight_spatialsdk.R
import com.example.moonlight_spatialsdk.ScaledChild
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.getAbsoluteTransform

/**
 * Entity representing a vertical depth control slider positioned on the right side of the video panel.
 * 
 * The slider follows the video panel's position, rotation, and scale (similar to buttonShelf and scaling handles).
 * It appears when the user hovers over the video panel and stereoscopic depth is enabled.
 */
class StereoDepthSliderEntity {
    companion object {
        private const val WIDTH_IN_METERS = 0.9f  // Same width as buttonShelf
        private const val HEIGHT_IN_METERS = 0.12f  // Same height as buttonShelf
    }

    val entity: Entity
    var parentEntity: Entity? = null

    private var sliderOffset = Vector3(0f, 0f, 0f)
        set(value) {
            if (field != value) {
                field = value
                updateTransform()
            }
        }

    init {
        entity =
            Entity.create(
                PanelDimensions(Vector2(WIDTH_IN_METERS, HEIGHT_IN_METERS)),
                Panel(R.id.stereo_depth_slider),
                Transform(),
                Visible(false),
                TransformParent(Entity.nullEntity()),
            )
    }

    fun setVisible(isVisible: Boolean) {
        entity.setComponent(Visible(isVisible))
    }

    /**
     * Attaches the slider to the video panel entity, positioning it above the panel.
     */
    fun attachToEntity(target: Entity) {
        val panelDimensions = target.tryGetComponent<PanelDimensions>()
        if (panelDimensions != null) {
            // Position above panel, centered horizontally, with extra spacing to prevent text cutoff
            sliderOffset.x = 0f // Center horizontally
            sliderOffset.y = panelDimensions.dimensions.y * 0.5f + HEIGHT_IN_METERS * 0.5f + 0.05f
            sliderOffset.z = 0f // Same plane as panel
        }

        if (parentEntity == null) {
            reparent(target)
        }
    }

    private fun reparent(parent: Entity) {
        parentEntity = parent

        val transformParent = entity.getComponent<TransformParent>()
        if (transformParent.entity != parent) {
            transformParent.entity = parent
            updateTransform()
            entity.setComponent(transformParent)
            entity.setComponent(ScaledChild(localPosition = sliderOffset, pivotOffset = Vector3(0f, 0f, 0f)))
        }
        entity.setComponent(Scale(1f))
    }

    fun detachFromEntity() {
        val scaledChild = entity.tryGetComponent<ScaledChild>()
        scaledChild?.isEnabled = false
        val globalPosition = getAbsoluteTransform(entity)
        entity.setComponent(TransformParent(Entity.nullEntity()))
        entity.setComponent(Transform(globalPosition))
        if (scaledChild != null) {
            entity.setComponent(scaledChild)
        }
        parentEntity = null
    }

    private fun updateTransform() {
        entity.setComponent(Transform(Pose(sliderOffset)))
    }

    fun destroy() {
        entity.destroy()
    }
}


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

class ButtonShelfEntity {
  companion object {
    private const val WIDTH_IN_METERS = 0.9f
    private const val HEIGHT_IN_METERS = 0.12f
  }

  val entity: Entity
  var parentEntity: Entity? = null

  private var shelfOffset = Vector3(0f, 0f, 0f)
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
            Panel(R.id.button_shelf),
            Transform(),
            Visible(false),
            TransformParent(Entity.nullEntity()),
        )
  }

  fun setVisible(isVisible: Boolean) {
    entity.setComponent(Visible(isVisible))
  }

  fun attachToEntity(target: Entity) {
    val panelDimensions = target.tryGetComponent<PanelDimensions>()
    if (panelDimensions != null) {
      // Place at bottom of the panel
      shelfOffset.y = panelDimensions.dimensions.y * -0.5f - HEIGHT_IN_METERS * 0.5f
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
      entity.setComponent(ScaledChild(localPosition = shelfOffset, pivotOffset = Vector3(0f, 0f, 0f)))
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
    entity.setComponent(Transform(Pose(shelfOffset)))
  }

  fun destroy() {
    entity.destroy()
  }
}

package com.example.moonlight_spatialsdk

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/**
 * PanelManager manages the root entity that serves as the parent for all panel entities.
 * This allows all panels to be positioned together as a group.
 */
class PanelManager {
  private val TAG = "PanelManager"
  var panelManagerEntity: Entity? = null
    private set

  /**
   * Creates the root PanelManager entity.
   * This entity will be positioned by PanelPositioningSystem and serves as the parent
   * for all child panel entities (video panel, connection panel, etc.)
   */
  fun create(): Entity {
    Log.i(TAG, "Creating PanelManager entity")
    
    panelManagerEntity = Entity.create(
        listOf(
            Transform(),
            Visible(true),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
        )
    )
    
    Log.i(TAG, "PanelManager entity created")
    return panelManagerEntity!!
  }
}


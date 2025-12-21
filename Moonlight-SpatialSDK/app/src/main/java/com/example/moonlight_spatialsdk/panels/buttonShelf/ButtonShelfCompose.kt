package com.example.moonlight_spatialsdk.panels.buttonShelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.ButtonShelf
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.Close
import com.meta.spatial.uiset.theme.icons.regular.Settings
import com.meta.spatial.uiset.theme.icons.regular.SidebarPin
import com.meta.spatial.uiset.theme.icons.regular.VolumeOn
import com.meta.spatial.uiset.theme.icons.regular.Zoom

/**
 * Modifier that scales content and also reduces its measured layout size accordingly.
 * Unlike Modifier.scale() which only visually scales, this affects layout measurements.
 */
private fun Modifier.scaleWithLayout(scaleFactor: Float) = this
    .scale(scaleFactor)
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val scaledWidth = (placeable.width * scaleFactor).toInt()
        val scaledHeight = (placeable.height * scaleFactor).toInt()
        layout(scaledWidth, scaledHeight) {
            placeable.place(
                x = ((scaledWidth - placeable.width) / 2f).toInt(),
                y = ((scaledHeight - placeable.height) / 2f).toInt()
            )
        }
    }

/**
 * Button shelf composable with controls for the video panel.
 * 
 * @param isSpatializeEnabled Whether spatial audio and room mesh are currently enabled
 * @param isSnapEnabled Whether snap-to-wall is currently enabled
 * @param onSettingsClick Callback for settings button press
 * @param onResetScaleClick Callback for reset scale button press
 * @param onSpatializeClick Callback for spatialize toggle button press
 * @param onSnapToWallClick Callback for snap to wall toggle button press
 * @param onDisconnectClick Callback for disconnect button press
 */
@Composable
fun ButtonShelfCompose(
    isSpatializeEnabled: Boolean = false,
    isSnapEnabled: Boolean = false,
    onSettingsClick: () -> Unit,
    onResetScaleClick: () -> Unit,
    onSpatializeClick: () -> Unit = {},
    onSnapToWallClick: () -> Unit = {},
    onDisconnectClick: () -> Unit
) {
  val focusManager = LocalFocusManager.current
  
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
      Box(modifier = Modifier.scaleWithLayout(0.5f)) {
        ButtonShelf(
            icon = { Icon(SpatialIcons.Regular.Settings, contentDescription = "Settings") },
            label = "Settings",
            selected = false,
            onSelectionChange = { focusManager.clearFocus(); onSettingsClick() },
        )
      }
      
      Box(modifier = Modifier.scaleWithLayout(0.5f)) {
        ButtonShelf(
            icon = { Icon(SpatialIcons.Regular.Zoom, contentDescription = "Resize") },
            label = "Resize",
            selected = false,
            onSelectionChange = { focusManager.clearFocus(); onResetScaleClick() },
        )
      }
      
      Box(modifier = Modifier.scaleWithLayout(0.5f)) {
        ButtonShelf(
            icon = { Icon(SpatialIcons.Regular.VolumeOn, contentDescription = "Spatialize") },
            label = "Spatialize",
            selected = isSpatializeEnabled,
            onSelectionChange = { focusManager.clearFocus(); onSpatializeClick() },
        )
      }
      
      Box(modifier = Modifier.scaleWithLayout(0.5f)) {
        ButtonShelf(
            icon = { Icon(SpatialIcons.Regular.SidebarPin, contentDescription = "Snap") },
            label = "Snap",
            selected = isSnapEnabled,
            onSelectionChange = { focusManager.clearFocus(); onSnapToWallClick() },
        )
      }
      
      Box(modifier = Modifier.scaleWithLayout(0.5f)) {
        ButtonShelf(
            icon = { Icon(SpatialIcons.Regular.Close, contentDescription = "Disconnect") },
            label = "Disconnect",
            selected = false,
            onSelectionChange = { focusManager.clearFocus(); onDisconnectClick() },
        )
      }
    }
  }
}

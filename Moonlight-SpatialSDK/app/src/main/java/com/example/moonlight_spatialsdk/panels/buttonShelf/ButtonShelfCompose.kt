package com.example.moonlight_spatialsdk.panels.buttonShelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.ButtonShelf
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.Close
import com.meta.spatial.uiset.theme.icons.regular.Settings
import com.meta.spatial.uiset.theme.icons.regular.SidebarPin
import com.meta.spatial.uiset.theme.icons.regular.VolumeOn
import com.meta.spatial.uiset.theme.icons.regular.Zoom

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
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.Settings, contentDescription = "Settings") },
          label = "Settings",
          selected = false,
          onSelectionChange = { onSettingsClick() },
      )
      
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.Zoom, contentDescription = "Resize") },
          label = "Resize",
          selected = false,
          onSelectionChange = { onResetScaleClick() },
      )
      
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.VolumeOn, contentDescription = "Spatialize") },
          label = "Spatialize",
          selected = isSpatializeEnabled,
          onSelectionChange = { onSpatializeClick() },
      )
      
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.SidebarPin, contentDescription = "Snap") },
          label = "Snap",
          selected = isSnapEnabled,
          onSelectionChange = { onSnapToWallClick() },
      )
      
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.Close, contentDescription = "Disconnect") },
          label = "Disconnect",
          selected = false,
          onSelectionChange = { onDisconnectClick() },
      )
    }
  }
}

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
import com.meta.spatial.uiset.theme.icons.regular.Zoom

@Composable
fun ButtonShelfCompose(
    onSettingsClick: () -> Unit,
    onResetScaleClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
    ) {
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.Settings, contentDescription = "Settings") },
          label = "Settings",
          selected = false,
          onSelectionChange = { onSettingsClick() },
      )
      
      ButtonShelf(
          icon = { Icon(SpatialIcons.Regular.Zoom, contentDescription = "Reset Scale") },
          label = "Reset Scale",
          selected = false,
          onSelectionChange = { onResetScaleClick() },
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

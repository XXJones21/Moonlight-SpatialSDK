package com.example.moonlight_spatialsdk

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meta.spatial.toolkit.PanelConstants
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme

const val OPTIONS_PANEL_WIDTH = 0.85f
const val OPTIONS_PANEL_HEIGHT = 0.75f

@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

@Composable
@Preview(
    widthDp = (PanelConstants.DEFAULT_DP_PER_METER * OPTIONS_PANEL_WIDTH).toInt(),
    heightDp = (PanelConstants.DEFAULT_DP_PER_METER * OPTIONS_PANEL_HEIGHT).toInt(),
)
fun OptionsPanelPreview() {
  OptionsPanel(
      onConnect = { _, _, _ -> },
      onDisconnect = {},
      connectionStatus = "Disconnected",
      isConnected = false
  )
}

@Composable
fun ConnectionPanel(
    activity: ImmersiveActivity,
    onConnect: (String, Int, Int) -> Unit,
    onDisconnect: () -> Unit
) {
  val connectionStatus by activity.connectionStatus.collectAsState()
  val isConnected by activity.isConnected.collectAsState()
  
  OptionsPanel(
      onConnect = onConnect,
      onDisconnect = onDisconnect,
      connectionStatus = connectionStatus,
      isConnected = isConnected
  )
}

@Composable
private fun OptionsPanel(
    onConnect: (String, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    connectionStatus: String,
    isConnected: Boolean
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("47989") }
  var appId by remember { mutableStateOf("0") }
  
  val hostFocusRequester = remember { FocusRequester() }
  val portFocusRequester = remember { FocusRequester() }
  val appIdFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = "Moonlight Connection",
          style = MaterialTheme.typography.headlineMedium,
      )
      
      Text(
          text = connectionStatus,
          style = MaterialTheme.typography.bodyMedium,
      )

      key("host_field") {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host / IP Address") },
            enabled = !isConnected,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { portFocusRequester.requestFocus() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(hostFocusRequester),
        )
      }

      key("port_field") {
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            enabled = !isConnected,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { appIdFocusRequester.requestFocus() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(portFocusRequester),
        )
      }

      key("appid_field") {
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("App ID (0 for desktop)") },
            enabled = !isConnected,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(appIdFocusRequester),
        )
      }

      if (isConnected) {
        SecondaryButton(
            label = "Disconnect",
            expanded = true,
            onClick = onDisconnect,
        )
      } else {
        PrimaryButton(
            label = "Connect",
            expanded = true,
            onClick = {
              val portInt = port.toIntOrNull() ?: 47989
              val appIdInt = appId.toIntOrNull() ?: 0
              onConnect(host, portInt, appIdInt)
            },
        )
      }
    }
  }
}

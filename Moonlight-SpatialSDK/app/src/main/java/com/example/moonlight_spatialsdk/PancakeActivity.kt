package com.example.moonlight_spatialsdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme

class PancakeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTheme(R.style.PanelAppThemeTransparent)
    setContent {
      ConnectionPanel2D(
          onConnect = { host, port, appId ->
            val immersiveIntent = Intent(this, ImmersiveActivity::class.java).apply {
              action = Intent.ACTION_MAIN
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              putExtra("host", host)
              putExtra("port", port)
              putExtra("appId", appId)
            }
            startActivity(immersiveIntent)
          },
          onLaunchImmersive = {
            val immersiveIntent = Intent(this, ImmersiveActivity::class.java).apply {
              action = Intent.ACTION_MAIN
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(immersiveIntent)
          }
      )
    }
  }
}

@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

@Composable
fun ConnectionPanel2D(
    onConnect: (String, Int, Int) -> Unit,
    onLaunchImmersive: () -> Unit
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("47989") }
  var appId by remember { mutableStateOf("0") }
  var connectionStatus by remember { mutableStateOf("Ready to connect") }
  var isConnected by remember { mutableStateOf(false) }
  
  val focusManager = LocalFocusManager.current

  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(48.dp),
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
              onNext = { focusManager.moveFocus(FocusDirection.Next) }
          ),
          modifier = Modifier.fillMaxWidth(),
      )

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
              onNext = { focusManager.moveFocus(FocusDirection.Next) }
          ),
          modifier = Modifier.fillMaxWidth(),
      )

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
          modifier = Modifier.fillMaxWidth(),
      )

      if (isConnected) {
        SecondaryButton(
            label = "Disconnect",
            expanded = true,
            onClick = {
              isConnected = false
              connectionStatus = "Disconnected"
            },
        )
      } else {
        PrimaryButton(
            label = "Connect & Launch Immersive",
            expanded = true,
            onClick = {
              val portInt = port.toIntOrNull() ?: 47989
              val appIdInt = appId.toIntOrNull() ?: 0
              if (host.isNotBlank()) {
                connectionStatus = "Connecting..."
                onConnect(host, portInt, appIdInt)
              } else {
                connectionStatus = "Error: Host cannot be empty"
              }
            },
        )
      }
      
      Spacer(modifier = Modifier.height(16.dp))
      
      SecondaryButton(
          label = "Launch Immersive Mode (No Connection)",
          expanded = true,
          onClick = onLaunchImmersive,
      )
    }
  }
}


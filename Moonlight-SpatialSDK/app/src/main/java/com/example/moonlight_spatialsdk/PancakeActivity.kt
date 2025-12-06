package com.example.moonlight_spatialsdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
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
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.nvstream.http.PairingManager
import com.limelight.preferences.PreferenceConfiguration

class PancakeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTheme(R.style.PanelAppThemeTransparent)
    
    // Initialize MediaCodecHelper before creating any decoder renderers
    MediaCodecHelper.initialize(this, "spatial-panel")
    
    val prefs = PreferenceConfiguration.readPreferences(this)
    val panelRenderer = MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> }
    )
    val connectionManager = MoonlightConnectionManager(
        context = this,
        activity = this,
        decoderRenderer = panelRenderer.getDecoder(),
        audioRenderer = AndroidAudioRenderer(this, false),
        onStatusUpdate = null
    )
    setContent {
      ConnectionPanel2D(
          connectionManager = connectionManager,
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
    connectionManager: MoonlightConnectionManager,
    onConnect: (String, Int, Int) -> Unit,
    onLaunchImmersive: () -> Unit
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("47989") }
  var appId by remember { mutableStateOf("0") }
  var generatedPin by remember { mutableStateOf<String?>(null) }
  var connectionStatus by remember { mutableStateOf("Ready to connect") }
  var isConnected by remember { mutableStateOf(false) }
  var needsPairing by remember { mutableStateOf(false) }
  var isCheckingPairing by remember { mutableStateOf(false) }
  var isPairing by remember { mutableStateOf(false) }
  
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
          enabled = !isConnected && !needsPairing,
          singleLine = true,
          keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Number,
              imeAction = ImeAction.Next
          ),
          keyboardActions = KeyboardActions(
              onNext = { focusManager.clearFocus() }
          ),
          modifier = Modifier.fillMaxWidth(),
      )

      if (needsPairing) {
        if (generatedPin == null) {
          // Generate PIN when pairing is needed
          LaunchedEffect(Unit) {
            generatedPin = PairingManager.generatePinString()
            isPairing = true
            connectionStatus = "Enter PIN $generatedPin on your server..."
            val portInt = port.toIntOrNull() ?: 47989
            connectionManager.pairWithServer(host, portInt, generatedPin!!) { success, error ->
              isPairing = false
              if (success) {
                connectionStatus = "Paired! Click Connect to continue"
                needsPairing = false
                generatedPin = null
              } else {
                when (error) {
                  "Incorrect PIN" -> {
                    connectionStatus = "PIN incorrect. Please try again."
                    generatedPin = null // Regenerate PIN
                  }
                  "Pairing already in progress" -> {
                    connectionStatus = "Another device is pairing. Please wait."
                    generatedPin = null
                  }
                  else -> {
                    connectionStatus = error ?: "Pairing failed. Please try again."
                    generatedPin = null
                  }
                }
              }
            }
          }
        }
        
        if (generatedPin != null) {
          Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            Text(
                text = "Enter this PIN on your server:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = generatedPin!!,
                style = MaterialTheme.typography.headlineLarge,
            )
            if (isPairing) {
              Text(
                  text = "Waiting for PIN entry on server...",
                  style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }

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
        if (isCheckingPairing || isPairing) {
          PrimaryButton(
              label = if (isCheckingPairing) "Checking..." else "Pairing...",
              expanded = true,
              onClick = { },
          )
        } else {
          if (needsPairing) {
            PrimaryButton(
                label = "Pairing in progress...",
                expanded = true,
                onClick = { },
            )
          } else {
            PrimaryButton(
                label = "Connect & Launch Immersive",
                expanded = true,
                onClick = {
                  val portInt = port.toIntOrNull() ?: 47989
                  val appIdInt = appId.toIntOrNull() ?: 0
                  if (host.isNotBlank()) {
                    isCheckingPairing = true
                    connectionStatus = "Checking pairing..."
                    connectionManager.checkPairing(host, portInt) { isPaired, error ->
                      isCheckingPairing = false
                      if (isPaired) {
                        connectionStatus = "Connecting..."
                        onConnect(host, portInt, appIdInt)
                      } else {
                        needsPairing = true
                        connectionStatus = error ?: "Server requires pairing. Generating PIN..."
                      }
                    }
                  } else {
                    connectionStatus = "Error: Host cannot be empty"
                  }
                },
            )
          }
        }
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


package com.example.moonlight_spatialsdk

import android.content.Intent
import android.content.Context.MODE_PRIVATE
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import android.preference.PreferenceManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration

class PancakeActivity : ComponentActivity() {
  private val TAG = "PancakeActivity"
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
    val shared = getSharedPreferences("connection_prefs", MODE_PRIVATE)
    val savedHost = shared.getString("saved_host", "") ?: ""
    val savedPort = shared.getString("saved_port", "47989") ?: "47989"
    val savedAppId = shared.getString("saved_appId", "0") ?: "0"
    setContent {
      ConnectionPanel2D(
          connectionManager = connectionManager,
          decoderRenderer = panelRenderer.getDecoder(),
          savedHost = savedHost,
          savedPort = savedPort,
          savedAppId = savedAppId,
          onSaveConnection = { h: String, p: String, a: String ->
            getSharedPreferences("connection_prefs", MODE_PRIVATE).edit()
                .putString("saved_host", h)
                .putString("saved_port", p)
                .putString("saved_appId", a)
                .apply()
          },
          onClearPairing = {
            IdentityStore.clearAll(this)
            Log.i(TAG, "Cleared client pairing state and pinned certificates")
          },
          onConnect = { host, port, appId ->
            Log.i(TAG, "onConnect clicked host=$host port=$port appId=$appId")
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
            Log.i(TAG, "Launch immersive without connection params")
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
private fun LabeledDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
    Text(text = label, style = MaterialTheme.typography.bodySmall)
    Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedTextField(
          value = selected,
          onValueChange = { },
          readOnly = true,
          modifier = Modifier
              .fillMaxWidth()
              .clickable { expanded = true },
          trailingIcon = { androidx.compose.material3.Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null) },
      )
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
          DropdownMenuItem(
              text = { Text(option) },
              onClick = {
                onSelect(option)
                expanded = false
              },
          )
        }
      }
    }
  }
}

@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

@Composable
fun ConnectionPanel2D(
    connectionManager: MoonlightConnectionManager,
    decoderRenderer: MediaCodecDecoderRenderer,
    savedHost: String,
    savedPort: String,
    savedAppId: String,
    onSaveConnection: (String, String, String) -> Unit,
    onClearPairing: () -> Unit,
    onConnect: (String, Int, Int) -> Unit,
    onLaunchImmersive: () -> Unit
) {
  val context = LocalContext.current
  var host by remember { mutableStateOf(savedHost) }
  var port by remember { mutableStateOf(savedPort) }
  var appId by remember { mutableStateOf(savedAppId) }
  var generatedPin by remember { mutableStateOf<String?>(null) }
  var connectionStatus by remember { mutableStateOf("Ready to connect") }
  var isConnected by remember { mutableStateOf(false) }
  var needsPairing by remember { mutableStateOf(false) }
  var isCheckingPairing by remember { mutableStateOf(false) }
  var isPairing by remember { mutableStateOf(false) }
  var pairingAttemptKey by remember { mutableStateOf(0) }
  var showConfig by remember { mutableStateOf(false) }
  val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
  var selectedRes by remember { mutableStateOf(defaultPrefs.getString("list_resolution", "1280x720") ?: "1280x720") }
  var selectedFps by remember { mutableStateOf(defaultPrefs.getString("list_fps", "60") ?: "60") }
  var selectedFormat by remember { mutableStateOf(defaultPrefs.getString("video_format", "auto") ?: "auto") }
  var resOptions by remember {
    mutableStateOf(
        listOf(
            "640x360",
            "854x480",
            "1280x720",
            "1920x1080",
            "2560x1440",
            "3840x2160",
        )
    )
  }
  var fpsOptions by remember { mutableStateOf(listOf("30", "60", "90", "120")) }
  var formatOptions by remember { mutableStateOf(listOf("auto", "h264", "hevc", "av1")) }
  var capabilitySummary by remember { mutableStateOf("") }
  var capabilityStatus by remember { mutableStateOf<String?>(null) }

  val focusManager = LocalFocusManager.current
  val scrollState = rememberScrollState()

  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .verticalScroll(scrollState)
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
          // Generate PIN when pairing is needed - use pairingAttemptKey to prevent infinite loop
          LaunchedEffect(pairingAttemptKey) {
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
                    connectionStatus = "PIN incorrect. Click 'Retry Pairing' to try again."
                  }
                  "Pairing already in progress" -> {
                    connectionStatus = "Another device is pairing. Please wait, then tap Retry."
                  }
                  else -> {
                    connectionStatus = error ?: "Pairing failed. Click 'Retry Pairing' to try again."
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
            if (isPairing) {
              PrimaryButton(
                  label = "Pairing in progress...",
                  expanded = true,
                  onClick = { },
              )
            } else {
              PrimaryButton(
                  label = "Retry Pairing",
                  expanded = true,
                  onClick = {
                    pairingAttemptKey++ // Trigger new PIN generation
                    generatedPin = null
                  },
              )
            }
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
                    onSaveConnection(host, port, appId)
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
          label = "Reset Client Pairing (clear cert & UID)",
          expanded = true,
          onClick = {
            onClearPairing()
            // Reset local UI state to force a fresh pairing flow
            isConnected = false
            needsPairing = true
            generatedPin = null
            pairingAttemptKey++
            connectionStatus = "Cleared client pairing; re-pair required"
          },
      )

      Spacer(modifier = Modifier.height(8.dp))

      SecondaryButton(
          label = if (showConfig) "Hide Stream Configuration" else "Configure Stream (Device Capabilities)",
          expanded = true,
          onClick = {
            showConfig = !showConfig
            if (showConfig) {
              val capsBits: Int = decoderRenderer.capabilities
              val slices = (capsBits ushr 24) and 0xFF
              val rfiAvc = (capsBits and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC) != 0
              val rfiHevc = (capsBits and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC) != 0
              val rfiAv1 = (capsBits and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1) != 0
              val direct = (capsBits and MoonBridge.CAPABILITY_DIRECT_SUBMIT) != 0
              capabilitySummary =
                  "SlicesPerFrame=$slices, RFI_AVC=$rfiAvc, RFI_HEVC=$rfiHevc, RFI_AV1=$rfiAv1, DirectSubmit=$direct"
              capabilityStatus = "Loading server capabilities..."
              val portInt = port.toIntOrNull() ?: 47989
              if (host.isBlank()) {
                capabilityStatus = "Enter host/port to load capabilities"
              } else {
                connectionManager.fetchServerCapabilities(host, portInt) { caps, error ->
                  if (caps == null || error != null) {
                    capabilityStatus = error ?: "Failed to load server capabilities"
                    return@fetchServerCapabilities
                  }
                  capabilityStatus = "Capabilities loaded"
                  val baseRes =
                      listOf("640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160")
                  val maxPixels = listOf(caps.maxLumaH264, caps.maxLumaHEVC).maxOrNull() ?: 0
                  val filteredRes =
                      baseRes.filter { res ->
                        val parts = res.split("x")
                        val pixels =
                            if (parts.size == 2) parts[0].toLong() * parts[1].toLong() else Long.MAX_VALUE
                        (maxPixels == 0L || pixels <= maxPixels) && (caps.supports4k || res != "3840x2160")
                      }
                  resOptions = if (filteredRes.isNotEmpty()) filteredRes else baseRes
                  if (!resOptions.contains(selectedRes)) {
                    selectedRes = resOptions.first()
                  }
                  val formatList = mutableListOf("auto")
                  val codec = caps.codecModeSupport
                  val hasH264 = codec == 0L || (codec and 0x3L) != 0L
                  val hasHevc = codec == 0L || (codec and (1L shl 8 or (1L shl 9) or (1L shl 10))) != 0L
                  if (hasH264) formatList.add("h264")
                  if (hasHevc) formatList.add("hevc")
                  formatList.add("av1")
                  formatOptions = formatList.distinct()
                  if (!formatOptions.contains(selectedFormat)) {
                    selectedFormat = formatOptions.first()
                  }
                }
              }
            }
          },
      )

      if (showConfig) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(text = "Decoder capabilities: $capabilitySummary", style = MaterialTheme.typography.bodySmall)
          capabilityStatus?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
          }
          Text(text = "Select resolution / fps / format to apply before launch", style = MaterialTheme.typography.bodySmall)

          LabeledDropdown(
              label = "Resolution",
              options = resOptions,
              selected = selectedRes,
              onSelect = { selectedRes = it },
          )
          LabeledDropdown(
              label = "FPS",
              options = fpsOptions,
              selected = selectedFps,
              onSelect = { selectedFps = it },
          )
          LabeledDropdown(
              label = "Format",
              options = formatOptions,
              selected = selectedFormat,
              onSelect = { selectedFormat = it },
          )

          PrimaryButton(
              label = "Apply Stream Settings",
              expanded = true,
              onClick = {
            val shared = PreferenceManager.getDefaultSharedPreferences(context)
                shared.edit()
                    .putString("list_resolution", selectedRes)
                    .putString("list_fps", selectedFps)
                    .putString("video_format", selectedFormat)
                    .apply()
                connectionStatus = "Applied stream prefs (resolution/fps/format)"
              },
          )
        }
      }

      SecondaryButton(
          label = "Launch Immersive Mode (No Connection)",
          expanded = true,
          onClick = onLaunchImmersive,
      )
    }
  }
}


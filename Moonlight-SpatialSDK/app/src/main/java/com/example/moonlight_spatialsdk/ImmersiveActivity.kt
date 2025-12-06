package com.example.moonlight_spatialsdk

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.net.toUri
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.preferences.PreferenceConfiguration
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ImmersiveActivity : AppSystemActivity() {
  private val activityScope = CoroutineScope(Dispatchers.Main)
  private val prefs by lazy { PreferenceConfiguration.readPreferences(this) }
  private val moonlightPanelRenderer by lazy {
    MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> },
    )
  }
  private val audioRenderer by lazy {
    AndroidAudioRenderer(this, prefs.enableAudioFx)
  }
  private val connectionManager by lazy {
    MoonlightConnectionManager(
        context = this,
        activity = this,
        decoderRenderer = moonlightPanelRenderer.getDecoder(),
        audioRenderer = audioRenderer,
        onStatusUpdate = { status, connected ->
          _connectionStatus.value = status
          _isConnected.value = connected
        }
    )
  }
  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
  
  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  override fun registerFeatures(): List<SpatialFeature> {
    val features =
        mutableListOf<SpatialFeature>(
            VRFeature(this),
            ComposeFeature(),
            IsdkFeature(this, spatial, systemManager),
        )
    if (BuildConfig.DEBUG) {
      features.add(CastInputForwardFeature(this))
      features.add(HotReloadFeature(this))
      features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
      features.add(DataModelInspectorFeature(spatial, this.componentManager))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )

    // Enable MR mode
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)

    // Check for connection parameters from PancakeActivity
    val host = intent.getStringExtra("host")
    val port = intent.getIntExtra("port", 47989)
    val appId = intent.getIntExtra("appId", 0)
    
    if (!host.isNullOrBlank()) {
      // Launch connection in background
      connectToHost(host, port, appId)
    }

    loadGLXF()
  }

  override fun onSceneReady() {
    super.onSceneReady()

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")

    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
  }


  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        VideoSurfacePanelRegistration(
            MOONLIGHT_PANEL_ID,
            surfaceConsumer = { _, surface ->
              moonlightPanelRenderer.attachSurface(surface)
            },
            settingsCreator = {
              MediaPanelSettings(
                  shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
                  display = PixelDisplayOptions(width = 1920, height = 1080),
                  rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
              )
            },
        ),
    )
  }

  override fun onSpatialShutdown() {
    super.onSpatialShutdown()
    disconnect()
  }

  /**
   * Returns to 2D panel mode in Home environment.
   * This follows the Meta Spatial SDK hybrid app pattern for seamless transitions.
   * See: https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview
   */
  fun launchPanelModeInHome() {
    // Create the intent used to launch the panel component
    val panelIntent =
        Intent(applicationContext, PancakeActivity::class.java).apply {
          action = Intent.ACTION_MAIN
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    // Wrap the created Intent in a PendingIntent object
    val pendingPanelIntent =
        PendingIntent.getActivity(
            applicationContext,
            0,
            panelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Create and send the Intent to launch the Home environment, providing the
    // PendingIntent object as extra parameters
    val homeIntent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("extra_launch_in_home_pending_intent", pendingPanelIntent)
    startActivity(homeIntent)
  }

  private fun connectToHost(host: String, port: Int, appId: Int) {
    if (host.isBlank()) {
      _connectionStatus.value = "Error: Host cannot be empty"
      return
    }

    _connectionStatus.value = "Connecting..."
    _isConnected.value = false

    val uniqueId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        ?: "0123456789ABCDEF"

    connectionManager.startStream(
        host = host,
        port = port,
        appId = appId,
        uniqueId = uniqueId,
        prefs = prefs
    )
  }

  private fun disconnect() {
    connectionManager.stopStream()
    _connectionStatus.value = "Disconnected"
    _isConnected.value = false
  }

  private fun loadGLXF(): Job {
    return activityScope.launch {
      glXFManager.inflateGLXF(
          "apk:///scenes/Composition.glxf".toUri(),
          keyName = "example_key_name",
      )
    }
  }

  companion object {
    private const val MOONLIGHT_PANEL_ID = 1001
  }
}

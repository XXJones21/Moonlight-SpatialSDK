package com.example.moonlight_spatialsdk

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.preferences.PreferenceConfiguration
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
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

class ImmersiveActivity : AppSystemActivity() {
  private val TAG = "ImmersiveActivity"
  private val prefs by lazy { PreferenceConfiguration.readPreferences(this) }
  private lateinit var moonlightPanelRenderer: MoonlightPanelRenderer
  private lateinit var audioRenderer: AndroidAudioRenderer
  private lateinit var connectionManager: MoonlightConnectionManager
  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
  
  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
  
  private var pendingConnectionParams: Triple<String, Int, Int>? = null
  private var isPaired: Boolean = false
  private var isSurfaceReady: Boolean = false
  private var videoPanelEntity: Entity? = null

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
    // CRITICAL TEST LOG - This MUST appear in logcat if onCreate is called
    System.out.println("=== IMMERSIVE_ACTIVITY_ONCREATE_START ===")
    android.util.Log.e(TAG, "=== IMMERSIVE_ACTIVITY_ONCREATE_START ===")
    super.onCreate(savedInstanceState)
    
    // Initialize MediaCodecHelper before creating any decoder renderers
    MediaCodecHelper.initialize(this, "spatial-panel")
    
    // Create decoder renderer in onCreate() like moonlight-android does
    // This ensures decoder is initialized before any connection attempts
    moonlightPanelRenderer = MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> },
    )
    audioRenderer = AndroidAudioRenderer(this, prefs.enableAudioFx)
    connectionManager = MoonlightConnectionManager(
        context = this,
        activity = this,
        decoderRenderer = moonlightPanelRenderer.getDecoder(),
        audioRenderer = audioRenderer,
        onStatusUpdate = { status, connected ->
          _connectionStatus.value = status
          _isConnected.value = connected
        }
    )
    
    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )

    // Check for connection parameters from PancakeActivity
    val host = intent.getStringExtra("host")
    val port = intent.getIntExtra("port", 47989)
    val appId = intent.getIntExtra("appId", 0)
    System.out.println("=== IMMERSIVE_ACTIVITY_EXTRAS host=$host port=$port appId=$appId ===")
    android.util.Log.e(TAG, "=== IMMERSIVE_ACTIVITY_EXTRAS host=$host port=$port appId=$appId ===")
    Log.i(TAG, "onCreate extras host=$host port=$port appId=$appId")
    
    if (!host.isNullOrBlank()) {
      // Store connection params but don't connect yet - wait for panel surface to be ready
      Log.i(TAG, "Host provided, storing connection params for later connection")
      pendingConnectionParams = Triple(host, port, appId)
    } else {
      Log.i(TAG, "No host provided; immersive launched without connection params")
    }
  }

  override fun onSceneReady() {
    System.out.println("=== ONSCENE_READY_CALLED ===")
    android.util.Log.e(TAG, "=== ONSCENE_READY_CALLED ===")
    super.onSceneReady()

    // Enable MR mode - scene and systemManager are now available
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")

    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)

    System.out.println("=== CALLING_CREATE_VIDEO_PANEL_ENTITY ===")
    android.util.Log.e(TAG, "=== CALLING_CREATE_VIDEO_PANEL_ENTITY ===")
    createVideoPanelEntity()
  }


  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    System.out.println("=== REGISTER_PANELS_CALLED ===")
    android.util.Log.e(TAG, "=== REGISTER_PANELS_CALLED ===")
    return listOf(
        VideoSurfacePanelRegistration(
            R.id.ui_example,
            surfaceConsumer = { panelEntity, surface ->
              System.out.println("=== SURFACE_CONSUMER_CALLED panelEntity=$panelEntity ===")
              android.util.Log.e(TAG, "=== SURFACE_CONSUMER_CALLED panelEntity=$panelEntity ===")
              Log.i(TAG, "Surface attached for panel entity=$panelEntity")
              
              // Store the panel entity reference
              videoPanelEntity = panelEntity
              
              // Ensure panel is visible and positioned
              panelEntity.setComponent(Visible(true))
              panelEntity.setComponent(Transform(
                  Pose(
                      Vector3(0f, 1.1f, -1.5f),
                      Quaternion(0f, 0f, 0f, 1f)
                  )
              ))
              panelEntity.setComponent(Grabbable(enabled = true))
              
              SurfaceUtil.paintBlack(surface)
              
              // Configure decoder with preferences when panel is created
              System.out.println("=== ATTACHING_SURFACE_AND_CONFIGURING_DECODER ===")
              android.util.Log.e(TAG, "=== ATTACHING_SURFACE_AND_CONFIGURING_DECODER ===")
              moonlightPanelRenderer.attachSurface(surface)
              
              // Pre-configure decoder with settings from PancakeActivity
              System.out.println("=== PRECONFIGURING_DECODER ===")
              android.util.Log.e(TAG, "=== PRECONFIGURING_DECODER ===")
              moonlightPanelRenderer.preConfigureDecoder()
              
              isSurfaceReady = true
              System.out.println("=== SURFACE_READY_AND_DECODER_CONFIGURED ===")
              android.util.Log.e(TAG, "=== SURFACE_READY_AND_DECODER_CONFIGURED ===")
              
              // Now that panel surface is ready and decoder is configured, initiate connection if we have pending params
              val params = pendingConnectionParams
              if (params != null) {
                val (host, port, appId) = params
                System.out.println("=== CONNECTING host=$host port=$port appId=$appId ===")
                android.util.Log.e(TAG, "=== CONNECTING host=$host port=$port appId=$appId ===")
                Log.i(TAG, "Panel surface ready, decoder configured, initiating connection host=$host port=$port appId=$appId")
                connectToHost(host, port, appId)
              } else {
                System.out.println("=== NO_PENDING_CONNECTION_PARAMS ===")
                android.util.Log.e(TAG, "=== NO_PENDING_CONNECTION_PARAMS ===")
                Log.d(TAG, "Panel surface ready but no pending connection params")
              }
            },
            settingsCreator = {
              System.out.println("=== SETTINGS_CREATOR_CALLED ===")
              android.util.Log.e(TAG, "=== SETTINGS_CREATOR_CALLED ===")
              MediaPanelSettings(
                  shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
                  display = PixelDisplayOptions(width = 1920, height = 1080),
                  rendering = MediaPanelRenderOptions(
                      isDRM = true,
                      stereoMode = StereoMode.None
                  ),
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
      Log.w(TAG, "connectToHost called with empty host")
      _connectionStatus.value = "Error: Host cannot be empty"
      return
    }

    // PancakeActivity already verified pairing before launching ImmersiveActivity,
    // so we can skip the redundant checkPairing() call and directly start the stream.
    // This ensures only ImmersiveActivity initiates streaming connections.
    Log.i(TAG, "connectToHost: Pairing already verified by PancakeActivity, starting stream host=$host port=$port appId=$appId")
    _connectionStatus.value = "Connecting..."
    _isConnected.value = false
    pendingConnectionParams = Triple(host, port, appId)
    isPaired = true // Assume paired since PancakeActivity verified it
    
    startStreamIfReady()
  }
  
  private fun startStreamIfReady() {
    val params = pendingConnectionParams
    if (params != null && isPaired && isSurfaceReady) {
      val (host, port, appId) = params
      Log.i(TAG, "Starting stream - surface ready and paired host=$host port=$port appId=$appId")
        _connectionStatus.value = "Connecting..."
      pendingConnectionParams = null

        connectionManager.startStream(
            host = host,
            port = port,
            appId = appId,
            prefs = prefs
        )
      Log.i(TAG, "startStream invoked host=$host port=$port appId=$appId")
      } else {
      if (params != null) {
        Log.d(TAG, "Stream not ready: isPaired=$isPaired isSurfaceReady=$isSurfaceReady")
      }
    }
  }

  private fun disconnect() {
    Log.i(TAG, "disconnect invoked")
    connectionManager.stopStream()
    _connectionStatus.value = "Disconnected"
    _isConnected.value = false
    pendingConnectionParams = null
    isPaired = false
    isSurfaceReady = false
  }

  private fun createVideoPanelEntity() {
    System.out.println("=== CREATE_VIDEO_PANEL_ENTITY_CALLED ===")
    android.util.Log.e(TAG, "=== CREATE_VIDEO_PANEL_ENTITY_CALLED ===")
    Log.i(TAG, "Creating video panel entity with Panel(R.id.ui_example)")
    
    videoPanelEntity = Entity.create(
        listOf(
            Panel(R.id.ui_example),
            Transform(
                Pose(
                    Vector3(0f, 1.1f, -1.5f),
                    Quaternion(0f, 0f, 0f, 1f)
                )
            ),
            Grabbable(enabled = true),
            Visible(true)
        )
    )
    
    System.out.println("=== VIDEO_PANEL_ENTITY_CREATED ===")
    android.util.Log.e(TAG, "=== VIDEO_PANEL_ENTITY_CREATED ===")
    Log.i(TAG, "Video panel entity created at (0, 1.1, -1.5) - visible and grabbable")
  }

}

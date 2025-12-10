package com.example.moonlight_spatialsdk

import android.app.PendingIntent
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.moonlight_spatialsdk.BuildConfig
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.preferences.PreferenceConfiguration
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
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
import com.meta.spatial.compose.composePanel
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
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
  private val basePanelHeightMeters = 0.7f
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
  private var connectionPanelEntity: Entity? = null
  private var panelPositioningSystem: PanelPositioningSystem? = null
  private lateinit var pairingHelper: MoonlightPairingHelper

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

    // Create decoder renderer in onCreate() like moonlight-android does
    // This ensures decoder is initialized before any connection attempts
    moonlightPanelRenderer = MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> },
    )
    audioRenderer = AndroidAudioRenderer(this, prefs.enableAudioFx)
    pairingHelper = MoonlightPairingHelper(this)
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

    panelPositioningSystem = PanelPositioningSystem()
    systemManager.registerSystem(panelPositioningSystem!!)
    Log.i(TAG, "PanelPositioningSystem registered")

    System.out.println("=== CALLING_CREATE_VIDEO_PANEL_ENTITY ===")
    android.util.Log.e(TAG, "=== CALLING_CREATE_VIDEO_PANEL_ENTITY ===")
    createVideoPanelEntity()
    
    System.out.println("=== CALLING_CREATE_CONNECTION_PANEL_ENTITY ===")
    android.util.Log.e(TAG, "=== CALLING_CREATE_CONNECTION_PANEL_ENTITY ===")
    createConnectionPanelEntity()
  }


  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    System.out.println("=== REGISTER_PANELS_CALLED ===")
    android.util.Log.e(TAG, "=== REGISTER_PANELS_CALLED ===")
    
    val shared = getSharedPreferences("connection_prefs", MODE_PRIVATE)
    val savedHost = shared.getString("saved_host", "") ?: ""
    val savedPort = shared.getString("saved_port", "47989") ?: "47989"
    val savedAppId = shared.getString("saved_appId", "0") ?: "0"
    
    return listOf(
        VideoSurfacePanelRegistration(
            R.id.ui_example,
            surfaceConsumer = { panelEntity, surface ->
              System.out.println("=== SURFACE_CONSUMER_CALLED panelEntity=$panelEntity ===")
              android.util.Log.e(TAG, "=== SURFACE_CONSUMER_CALLED panelEntity=$panelEntity ===")
              Log.i(TAG, "Surface attached for panel entity=$panelEntity")
              
              // Store the panel entity reference
              videoPanelEntity = panelEntity
              
              // Set panel entity on positioning system for async positioning
              panelPositioningSystem?.setPanelEntity(panelEntity)
              
              // Panel starts visible - positioning handled by PanelPositioningSystem
              panelEntity.setComponent(Visible(true))
              panelEntity.setComponent(Grabbable(enabled = true, type = GrabbableType.PIVOT_Y))
              
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
              
              // Update connection panel parent after video panel is ready
              updateConnectionPanelParent()
              
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
                  shape = computePanelShape(),
                  display = PixelDisplayOptions(width = prefs.width, height = prefs.height),
                  rendering = MediaPanelRenderOptions(
                      isDRM = false,
                      stereoMode = StereoMode.None
                  ),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
              )
            },
        ),
        PanelRegistration(R.id.connection_panel) {
          config {
            fractionOfScreen = 0.8f
            height = basePanelHeightMeters
            width = basePanelHeightMeters * 0.8f
            layoutDpi = 160
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.PanelAppThemeTransparent
          }
          composePanel { setContent {
            ConnectionPanelImmersive(
                  pairingHelper = pairingHelper,
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
                    IdentityStore.clearAll(this@ImmersiveActivity)
                    Log.i(TAG, "Cleared client pairing state and pinned certificates")
                  },
                  onConnect = { host, port, appId ->
                    Log.i(TAG, "Connection panel connect clicked host=$host port=$port appId=$appId")
                    connectToHost(host, port, appId)
                  }
              )
            }
          }
        },
    )
  }

  /**
   * Align panel physical shape with the negotiated video pixel aspect ratio.
   * Spatial SDK docs recommend matching layout size to the stream to keep
   * direct-to-surface output pixel-perfect.
   */
  private fun computePanelShape(): QuadShapeOptions {
    val aspect =
        if (prefs.height != 0) {
          prefs.width.toFloat() / prefs.height.toFloat()
        } else {
          16f / 9f
        }
    val panelHeightMeters = basePanelHeightMeters
    val panelWidthMeters = aspect * basePanelHeightMeters
    return QuadShapeOptions(width = panelWidthMeters, height = panelHeightMeters)
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
    
    val aspect =
        if (prefs.height != 0) {
          prefs.width.toFloat() / prefs.height.toFloat()
        } else {
          16f / 9f
        }
    val panelSize = Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
    
    videoPanelEntity = Entity.create(
        listOf(
            Panel(R.id.ui_example),
            Transform(),
            PanelDimensions(panelSize),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(true)
        )
    )
    
    System.out.println("=== VIDEO_PANEL_ENTITY_CREATED ===")
    android.util.Log.e(TAG, "=== VIDEO_PANEL_ENTITY_CREATED ===")
    Log.i(TAG, "Video panel entity created with PanelDimensions - positioning handled by Spatial SDK")
  }

  private fun createConnectionPanelEntity() {
    System.out.println("=== CREATE_CONNECTION_PANEL_ENTITY_CALLED ===")
    android.util.Log.e(TAG, "=== CREATE_CONNECTION_PANEL_ENTITY_CALLED ===")
    Log.i(TAG, "Creating connection panel entity with Panel(R.id.connection_panel)")
    
    val aspect =
        if (prefs.height != 0) {
          prefs.width.toFloat() / prefs.height.toFloat()
        } else {
          16f / 9f
        }
    val panelSize = Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
    
    connectionPanelEntity = Entity.create(
        listOf(
            Panel(R.id.connection_panel),
            Transform(),
            PanelDimensions(panelSize),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(true),
            TransformParent(Entity.nullEntity()) // Will be updated when videoPanelEntity is ready
        )
    )
    
    System.out.println("=== CONNECTION_PANEL_ENTITY_CREATED ===")
    android.util.Log.e(TAG, "=== CONNECTION_PANEL_ENTITY_CREATED ===")
    Log.i(TAG, "Connection panel entity created - will be parented to video panel when ready")
    
    // If video panel already exists, parent immediately
    updateConnectionPanelParent()
  }

  private fun updateConnectionPanelParent() {
    val videoEntity = videoPanelEntity
    val connectionEntity = connectionPanelEntity
    
    if (videoEntity != null && connectionEntity != null) {
      val videoDimensions = videoEntity.tryGetComponent<PanelDimensions>()
      val connectionDimensions = connectionEntity.tryGetComponent<PanelDimensions>()
      val spacing = 0.1f // 10cm spacing between panels
      val offsetX = if (videoDimensions != null && connectionDimensions != null) {
        videoDimensions.dimensions.x / 2f + spacing + connectionDimensions.dimensions.x / 2f
      } else {
        1.0f + spacing // Default offset if dimensions not available
      }
      
      val offset = Vector3(offsetX, 0f, 0f)
      connectionEntity.setComponent(TransformParent(videoEntity))
      connectionEntity.setComponent(Transform(Pose(offset)))
      
      System.out.println("=== CONNECTION_PANEL_PARENTED ===")
      android.util.Log.e(TAG, "=== CONNECTION_PANEL_PARENTED ===")
      Log.i(TAG, "Connection panel parented to video panel with offset x=$offsetX")
    }
  }

}

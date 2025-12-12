package com.example.moonlight_spatialsdk

import android.app.PendingIntent
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.moonlight_spatialsdk.BuildConfig
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecHelper
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
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.SpatialActivityManager
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
import com.example.moonlight_spatialsdk.Scalable
import com.example.moonlight_spatialsdk.ScaledParent
import com.example.moonlight_spatialsdk.systems.pointerInfo.PointerInfoSystem
import com.example.moonlight_spatialsdk.systems.scalable.TouchScalableSystem
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
  private var panelManager: PanelManager? = null
  private var panelPositioningSystem: PanelPositioningSystem? = null
  private lateinit var pairingHelper: MoonlightPairingHelper
  
  // Track connection state before pause for resume recovery
  private var wasConnectedBeforePause: Boolean = false
  private var connectionParamsBeforePause: Triple<String, Int, Int>? = null
  
  // Diagnostic flag: when true, bypass ControllerHandler forwarding to allow UI navigation testing
  // Set to true to test if controller input reaches the app (UI navigation)
  // Set to false to forward input to ControllerHandler for Sunshine passthrough
  private val allowControllerUIInput = false

  /**
   * Get OpenGL renderer string for Quest 3/3S hardware.
   * Quest 3 uses Adreno 740 GPU. Since we're targeting specific hardware,
   * we can use a known value. This enables MediaCodecHelper to properly
   * detect GPU capabilities and configure decoder optimizations.
   */
  private fun getQuestGlRenderer(): String {
    // Quest 3/3S uses Snapdragon XR2 Gen 2 with Adreno 740 GPU
    // MediaCodecHelper uses this to detect GPU capabilities (e.g., isLowEndSnapdragon, isAdreno620)
    // For Quest 3, we know it's Adreno 740, so we can use a known value
    // Format: "Adreno (TM) 740" - MediaCodecHelper parses the number to detect capabilities
    return "Adreno (TM) 740"
  }

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

    // Initialize MediaCodecHelper BEFORE creating decoder renderer
    // This is required for explicit decoder selection and capability checking
    // Quest 3/3S uses Adreno 740 GPU - we can use a known value since we're targeting specific hardware
    val glRenderer = getQuestGlRenderer()
    Log.i(TAG, "Initializing MediaCodecHelper with GL renderer: $glRenderer")
    MediaCodecHelper.initialize(this, glRenderer)
    Log.i(TAG, "MediaCodecHelper initialized successfully")

    // Create decoder renderer in onCreate() like moonlight-android does
    // This ensures decoder is initialized before any connection attempts
    moonlightPanelRenderer = MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> },
    )
    audioRenderer = AndroidAudioRenderer(this, prefs.enableAudioFx)
    pairingHelper = MoonlightPairingHelper(this)
    
    // Register scaling components
    componentManager.registerComponent<Scalable>(Scalable.Companion)
    componentManager.registerComponent<ScaledParent>(ScaledParent.Companion)
    
    // Register pointer info system (required for hover detection)
    val pointerInfoSystem = PointerInfoSystem()
    systemManager.registerSystem(pointerInfoSystem)
    
    // Register touch scalable system
    systemManager.registerSystem(TouchScalableSystem(minScale = 0.5f, maxScale = 10.0f))
    
    connectionManager = MoonlightConnectionManager(
        context = this,
        activity = this,
        decoderRenderer = moonlightPanelRenderer.getDecoder(),
        audioRenderer = audioRenderer,
        onStatusUpdate = { status, connected ->
          _connectionStatus.value = status
          _isConnected.value = connected
          // When connection is established (stream ready), show video panel
          if (connected) {
            videoPanelEntity?.setComponent(Visible(true))
            Log.i(TAG, "Video stream ready (connected=$connected, status=$status), showing video panel")
            
            // Initialize ControllerHandler now that video panel is visible and stream is ready
            val handlerInitialized = connectionManager.initializeControllerHandler()
            if (handlerInitialized) {
              Log.i(TAG, "ControllerHandler initialized successfully for input passthrough")
            } else {
              Log.w(TAG, "ControllerHandler initialization failed - input passthrough may not work")
            }
          }
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

    // Create PanelManager first - this will be the root for all panels
    panelManager = PanelManager()
    val panelManagerEntity = panelManager!!.create()
    panelPositioningSystem?.setPanelEntity(panelManagerEntity)
    Log.i(TAG, "PanelManager created and set on positioning system")

    createVideoPanelEntity()
    createConnectionPanelEntity()
  }


  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    val shared = getSharedPreferences("connection_prefs", MODE_PRIVATE)
    val savedHost = shared.getString("saved_host", "") ?: ""
    val savedPort = shared.getString("saved_port", "47989") ?: "47989"
    val savedAppId = shared.getString("saved_appId", "0") ?: "0"
    
    // Video panel is registered dynamically in createVideoPanelEntity() using executeOnVrActivity
    // to ensure panelManager is initialized before registration (lifecycle alignment)
    return listOf(
        PanelRegistration(R.id.connection_panel) {
          config {
            fractionOfScreen = 0.4f
            height = basePanelHeightMeters * 0.75f
            width = basePanelHeightMeters * 0.6f
            layoutDpi = 240
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
    // Unregister video panel from scaling system before shutdown
    videoPanelEntity?.let { entity ->
      val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
      if (touchScalableSystem != null) {
        touchScalableSystem.unregisterEntity(entity)
        Log.i(TAG, "Video panel unregistered from TouchScalableSystem on shutdown")
      }
    }
    
    super.onSpatialShutdown()
    disconnect()
  }

  /**
   * Handle HMD unmount (device removed from head).
   * Store connection state for potential resume recovery.
   */
  override fun onHMDUnmounted() {
    super.onHMDUnmounted()
    Log.i(TAG, "onHMDUnmounted: Storing connection state for resume recovery")
    
    // Store connection state before pause
    wasConnectedBeforePause = connectionManager.isConnected()
    if (wasConnectedBeforePause) {
      // Get connection params from connection manager (it stores them when stream starts)
      connectionParamsBeforePause = connectionManager.getCurrentConnectionParams()
      if (connectionParamsBeforePause == null) {
        // Fallback to pendingConnectionParams if connection manager doesn't have them
        connectionParamsBeforePause = pendingConnectionParams
        if (connectionParamsBeforePause == null) {
          Log.w(TAG, "onHMDUnmounted: Connected but no connection params available")
        }
      }
      if (connectionParamsBeforePause != null) {
        Log.i(TAG, "onHMDUnmounted: Was connected, stored params for resume recovery: ${connectionParamsBeforePause}")
      }
    }
  }

  /**
   * Handle HMD mount (device placed on head).
   * Check if video stream needs to be re-established after sleep/wake cycle.
   */
  override fun onHMDMounted() {
    super.onHMDMounted()
    Log.i(TAG, "onHMDMounted: Checking if video stream needs recovery")
    
    // If we were connected before pause, check if we need to re-establish video stream
    if (wasConnectedBeforePause) {
      Log.i(TAG, "onHMDMounted: Was connected before pause, checking video stream health")
      
      // Check if connection is still alive but video stream may have died
      val isCurrentlyConnected = connectionManager.isConnected()
      if (!isCurrentlyConnected && connectionParamsBeforePause != null) {
        val (host, port, appId) = connectionParamsBeforePause!!
        Log.i(TAG, "onHMDMounted: Connection lost during sleep, re-establishing video stream host=$host port=$port appId=$appId")
        
        // Re-establish connection with stored params
        pendingConnectionParams = connectionParamsBeforePause
        isPaired = true // Assume still paired
        startStreamIfReady()
      } else if (isCurrentlyConnected) {
        // Connection still exists, but video stream may have died
        // Try to restart video decoder if needed
        Log.i(TAG, "onHMDMounted: Connection still active, checking video decoder state")
        connectionManager.checkAndRestartVideoStreamIfNeeded()
      }
      
      // Reset state
      wasConnectedBeforePause = false
      connectionParamsBeforePause = null
    }
  }

  /**
   * Handle VR pause (system pause event).
   * Store connection state for potential resume recovery.
   */
  override fun onVRPause() {
    super.onVRPause()
    Log.i(TAG, "onVRPause: Storing connection state for resume recovery")
    
    // Store connection state before pause
    wasConnectedBeforePause = connectionManager.isConnected()
    if (wasConnectedBeforePause) {
      // Get connection params from connection manager (it stores them when stream starts)
      connectionParamsBeforePause = connectionManager.getCurrentConnectionParams()
      if (connectionParamsBeforePause == null) {
        // Fallback to pendingConnectionParams if connection manager doesn't have them
        connectionParamsBeforePause = pendingConnectionParams
        if (connectionParamsBeforePause == null) {
          Log.w(TAG, "onVRPause: Connected but no connection params available")
        }
      }
      if (connectionParamsBeforePause != null) {
        Log.i(TAG, "onVRPause: Was connected, stored params for resume recovery: ${connectionParamsBeforePause}")
      }
    }
  }

  /**
   * Handle VR ready (system resume event).
   * Check if video stream needs to be re-established after sleep/wake cycle.
   */
  override fun onVRReady() {
    super.onVRReady()
    Log.i(TAG, "onVRReady: Checking if video stream needs recovery")
    
    // If we were connected before pause, check if we need to re-establish video stream
    if (wasConnectedBeforePause) {
      Log.i(TAG, "onVRReady: Was connected before pause, checking video stream health")
      
      // Check if connection is still alive but video stream may have died
      val isCurrentlyConnected = connectionManager.isConnected()
      if (!isCurrentlyConnected && connectionParamsBeforePause != null) {
        val (host, port, appId) = connectionParamsBeforePause!!
        Log.i(TAG, "onVRReady: Connection lost during sleep, re-establishing video stream host=$host port=$port appId=$appId")
        
        // Re-establish connection with stored params
        pendingConnectionParams = connectionParamsBeforePause
        isPaired = true // Assume still paired
        startStreamIfReady()
      } else if (isCurrentlyConnected) {
        // Connection still exists, but video stream may have died
        // Try to restart video decoder if needed
        Log.i(TAG, "onVRReady: Connection still active, checking video decoder state")
        connectionManager.checkAndRestartVideoStreamIfNeeded()
      }
      
      // Reset state
      wasConnectedBeforePause = false
      connectionParamsBeforePause = null
    }
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

    // Destroy connection panel entity when connect is pressed to prevent it from receiving input
    // Following PremiumMediaSample pattern: destroy entity, not just hide it
    connectionPanelEntity?.destroy()
    connectionPanelEntity = null
    Log.i(TAG, "Connection panel entity destroyed - starting connection")

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
    
    // Unregister video panel from scaling system
    videoPanelEntity?.let { entity ->
      val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
      if (touchScalableSystem != null) {
        touchScalableSystem.unregisterEntity(entity)
        Log.i(TAG, "Video panel unregistered from TouchScalableSystem")
      }
    }
    
    connectionManager.stopStream()
    _connectionStatus.value = "Disconnected"
    _isConnected.value = false
    pendingConnectionParams = null
    isPaired = false
    isSurfaceReady = false
  }

  private fun createVideoPanelEntity() {
    Log.i(TAG, "Creating video panel entity with Panel(R.id.ui_example)")
    
    // Register panel dynamically using executeOnVrActivity to ensure activity is fully ready
    // This matches PremiumMediaSample pattern and ensures panelManager is initialized
    SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
      immersiveActivity.registerPanel(
          VideoSurfacePanelRegistration(
              R.id.ui_example,
              surfaceConsumer = { panelEntity, surface ->
                Log.i(TAG, "Surface attached for panel entity=$panelEntity")
                
                SurfaceUtil.paintBlack(surface)
                
                // Configure decoder with preferences when panel is created
                moonlightPanelRenderer.attachSurface(surface)
                moonlightPanelRenderer.preConfigureDecoder()
                
                isSurfaceReady = true
                
                // Now that panel surface is ready and decoder is configured, initiate connection if we have pending params
                val params = pendingConnectionParams
                if (params != null) {
                  val (host, port, appId) = params
                  Log.i(TAG, "Panel surface ready, decoder configured, initiating connection host=$host port=$port appId=$appId")
                  connectToHost(host, port, appId)
                } else {
                  Log.d(TAG, "Panel surface ready but no pending connection params")
                }
              },
              settingsCreator = {
                MediaPanelSettings(
                    shape = computePanelShape(),
                    display = PixelDisplayOptions(width = prefs.width, height = prefs.height),
                    rendering = MediaPanelRenderOptions(
                        isDRM = false,
                        stereoMode = StereoMode.None,
                        zIndex = 0 // Rectilinear panels use zIndex 0 (Equirect180 uses -1)
                    ),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                )
              },
          )
      )
    }
    
    // Create entity after panel registration (panel must be registered before entity creation)
    val aspect =
        if (prefs.height != 0) {
          prefs.width.toFloat() / prefs.height.toFloat()
        } else {
          16f / 9f
        }
    val panelSize = Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
    
    val managerEntity = panelManager?.panelManagerEntity
    val parentComponent = if (managerEntity != null) {
      TransformParent(managerEntity)
    } else {
      TransformParent(Entity.nullEntity())
    }
    
    videoPanelEntity = Entity.create(
        listOf(
            Panel(R.id.ui_example),
            Transform(Pose(Vector3(0f, 0f, 0f))),
            PanelDimensions(panelSize),
            Scale(Vector3(1f)), // Initial scale of 1.0 - can be adjusted after connection
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(false), // Hidden initially, shown when stream is ready
            Scalable(), // Enable corner scaling
            ScaledParent(), // Mark as scalable parent
            parentComponent
        )
    )
    
    // Register video panel with scaling system
    val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
    if (touchScalableSystem != null) {
      touchScalableSystem.registerEntity(videoPanelEntity!!)
      Log.i(TAG, "Video panel entity created and registered with TouchScalableSystem")
    } else {
      Log.w(TAG, "TouchScalableSystem not found - scaling will not work")
    }
    
    Log.i(TAG, "Video panel entity created - parented to PanelManager, hidden initially")
  }

  private fun createConnectionPanelEntity() {
    Log.i(TAG, "Creating connection panel entity with Panel(R.id.connection_panel)")
    
    // Connection panel size - match the registration config to UISetSample "UI Components" panel size
    // Registration: height = 0.75f * basePanelHeightMeters, width = 0.6f * basePanelHeightMeters
    val connectionPanelHeight = basePanelHeightMeters * 0.75f  // 0.525m
    val connectionPanelWidth = basePanelHeightMeters * 0.6f      // 0.42m
    val panelSize = Vector2(connectionPanelWidth, connectionPanelHeight)
    
    val managerEntity = panelManager?.panelManagerEntity
    val parentComponent = if (managerEntity != null) {
      TransformParent(managerEntity)
    } else {
      TransformParent(Entity.nullEntity()) // Will be updated when PanelManager is ready
    }
    
    connectionPanelEntity = Entity.create(
        listOf(
            Panel(R.id.connection_panel),
            Transform(Pose(Vector3(0f, 0f, 0f))),
            PanelDimensions(panelSize),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(true), // Visible initially, hidden when connect is pressed
            parentComponent
        )
    )
    
    Log.i(TAG, "Connection panel entity created - size: ${panelSize.x}m x ${panelSize.y}m, parented to PanelManager")
  }

  /**
   * Updates the scale of the video panel after connection is established.
   * Scale is applied uniformly to all dimensions (x, y, z).
   * 
   * @param scaleFactor Scale multiplier (1.0 = original size, 2.0 = double size, 0.5 = half size)
   */
  fun updateVideoPanelScale(scaleFactor: Float) {
    val entity = videoPanelEntity ?: return
    val currentScale = entity.tryGetComponent<Scale>()
    if (currentScale != null) {
      entity.setComponent(Scale(Vector3(scaleFactor)))
      Log.i(TAG, "Video panel scale updated to $scaleFactor")
    } else {
      entity.setComponent(Scale(Vector3(scaleFactor)))
      Log.i(TAG, "Video panel scale component added with value $scaleFactor")
    }
  }

  /**
   * PanelLayerAlpha is a custom component used in PremiumMediaSample for fade in/out effects.
   * It controls panel opacity separately from the Visible component:
   * - Visible: Controls whether entity is rendered at all (on/off)
   * - PanelLayerAlpha: Controls opacity for smooth fade transitions (0.0 = transparent, 1.0 = opaque)
   * 
   * Implementation requires:
   * 1. Component definition XML (components/PanelLayerAlpha.xml)
   * 2. Component registration in registerFeatures()
   * 3. PanelLayerAlphaSystem to apply alpha to panel layer colorScaleBias
   * 4. Optional: TweenEngineSystem integration for animated fades
   * 
   * For now, we use Visible component for instant show/hide. Fade effects can be added later if needed.
   */

  /**
   * Forward key events to ControllerHandler for input passthrough.
   * This allows Bluetooth controllers (Xbox/DualShock 4) to send input to the server.
   * Only forwards events when connected, and consumes them to prevent UI handling.
   */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // Diagnostic: allow controller input to reach UI for testing
    if (allowControllerUIInput) {
      // Log to verify all events are reaching this method
      Log.d(TAG, "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}, device=${event.device?.name}")
      return super.dispatchKeyEvent(event)
    }
    
    // Log all key events for debugging
    Log.d(TAG, "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}, device=${event.device?.name}, connected=${connectionManager.isConnected()}")
    
    // Only forward input when connected (check directly from connection manager for accuracy)
    if (!connectionManager.isConnected()) {
      Log.d(TAG, "dispatchKeyEvent: Not connected, passing to super")
      return super.dispatchKeyEvent(event)
    }
    
    val controllerHandler = connectionManager.getControllerHandler()
    if (controllerHandler == null) {
      Log.w(TAG, "dispatchKeyEvent: Connected but ControllerHandler is null")
      return super.dispatchKeyEvent(event)
    }
    
    // Skip keyboard events (alphabetic keyboards) to avoid consuming UI input
    val device = event.device
    if (device != null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
      Log.d(TAG, "dispatchKeyEvent: Alphabetic keyboard, passing to super")
      return super.dispatchKeyEvent(event)
    }
    
    // Let ControllerHandler determine if this is a gamepad event
    // It has sophisticated logic to detect gamepads and will return true if handled
    val handled = when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        val result = controllerHandler.handleButtonDown(event)
        Log.d(TAG, "dispatchKeyEvent: handleButtonDown returned $result")
        result
      }
      KeyEvent.ACTION_UP -> {
        val result = controllerHandler.handleButtonUp(event)
        Log.d(TAG, "dispatchKeyEvent: handleButtonUp returned $result")
        result
      }
      else -> false
    }
    if (handled) {
      // Consume the event to prevent UI from handling it
      Log.d(TAG, "dispatchKeyEvent: ControllerHandler handled event, consuming")
      return true
    }
    
    Log.d(TAG, "dispatchKeyEvent: ControllerHandler did not handle, passing to super")
    return super.dispatchKeyEvent(event)
  }

  /**
   * Forward motion events (joystick/analog stick movements) to ControllerHandler for input passthrough.
   * This allows Bluetooth controllers to send analog stick and trigger input to the server.
   * Only forwards gamepad events when connected, and consumes them to prevent UI handling.
   */
  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    // Diagnostic: allow controller input to reach UI for testing
    if (allowControllerUIInput) {
      // Log to verify all events are reaching this method
      Log.d(TAG, "dispatchGenericMotionEvent: action=${event.action}, source=${event.source}, device=${event.device?.name}")
      return super.dispatchGenericMotionEvent(event)
    }
    
    // Log motion events for debugging
    Log.d(TAG, "dispatchGenericMotionEvent: action=${event.action}, source=${event.source}, device=${event.device?.name}, connected=${connectionManager.isConnected()}")
    
    // Only forward input when connected (check directly from connection manager for accuracy)
    if (!connectionManager.isConnected()) {
      Log.d(TAG, "dispatchGenericMotionEvent: Not connected, passing to super")
      return super.dispatchGenericMotionEvent(event)
    }
    
    val controllerHandler = connectionManager.getControllerHandler()
    if (controllerHandler == null) {
      Log.w(TAG, "dispatchGenericMotionEvent: Connected but ControllerHandler is null")
      return super.dispatchGenericMotionEvent(event)
    }
    
    // Check if this is a gamepad/joystick event
    val isGamepad = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    if (isGamepad) {
      val handled = controllerHandler.handleMotionEvent(event)
      Log.d(TAG, "dispatchGenericMotionEvent: handleMotionEvent returned $handled")
      if (handled) {
        // Consume the event to prevent UI from handling it
        Log.d(TAG, "dispatchGenericMotionEvent: ControllerHandler handled event, consuming")
        return true
      }
    } else {
      Log.d(TAG, "dispatchGenericMotionEvent: Not a gamepad event (source=${event.source}), passing to super")
    }
    
    Log.d(TAG, "dispatchGenericMotionEvent: ControllerHandler did not handle, passing to super")
    return super.dispatchGenericMotionEvent(event)
  }

}

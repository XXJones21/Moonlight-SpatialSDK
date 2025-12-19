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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
import com.meta.spatial.core.Query
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
// import com.meta.spatial.isdk.IsdkFeature (not required anymore)
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
import com.example.moonlight_spatialsdk.ScaledChild
import com.example.moonlight_spatialsdk.ScaledParent
import com.example.moonlight_spatialsdk.systems.pointerInfo.PointerInfoSystem
import com.example.moonlight_spatialsdk.systems.scalable.TouchScalableSystem
import com.example.moonlight_spatialsdk.systems.scaleChildren.ScaleChildrenSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
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
  private var disconnectDialogPanelEntity: Entity? = null
  private var buttonShelfEntity: com.example.moonlight_spatialsdk.entities.ButtonShelfEntity? = null
  private var buttonShelfVisibilitySystem: com.example.moonlight_spatialsdk.systems.buttonShelfVisibility.ButtonShelfVisibilitySystem? = null
  private var panelManager: PanelManager? = null
  private var panelPositioningSystem: PanelPositioningSystem? = null
  private lateinit var pairingHelper: MoonlightPairingHelper
  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  
  // Track connection state before pause for resume recovery
  private var wasConnectedBeforePause: Boolean = false
  private var connectionParamsBeforePause: Triple<String, Int, Int>? = null
  
  // Diagnostic flag: when true, bypass ControllerHandler forwarding to allow UI navigation testing
  // Set to true to test if controller input reaches the app (UI navigation)
  // Set to false to forward input to ControllerHandler for Sunshine passthrough
  private val allowControllerUIInput = false
  
  // Gate flag for input forwarding: only forward inputs when connection is established and ControllerHandler is ready
  // Prevents inputs from being consumed when video panel is registered but no connection exists
  private var shouldForwardInputs: Boolean = false
  
  // Dialog state for disconnect confirmation
  private val _showDisconnectDialog = MutableStateFlow(false)
  val showDisconnectDialog: StateFlow<Boolean> = _showDisconnectDialog.asStateFlow()

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
            // IsdkFeature(this, spatial, systemManager), (not required anymore)
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
    componentManager.registerComponent<ScaledChild>(ScaledChild.Companion)
    
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
              // Only enable input forwarding after ControllerHandler is ready
              shouldForwardInputs = true
              Log.i(TAG, "Input forwarding enabled - connection established and ControllerHandler ready")
            } else {
              Log.w(TAG, "ControllerHandler initialization failed - input passthrough may not work")
              shouldForwardInputs = false
            }
          } else {
            shouldForwardInputs = false
            Log.i(TAG, "Input forwarding disabled - connection lost")
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

  /**
   * Handle new intents when activity is relaunched (singleTask mode).
   * This is called when PancakeActivity launches ImmersiveActivity with new connection params
   * after a previous disconnect.
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent) // Update the stored intent
    
    val host = intent.getStringExtra("host")
    val port = intent.getIntExtra("port", 47989)
    val appId = intent.getIntExtra("appId", 0)
    Log.i(TAG, "onNewIntent: Received new connection params host=$host port=$port appId=$appId")
    
    if (!host.isNullOrBlank()) {
      Log.i(TAG, "onNewIntent: Processing new connection request")
      pendingConnectionParams = Triple(host, port, appId)
      isPaired = true // Assume paired since PancakeActivity verified it
      
      // Always use connectToHost which will create video panel if needed
      // The video panel was destroyed on disconnect, so we need a fresh one
      Log.i(TAG, "onNewIntent: Calling connectToHost for reconnection")
      connectToHost(host, port, appId)
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

    // Register ScaleChildrenSystem for handling child entities that scale with parent
    systemManager.registerLateSystem(ScaleChildrenSystem())
    Log.i(TAG, "ScaleChildrenSystem registered")

    // Create PanelManager first - this will be the root for all panels
    panelManager = PanelManager()
    val panelManagerEntity = panelManager!!.create()
    panelPositioningSystem?.setPanelEntity(panelManagerEntity)
    Log.i(TAG, "PanelManager created and set on positioning system")

    createVideoPanelEntity()
    createConnectionPanelEntity()
    createButtonShelfEntity()
    // Don't create disconnect dialog entity upfront - create/destroy on menu button press
    
    // Observe disconnect dialog state (for Compose UI state, not entity visibility)
    // Entity will be created/destroyed directly by menu button handler
    coroutineScope.launch {
      _showDisconnectDialog.collect { show ->
        Log.d(TAG, "Disconnect dialog state changed: $show (entity will be created/destroyed on toggle)")
      }
    }
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
        PanelRegistration(R.id.disconnect_dialog_panel) {
          config {
            fractionOfScreen = 0.4f
            height = basePanelHeightMeters * 0.4f
            width = basePanelHeightMeters * 0.5f
            layoutDpi = 240
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.PanelAppThemeTransparent
          }
          composePanel { setContent {
          DisconnectDialog(
            showDialog = showDisconnectDialog,
            onResetPanelSize = {
              _showDisconnectDialog.value = false
              updateVideoPanelScale(1.0f)
              Log.i(TAG, "Video panel scale reset to default (1.0)")
              // Destroy entity to close dialog
              destroyDisconnectDialogPanelEntity()
            },
            onEndStream = {
              _showDisconnectDialog.value = false
              // Destroy entity before disconnecting
              destroyDisconnectDialogPanelEntity()
              disconnect()
            },
            onCancel = {
              _showDisconnectDialog.value = false
              Log.i(TAG, "User cancelled disconnect dialog")
              // Destroy entity to close dialog
              destroyDisconnectDialogPanelEntity()
            }
          )
            }
          }
        },
        PanelRegistration(R.id.button_shelf) {
          config {
            fractionOfScreen = 0.3f
            height = 0.12f
            width = 0.5f
            layoutDpi = 240
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.PanelAppThemeTransparent
          }
          composePanel { setContent {
            com.example.moonlight_spatialsdk.panels.buttonShelf.ButtonShelfCompose(
                onSettingsClick = {
                  Log.i(TAG, "ButtonShelf Settings clicked - opening 2D panel overlay for adjustments")
                  startPanelActivityInOverlay()
                },
                onResetScaleClick = {
                  Log.i(TAG, "ButtonShelf Reset Scale clicked - resetting video panel scale to 1.0")
                  updateVideoPanelScale(1.0f)
                },
                onDisconnectClick = {
                  Log.i(TAG, "ButtonShelf Disconnect clicked - ending stream and returning to 2D panel")
                  disconnect()
                  launchPanelModeInHome()
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
   * Opens PancakeActivity as an overlay panel within the immersive scene.
   * This allows settings adjustments with working keyboard while staying in VR.
   * Don't call finishAndRemoveTask(), as it will close the immersive activity.
   */
  private fun startPanelActivityInOverlay() {
    val panelIntent = Intent(this, PancakeActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(panelIntent)
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

    // Hide and destroy connection panel entity when connect is pressed to prevent it from receiving input
    // Following PremiumMediaSample pattern: destroy entity, not just hide it
    connectionPanelEntity?.let { entity ->
      entity.setComponent(Visible(false))
      entity.destroy()
      Log.i(TAG, "Connection panel entity hidden and destroyed - starting connection")
    }
    connectionPanelEntity = null
    

    // Recreate video panel entity if it doesn't exist (for reconnection after disconnect)
    if (videoPanelEntity == null) {
      Log.i(TAG, "Video panel entity doesn't exist, recreating for reconnection")
      createVideoPanelEntity()
    } else {
      // Video panel exists but may be hidden from previous disconnect
      // Surface should still be attached if entity exists (surfaceConsumer was called during initial registration)
      // Restore isSurfaceReady state to allow stream to start
      if (!isSurfaceReady) {
        Log.i(TAG, "Video panel exists but isSurfaceReady is false - restoring surface ready state for reconnection")
        isSurfaceReady = true
      }
      // Reconfigure decoder for new connection
      moonlightPanelRenderer.preConfigureDecoder()
      Log.i(TAG, "Video panel exists, decoder reconfigured for reconnection")
      
      // Re-register video panel with scaling system (was unregistered on disconnect)
      val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
      if (touchScalableSystem != null) {
        touchScalableSystem.registerEntity(videoPanelEntity!!)
        Log.i(TAG, "Video panel re-registered with TouchScalableSystem for reconnection")
      } else {
        Log.w(TAG, "TouchScalableSystem not found - scaling will not work")
      }
      
      // Panel will be made visible when stream is ready (in onStatusUpdate callback)
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
    
    // Disable input forwarding immediately on disconnect
    shouldForwardInputs = false
    Log.i(TAG, "Input forwarding disabled - disconnect initiated")
    
    // Unregister video panel from scaling system
    videoPanelEntity?.let { entity ->
      val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
      if (touchScalableSystem != null) {
        touchScalableSystem.unregisterEntity(entity)
        Log.i(TAG, "Video panel unregistered from TouchScalableSystem")
      }
    }
    
    // Destroy video panel entity completely on disconnect
    // The surface becomes invalid after stream stops, so we need a fresh panel for reconnection
    videoPanelEntity?.let { entity ->
      entity.setComponent(Visible(false))
      entity.destroy()
      Log.i(TAG, "Video panel entity destroyed for clean reconnection")
    }
    videoPanelEntity = null
    
    connectionManager.stopStream()
    _connectionStatus.value = "Disconnected"
    _isConnected.value = false
    pendingConnectionParams = null
    isPaired = false
    isSurfaceReady = false
    
    // Recreate connection panel
    createConnectionPanelEntity()
    Log.i(TAG, "Connection panel recreated")
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
    
    // Attach ButtonShelf to video panel if it was created before video panel
    buttonShelfEntity?.let { shelf ->
      shelf.attachToEntity(videoPanelEntity!!)
      Log.i(TAG, "ButtonShelf attached to video panel")
      
      // Force update children to ensure ButtonShelf is positioned correctly
      val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
      if (scaleChildrenSystem != null) {
        scaleChildrenSystem.forceUpdateChildren(videoPanelEntity!!)
        Log.i(TAG, "ScaleChildrenSystem force update called for ButtonShelf")
      }
      
      // Create and register visibility system if not already done
      if (buttonShelfVisibilitySystem == null) {
        buttonShelfVisibilitySystem = com.example.moonlight_spatialsdk.systems.buttonShelfVisibility.ButtonShelfVisibilitySystem(
            buttonShelf = shelf,
            videoPanelEntity = videoPanelEntity!!
        )
        systemManager.registerSystem(buttonShelfVisibilitySystem!!)
        buttonShelfVisibilitySystem?.startTracking()
        Log.i(TAG, "ButtonShelfVisibilitySystem registered and started tracking")
      }
    }
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

  private fun createButtonShelfEntity() {
    Log.i(TAG, "Creating ButtonShelf entity")
    
    buttonShelfEntity = com.example.moonlight_spatialsdk.entities.ButtonShelfEntity()
    
    // Attach to video panel when it exists
    videoPanelEntity?.let { videoEntity ->
      buttonShelfEntity?.attachToEntity(videoEntity)
      Log.i(TAG, "ButtonShelf attached to video panel")
      
      // Force update children to ensure ButtonShelf is positioned correctly
      val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
      if (scaleChildrenSystem != null) {
        scaleChildrenSystem.forceUpdateChildren(videoEntity)
        Log.i(TAG, "ScaleChildrenSystem force update called for ButtonShelf")
      }
      
      // Create and register visibility system
      buttonShelfVisibilitySystem = com.example.moonlight_spatialsdk.systems.buttonShelfVisibility.ButtonShelfVisibilitySystem(
          buttonShelf = buttonShelfEntity!!,
          videoPanelEntity = videoEntity
      )
      systemManager.registerSystem(buttonShelfVisibilitySystem!!)
      buttonShelfVisibilitySystem?.startTracking()
      Log.i(TAG, "ButtonShelfVisibilitySystem registered and started tracking")
    } ?: run {
      Log.w(TAG, "Video panel entity not yet created, ButtonShelf will be attached later")
    }
  }

  /**
   * Toggle the OptionsPanel (connection panel) visibility when Settings button is clicked.
   */
  fun showOptionsPanel() {
    // Check if connection panel entity exists (either in our reference or in the scene)
    val existingEntity = connectionPanelEntity ?: run {
      // Query for existing connection panel entity in case our reference is null but entity still exists
      val query = Query.where { has(Panel.id) }
      query.eval().firstOrNull { entity ->
        val panel = entity.tryGetComponent<Panel>()
        panel != null && panel.panelRegistrationId == R.id.connection_panel
      }
    }
    
    if (existingEntity == null) {
      Log.i(TAG, "Creating connection panel entity to show OptionsPanel")
      createConnectionPanelEntity()
    } else {
      // Update our reference if we found an existing entity
      if (connectionPanelEntity == null) {
        connectionPanelEntity = existingEntity
        Log.i(TAG, "Found existing connection panel entity, updating reference")
      }
      
      val isVisible = existingEntity.getComponent<Visible>().isVisible
      if (isVisible) {
        Log.i(TAG, "Connection panel is visible, hiding it")
        existingEntity.setComponent(Visible(false))
      } else {
        Log.i(TAG, "Connection panel is hidden, making it visible")
        existingEntity.setComponent(Visible(true))
      }
    }
  }

  /**
   * Create disconnect dialog panel entity.
   * Called when menu button is pressed to show the dialog.
   */
  private fun createDisconnectDialogPanelEntity() {
    // Don't create if already exists
    if (disconnectDialogPanelEntity != null) {
      Log.w(TAG, "Disconnect dialog entity already exists, skipping creation")
      return
    }
    
    Log.i(TAG, "Creating disconnect dialog panel entity with Panel(R.id.disconnect_dialog_panel)")
    
    // Disconnect dialog panel size - match the registration config
    // Registration: height = 0.4f * basePanelHeightMeters, width = 0.5f * basePanelHeightMeters
    val dialogPanelHeight = basePanelHeightMeters * 0.4f  // 0.28m
    val dialogPanelWidth = basePanelHeightMeters * 0.5f      // 0.35m
    val panelSize = Vector2(dialogPanelWidth, dialogPanelHeight)
    
    val managerEntity = panelManager?.panelManagerEntity
    if (managerEntity == null) {
      Log.e(TAG, "Cannot create disconnect dialog - PanelManager entity is null")
      return
    }
    
    val parentComponent = TransformParent(managerEntity)
    
    disconnectDialogPanelEntity = Entity.create(
        listOf(
            Panel(R.id.disconnect_dialog_panel),
            Transform(Pose(Vector3(0f, 0f, -0.1f))), // Move 0.1m back to test visibility
            PanelDimensions(panelSize),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(true), // Visible when created
            parentComponent
        )
    )
    
    Log.i(TAG, "Disconnect dialog panel entity created - size: ${panelSize.x}m x ${panelSize.y}m, parented to PanelManager")
    Log.i(TAG, "Disconnect dialog StateFlow value: ${_showDisconnectDialog.value}, entity: $disconnectDialogPanelEntity")
    
    // Verify entity has all required components
    val hasPanel = disconnectDialogPanelEntity!!.hasComponent<Panel>()
    val hasVisible = disconnectDialogPanelEntity!!.hasComponent<Visible>()
    val visibleComponent = disconnectDialogPanelEntity!!.getComponent<Visible>()
    Log.i(TAG, "Entity verification - hasPanel: $hasPanel, hasVisible: $hasVisible, visibleComponent: $visibleComponent")
  }
  
  /**
   * Destroy disconnect dialog panel entity.
   * Called when menu button is pressed again to hide the dialog.
   */
  private fun destroyDisconnectDialogPanelEntity() {
    val entity = disconnectDialogPanelEntity
    if (entity == null) {
      Log.w(TAG, "Disconnect dialog entity is null, cannot destroy")
      return
    }
    
    Log.i(TAG, "Destroying disconnect dialog panel entity")
    entity.destroy()
    disconnectDialogPanelEntity = null
    Log.i(TAG, "Disconnect dialog panel entity destroyed")
  }
  
  /**
   * Toggle disconnect dialog - create entity if it doesn't exist, destroy if it does.
   * Called when menu button is pressed.
   * Following documentation pattern: hide video panel when menu is shown, restore when closed.
   */
  private fun toggleDisconnectDialog() {
    if (disconnectDialogPanelEntity == null) {
      // Set StateFlow to true FIRST so Compose content is ready when entity is created
      _showDisconnectDialog.value = true
      // Hide video panel when menu dialog is shown (following documentation pattern)
      videoPanelEntity?.setComponent(Visible(false))
      Log.i(TAG, "Video panel hidden while menu dialog is shown")
      // Create entity - Compose content should render immediately
      createDisconnectDialogPanelEntity()
      Log.i(TAG, "Disconnect dialog toggled ON - StateFlow set to true, entity created")
    } else {
      // Hide dialog first, then destroy entity
      _showDisconnectDialog.value = false
      destroyDisconnectDialogPanelEntity()
      // Restore video panel visibility if connected (following documentation pattern)
      if (connectionManager.isConnected()) {
        videoPanelEntity?.setComponent(Visible(true))
        Log.i(TAG, "Video panel restored after menu dialog closed")
      }
      Log.i(TAG, "Disconnect dialog toggled OFF - StateFlow set to false, entity destroyed")
    }
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
    // Always log key events to diagnose controller input issues
    Log.i(TAG, "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}, device=${event.device?.name}, source=${event.source}, shouldForward=$shouldForwardInputs")
    
    // Diagnostic: allow controller input to reach UI for testing
    if (allowControllerUIInput) {
      // Log to verify all events are reaching this method
      Log.d(TAG, "dispatchKeyEvent: allowControllerUIInput=true, passing to super")
      return super.dispatchKeyEvent(event)
    }
    
    // Gate input forwarding with explicit flag
    if (!shouldForwardInputs) {
      Log.d(TAG, "dispatchKeyEvent: Input forwarding disabled, passing to super")
      return super.dispatchKeyEvent(event)
    }
    
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
    // Check if this is a gamepad/joystick event for logging (reduce noise from other motion events)
    val isGamepadSource = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    
    // Always log gamepad motion events to diagnose controller input issues
    if (isGamepadSource) {
      Log.i(TAG, "dispatchGenericMotionEvent: action=${event.action}, source=${event.source}, device=${event.device?.name}, shouldForward=$shouldForwardInputs, connected=${connectionManager.isConnected()}")
    }
    
    // Diagnostic: allow controller input to reach UI for testing
    if (allowControllerUIInput) {
      if (isGamepadSource) {
        Log.d(TAG, "dispatchGenericMotionEvent: allowControllerUIInput=true, passing to super")
      }
      return super.dispatchGenericMotionEvent(event)
    }
    
    // Gate input forwarding with explicit flag
    if (!shouldForwardInputs) {
      if (isGamepadSource) {
        Log.d(TAG, "dispatchGenericMotionEvent: Input forwarding disabled, passing to super")
      }
      return super.dispatchGenericMotionEvent(event)
    }
    
    // Only forward input when connected (check directly from connection manager for accuracy)
    if (!connectionManager.isConnected()) {
      if (isGamepadSource) {
        Log.d(TAG, "dispatchGenericMotionEvent: Not connected, passing to super")
      }
      return super.dispatchGenericMotionEvent(event)
    }
    
    val controllerHandler = connectionManager.getControllerHandler()
    if (controllerHandler == null) {
      Log.w(TAG, "dispatchGenericMotionEvent: Connected but ControllerHandler is null")
      return super.dispatchGenericMotionEvent(event)
    }
    
    // Forward gamepad/joystick events to ControllerHandler
    if (isGamepadSource) {
      val handled = controllerHandler.handleMotionEvent(event)
      Log.d(TAG, "dispatchGenericMotionEvent: handleMotionEvent returned $handled")
      if (handled) {
        // Consume the event to prevent UI from handling it
        return true
      }
    }
    
    return super.dispatchGenericMotionEvent(event)
  }

}

package com.example.moonlight_spatialsdk

import android.app.PendingIntent
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.button.DestructiveButton
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.LocalTypography
import androidx.compose.material3.Text
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
import com.meta.spatial.toolkit.ReadableVideoSurfacePanelRegistration
import com.meta.spatial.toolkit.ReadableMediaPanelSettings
import com.meta.spatial.toolkit.ReadableMediaPanelRenderOptions
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.PanelCreator
import java.util.concurrent.CompletableFuture
import com.example.moonlight_spatialsdk.data.ImmersiveSettings
import com.example.moonlight_spatialsdk.systems.heroLighting.HeroLightingSystem
import com.example.moonlight_spatialsdk.systems.heroLighting.WallLightingSystem
import com.example.moonlight_spatialsdk.systems.lighting.LightingPassthroughHandler
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.example.moonlight_spatialsdk.Anchorable
import com.example.moonlight_spatialsdk.AnchorOnLoad
import com.example.moonlight_spatialsdk.Scalable
import com.example.moonlight_spatialsdk.ScaledChild
import com.example.moonlight_spatialsdk.ScaledParent
import com.example.moonlight_spatialsdk.WallSnap
import com.example.moonlight_spatialsdk.systems.pointerInfo.PointerInfoSystem
import com.example.moonlight_spatialsdk.systems.scalable.TouchScalableSystem
import com.example.moonlight_spatialsdk.systems.scaleChildren.ScaleChildrenSystem
import com.example.moonlight_spatialsdk.systems.mruk.RoomMeshManager
import com.example.moonlight_spatialsdk.systems.audio.SpatialAudioManager
import com.example.moonlight_spatialsdk.systems.anchor.AnchorSnappingSystem
import com.example.moonlight_spatialsdk.entities.BiasLightingEntity
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
  
  // MRUK and Spatial Audio features for room meshing and spatialized audio
  private lateinit var mrukFeature: MRUKFeature
  private lateinit var spatialAudioFeature: SpatialAudioFeature
  
  // Immersive mode features state
  private val _isImmersiveModeEnabled = MutableStateFlow(false)
  val isImmersiveModeEnabled: StateFlow<Boolean> = _isImmersiveModeEnabled.asStateFlow()
  
  private val _isSnapEnabled = MutableStateFlow(false)
  val isSnapEnabled: StateFlow<Boolean> = _isSnapEnabled.asStateFlow()
  
  // RoomMeshManager for MRUK room mesh visualization
  private var roomMeshManager: RoomMeshManager? = null
  
  // SpatialAudioManager for spatialized audio from the video panel
  private var spatialAudioManager: SpatialAudioManager? = null
  
  // Hero lighting system for emissive lighting effects
  private var heroLightingSystem: HeroLightingSystem? = null
  
  // Lighting passthrough handler for room dimming
  private var lightingPassthroughHandler: LightingPassthroughHandler? = null
  
  // Wall lighting system for MRUK surface reflections
  private var wallLightingSystem: WallLightingSystem? = null
  
  // Bias lighting entity for edge-based ambient glow effect
  private var biasLightingEntity: BiasLightingEntity? = null
  
  // Stereo video system for stereoscopic 3D depth control
  private var stereoVideoSystem: com.example.moonlight_spatialsdk.systems.stereo.StereoVideoSystem? = null
  
  // Stereo depth slider entity for runtime depth control
  private var stereoDepthSliderEntity: com.example.moonlight_spatialsdk.entities.StereoDepthSliderEntity? = null
  
  // Stereo depth slider visibility system
  private var stereoDepthSliderVisibilitySystem: com.example.moonlight_spatialsdk.systems.stereoDepthSlider.StereoDepthSliderVisibilitySystem? = null
  
  // Cached immersive settings for panel creation decisions
  private var immersiveSettings: ImmersiveSettings = ImmersiveSettings()
  
  // Permission request codes for MRUK scene access
  companion object {
    private const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
    private const val REQUEST_CODE_PERMISSION_USE_SCENE = 100
  }

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
    // Initialize MRUK and Spatial Audio features for room meshing and spatialized audio
    mrukFeature = MRUKFeature(this, systemManager)
    spatialAudioFeature = SpatialAudioFeature()
    
    val features =
        mutableListOf<SpatialFeature>(
            VRFeature(this),
            ComposeFeature(),
            PhysicsFeature(spatial), // Required for MRUK colliders
            mrukFeature,
            spatialAudioFeature,
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
    
    // Register anchor snapping components for MRUK wall/floor/ceiling snapping
    componentManager.registerComponent<Anchorable>(Anchorable.Companion)
    componentManager.registerComponent<AnchorOnLoad>(AnchorOnLoad.Companion)
    componentManager.registerComponent<WallSnap>(WallSnap.Companion)
    
    // Register hero lighting components for emissive lighting effects
    componentManager.registerComponent<HeroLighting>(HeroLighting.Companion)
    componentManager.registerComponent<ReceiveLighting>(ReceiveLighting.Companion)
    
    // Register hero lighting system for lighting emission
    heroLightingSystem = HeroLightingSystem(autoDetectTexture = true, isProcessingShaders = true)
    systemManager.registerSystem(heroLightingSystem!!)
    Log.i(TAG, "HeroLightingSystem registered")
    
    // Register wall lighting system for MRUK surface reflections
    // (Registered at startup like PremiumMediaSample - starts hidden, only visible when reflections enabled)
    wallLightingSystem = WallLightingSystem(_isVisible = false)
    systemManager.registerSystem(wallLightingSystem!!)
    Log.i(TAG, "WallLightingSystem registered")
    
    // Register pointer info system (required for hover detection)
    val pointerInfoSystem = PointerInfoSystem()
    systemManager.registerSystem(pointerInfoSystem)
    
    // Register touch scalable system
    systemManager.registerSystem(TouchScalableSystem(minScale = 0.5f, maxScale = 10.0f))
    
    // Register anchor snapping system (will be enabled when snap-to-wall is toggled)
    systemManager.registerSystem(AnchorSnappingSystem())
    
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
    
    // Initialize LightingPassthroughHandler for room dimming effects
    lightingPassthroughHandler = LightingPassthroughHandler(scene)
    Log.i(TAG, "LightingPassthroughHandler initialized")

    // NOTE: Do NOT use scene.setViewOrigin() with rotation - it affects the entire
    // scene coordinate system including MRUK meshes, causing them to appear inverted.
    // Video panel positioning should use direct entity transforms instead.

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
    // Video panel is registered dynamically in createVideoPanelEntity() using executeOnVrActivity
    // to ensure panelManager is initialized before registration (lifecycle alignment)
    return listOf(
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
            width = 0.9f
            layoutDpi = 240
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.PanelAppThemeTransparent
          }
          composePanel { setContent {
            // Collect state flows for button selection states
            val immersiveModeEnabled = isImmersiveModeEnabled.collectAsState()
            val snapEnabled = isSnapEnabled.collectAsState()
            
            com.example.moonlight_spatialsdk.panels.buttonShelf.ButtonShelfCompose(
                isImmersiveModeEnabled = immersiveModeEnabled.value,
                isSnapEnabled = snapEnabled.value,
                onSettingsClick = {
                  Log.i(TAG, "ButtonShelf Settings clicked - opening 2D panel overlay for adjustments")
                  startPanelActivityInOverlay()
                },
                onResetScaleClick = {
                  Log.i(TAG, "ButtonShelf Reset Scale clicked - resetting video panel scale to 1.0")
                  updateVideoPanelScale(1.0f)
                },
                onImmersiveModeClick = {
                  Log.i(TAG, "ButtonShelf Immersive Mode clicked - toggling immersive features")
                  toggleImmersiveMode()
                },
                onSnapToWallClick = {
                  Log.i(TAG, "ButtonShelf Snap clicked - toggling snap-to-wall behavior")
                  toggleSnapToWall()
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
        PanelRegistration(R.id.stereo_depth_slider) {
          config {
            fractionOfScreen = 0.3f
            height = 0.12f
            width = 0.9f
            layoutDpi = 240
            layerConfig = LayerConfig()
            enableTransparent = true
            includeGlass = false
            themeResourceId = R.style.PanelAppThemeTransparent
          }
          composePanel { setContent {
            // Use a local state for the slider value, initialized from stereo system if available
            val initialValue = stereoVideoSystem?.depthFactor ?: 0.5f
            var currentDepthFactor by remember { mutableStateOf(initialValue) }
            
            // Update local state when stereo system value changes (if system exists)
            stereoVideoSystem?.let { system ->
              // Sync with system's current value
              val systemValue = system.depthFactor
              if (systemValue != currentDepthFactor) {
                currentDepthFactor = systemValue
              }
            }
            
            com.example.moonlight_spatialsdk.panels.stereoDepthSlider.StereoDepthSliderCompose(
              depthFactor = currentDepthFactor,
              onDepthChange = { newValue ->
                stereoVideoSystem?.updateDepthFactor(newValue)
                currentDepthFactor = newValue
                newValue
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

  /**
   * Adds all required components to the SDK-provided video panel entity.
   * This function centralizes component addition to avoid duplication across panel registration modes.
   *
   * @param entity The SDK-provided entity from panel registration callback
   * @param panelSize The physical panel dimensions in meters (Vector2)
   * @param useLightingEmission Whether to add HeroLighting component
   * @return The entity for chaining
   */
  private fun addVideoPanelComponents(entity: Entity, panelSize: Vector2, useLightingEmission: Boolean): Entity {
    val managerEntity = panelManager?.panelManagerEntity
    val parentComponent = if (managerEntity != null) {
      TransformParent(managerEntity)
    } else {
      TransformParent(Entity.nullEntity())
    }

    // Add all required components
    entity.setComponent(Transform(Pose(Vector3(0f, 0f, 0f))))
    
    // Set PanelDimensions - this controls the physical panel size and panel outline
    // For stereoscopic mode, this should be ultrawide (5120x1440p aspect ratio)
    // For standard/lighting modes, this should match computePanelShape() (2560x1440p aspect ratio)
    // Note: For stereoscopic mode, PanelDimensions is set BEFORE PanelSceneObject creation
    // For other modes, it's set here in the callback
    val existingDimensions = entity.tryGetComponent<PanelDimensions>()
    if (existingDimensions == null || existingDimensions.dimensions != panelSize) {
      entity.setComponent(PanelDimensions(panelSize))
      Log.i(TAG, "Set PanelDimensions: ${panelSize.x}m x ${panelSize.y}m (aspect ratio: ${panelSize.x / panelSize.y})")
    } else {
      Log.i(TAG, "PanelDimensions already set correctly: ${panelSize.x}m x ${panelSize.y}m")
    }
    
    entity.setComponent(Scale(Vector3(1f))) // Initial scale of 1.0
    entity.setComponent(Grabbable(enabled = true, type = GrabbableType.PIVOT_Y))
    entity.setComponent(Visible(false)) // Hidden initially, shown when stream is ready
    entity.setComponent(Scalable()) // Enable corner scaling
    entity.setComponent(ScaledParent()) // Mark as scalable parent (required for child entities)
    entity.setComponent(parentComponent) // Parent to PanelManager

    // Add HeroLighting component if lighting emission is enabled
    if (useLightingEmission) {
      entity.setComponent(HeroLighting(isEnabled = true))
      Log.i(TAG, "HeroLighting component added to video panel")
    }

    // Register with scaling system
    val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
    if (touchScalableSystem != null) {
      touchScalableSystem.registerEntity(entity)
      Log.i(TAG, "Video panel entity registered with TouchScalableSystem")
    } else {
      Log.w(TAG, "TouchScalableSystem not found - scaling will not work")
    }

    Log.i(TAG, "Video panel components added - parented to PanelManager, hidden initially")
    return entity
  }

  /**
   * Attaches child entities (ButtonShelf, StereoDepthSlider, BiasLighting) to the video panel.
   * This function is called after the video panel entity is ready and all components are added.
   */
  private fun attachChildEntitiesToVideoPanel() {
    videoPanelEntity?.let { entity ->
      // Load immersive settings if not already loaded
      val settings = try {
        ImmersiveSettings.load(this)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to load immersive settings: ${e.message}")
        null
      }
      
      // Attach ButtonShelf to video panel if it was created before video panel
      buttonShelfEntity?.let { shelf ->
        shelf.attachToEntity(entity)
        Log.i(TAG, "ButtonShelf attached to video panel")
        
        // Force update children to ensure ButtonShelf is positioned correctly
        val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
        if (scaleChildrenSystem != null) {
          scaleChildrenSystem.forceUpdateChildren(entity)
          Log.i(TAG, "ScaleChildrenSystem force update called for ButtonShelf")
        }
        
        // Create and register visibility system if not already done
        if (buttonShelfVisibilitySystem == null) {
          buttonShelfVisibilitySystem = com.example.moonlight_spatialsdk.systems.buttonShelfVisibility.ButtonShelfVisibilitySystem(
              buttonShelf = shelf,
              videoPanelEntity = entity
          )
          systemManager.registerSystem(buttonShelfVisibilitySystem!!)
          buttonShelfVisibilitySystem?.startTracking()
          Log.i(TAG, "ButtonShelfVisibilitySystem registered and started tracking")
        }
      }
      
      // Create BiasLightingEntity if lighting emission is enabled
      settings?.let { s ->
        val useLightingEmission = s.lightingEmissionEnabled || s.reflectionsEnabled
        if (useLightingEmission && biasLightingEntity == null) {
          biasLightingEntity = BiasLightingEntity(heroLightingSystem)
          biasLightingEntity?.attachToPanel(entity)
          
          // Register scale listener to update bias lighting when panel scales
          val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
          scaleChildrenSystem?.addScaleListener(entity) {
            biasLightingEntity?.updateFromParentScale()
          }
          
          Log.i(TAG, "BiasLightingEntity created and attached to video panel")
        }
      }
      
      // Initialize stereo video system and attach slider if stereoscopic depth is enabled
      settings?.let { s ->
        if (s.stereoscopicDepthEnabled) {
          if (stereoVideoSystem == null) {
            stereoVideoSystem = com.example.moonlight_spatialsdk.systems.stereo.StereoVideoSystem(stereoFormat = 0.0f) // 0.0 = side-by-side
            systemManager.registerSystem(stereoVideoSystem!!)
            Log.i(TAG, "StereoVideoSystem registered")
          }
          
          // Register video texture with stereo system after panel is ready
          coroutineScope.launch {
            delay(100) // Small delay to ensure SceneObject is ready
            stereoVideoSystem?.registerVideoTexture(entity)
            Log.i(TAG, "Video texture registered with StereoVideoSystem")
          }
          
          // Create stereo depth slider entity if not already created
          if (stereoDepthSliderEntity == null) {
            stereoDepthSliderEntity = com.example.moonlight_spatialsdk.entities.StereoDepthSliderEntity()
            Log.i(TAG, "StereoDepthSliderEntity created")
          }
          
          // Attach slider to video panel
          stereoDepthSliderEntity?.let { slider ->
            slider.attachToEntity(entity)
            Log.i(TAG, "StereoDepthSliderEntity attached to video panel")
            
            // Force update children to ensure slider is positioned correctly
            val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
            scaleChildrenSystem?.forceUpdateChildren(entity)
            
            // Create and register visibility system if not already done
            if (stereoDepthSliderVisibilitySystem == null) {
              stereoDepthSliderVisibilitySystem = com.example.moonlight_spatialsdk.systems.stereoDepthSlider.StereoDepthSliderVisibilitySystem(
                  slider = slider,
                  videoPanelEntity = entity
              )
              systemManager.registerSystem(stereoDepthSliderVisibilitySystem!!)
              stereoDepthSliderVisibilitySystem?.startTracking()
              Log.i(TAG, "StereoDepthSliderVisibilitySystem registered and started tracking")
            }
          }
        }
      }
    }
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
    
    // Clean up MRUK RoomMeshManager and SpatialAudioManager
    roomMeshManager?.destroy()
    roomMeshManager = null
    spatialAudioManager?.destroy()
    spatialAudioManager = null
    
    super.onSpatialShutdown()
    disconnect()
  }
  
  /**
   * Handle permission request results for MRUK scene access.
   */
  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    
    if (requestCode == REQUEST_CODE_PERMISSION_USE_SCENE &&
        permissions.isNotEmpty() &&
        permissions[0] == PERMISSION_USE_SCENE) {
      val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
      if (granted) {
        Log.i(TAG, "USE_SCENE permission granted, enabling immersive features")
        enableImmersiveFeatures()
      } else {
        Log.w(TAG, "USE_SCENE permission denied, immersive features unavailable")
        _isImmersiveModeEnabled.value = false
      }
    }
  }
  
  /**
   * Toggle immersive mode features.
   * 
   * When enabled:
   * - Requests USE_SCENE permission if not granted
   * - Loads MRUK scene from device
   * - Enables features configured in ImmersiveSettings (spatial audio, room dimming, 
   *   lighting emission, reflections)
   * 
   * When disabled:
   * - Disables all immersive effects immediately
   */
  fun toggleImmersiveMode() {
    val newEnabled = !_isImmersiveModeEnabled.value
    
    if (newEnabled) {
      // Check and request USE_SCENE permission if needed
      if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
        Log.i(TAG, "Requesting USE_SCENE permission for spatialize features")
        requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_CODE_PERMISSION_USE_SCENE)
        return // Will continue in onRequestPermissionsResult
      }
      enableImmersiveFeatures()
    } else {
      disableImmersiveFeatures()
    }
  }
  
  /**
   * Enables immersive features after permission is granted.
   * 
   * Reads ImmersiveSettings to determine which features to enable:
   * - Spatial Audio: Audio from panel position
   * - Room Dimming: Dim passthrough when streaming
   * - Lighting Emission: Panel emits ambient light
   * - Reflections: Reflect video on room surfaces
   * 
   * Following the Valinor pattern: load scene data first, then create
   * AnchorProceduralMesh AFTER scene loads successfully.
   */
  private fun enableImmersiveFeatures() {
    _isImmersiveModeEnabled.value = true
    
    // Load latest immersive settings
    immersiveSettings = ImmersiveSettings.load(this)
    Log.i(TAG, "Enabling immersive features with settings: spatialAudio=${immersiveSettings.spatialAudioEnabled}, " +
        "roomDimming=${immersiveSettings.roomDimmingEnabled}, " +
        "lightingEmission=${immersiveSettings.lightingEmissionEnabled}, " +
        "reflections=${immersiveSettings.reflectionsEnabled}")
    
    // Initialize RoomMeshManager if needed (for MRUK features)
    if (roomMeshManager == null) {
      roomMeshManager = RoomMeshManager(mrukFeature)
    }
    
    // Initialize SpatialAudioManager if spatial audio is enabled
    if (immersiveSettings.spatialAudioEnabled && spatialAudioManager == null) {
      spatialAudioManager = SpatialAudioManager(spatialAudioFeature)
    }
    
    // Enable room dimming if configured
    if (immersiveSettings.roomDimmingEnabled) {
      lightingPassthroughHandler?.enableRoomDimming()
      Log.i(TAG, "Room dimming enabled")
    }
    
    // Enable lighting emission if configured
    if (immersiveSettings.lightingEmissionEnabled) {
      heroLightingSystem?.lightingAlpha = 0.8f
      biasLightingEntity?.setVisible(true)
      biasLightingEntity?.setIntensity(0.8f)
      Log.i(TAG, "Lighting emission enabled")
    }
    
    // Load MRUK scene - AnchorProceduralMesh is created AFTER scene loads (Valinor pattern)
    roomMeshManager?.loadSceneFromDevice(
      onSceneLoaded = {
        Log.i(TAG, "MRUK scene loaded - AnchorProceduralMesh created")
        
        // Enable spatial audio for video panel if configured
        if (immersiveSettings.spatialAudioEnabled) {
          enableSpatialAudioIfReady()
        }
        
        // Enable wall lighting only for reflections (MRUK surface projections)
        // BiasLightingEntity handles lighting emission separately
        if (immersiveSettings.reflectionsEnabled) {
          wallLightingSystem?.transitionInstant(true)
          Log.i(TAG, "Wall reflections enabled")
        }
        
        Log.i(TAG, "Immersive features enabled")
      },
      onSceneLoadFailed = { result ->
        Log.e(TAG, "Failed to load MRUK scene: $result")
        _isImmersiveModeEnabled.value = false
      }
    )
  }
  
  /**
   * Enables spatial audio for the video panel if audio is ready.
   * 
   * Uses the actual channel count from the audio renderer to enable proper
   * surround sound support (5.1/7.1) when configured in Sunshine.
   */
  private fun enableSpatialAudioIfReady() {
    val entity = videoPanelEntity ?: return
    val audioSessionId = audioRenderer.audioSessionId
    val channelCount = audioRenderer.channelCount
    
    if (audioSessionId > 0) {
      Log.i(TAG, "Enabling spatial audio with session ID: $audioSessionId, channels: $channelCount")
      spatialAudioManager?.enableSpatialAudio(entity, audioSessionId, channelCount)
    } else {
      Log.w(TAG, "Audio session ID not available yet, spatial audio will be enabled when audio starts")
    }
  }
  
  /**
   * Disables all immersive features immediately.
   */
  private fun disableImmersiveFeatures() {
    _isImmersiveModeEnabled.value = false
    
    // Disable room mesh
    roomMeshManager?.hideRoomMesh()
    
    // Disable spatial audio
    spatialAudioManager?.disableSpatialAudio()
    
    // Disable room dimming
    lightingPassthroughHandler?.disableRoomDimming()
    
    // Disable lighting emission
    heroLightingSystem?.lightingAlpha = 0f
    biasLightingEntity?.setVisible(false)
    
    // Disable wall reflections
    wallLightingSystem?.transitionInstant(false)
    
    Log.i(TAG, "Immersive features disabled")
  }
  
  /**
   * Toggle snap-to-wall behavior for the video panel.
   * 
   * When enabled, the video panel will snap to the nearest wall when grabbed
   * and movement will be constrained to the wall plane (X/Y sliding with Z locked).
   */
  fun toggleSnapToWall() {
    val newEnabled = !_isSnapEnabled.value
    _isSnapEnabled.value = newEnabled
    
    // Enable/disable WallSnap component on video panel
    videoPanelEntity?.let { entity ->
      if (newEnabled) {
        // Add WallSnap component to enable wall-constrained movement
        entity.setComponent(WallSnap(
            isEnabled = true,
            isSnappedToWall = false,
            wallPlaneNormal = Vector3(0f, 0f, 1f),
            wallPlanePoint = Vector3(0f, 0f, 0f),
            wallOffset = 0.02f
        ))
        Log.i(TAG, "WallSnap component enabled on video panel")
      } else {
        // Disable WallSnap by setting isEnabled to false
        if (entity.hasComponent<WallSnap>()) {
          val wallSnap = entity.getComponent<WallSnap>()
          wallSnap.isEnabled = false
          wallSnap.isSnappedToWall = false
          entity.setComponent(wallSnap)
          Log.i(TAG, "WallSnap component disabled on video panel")
        }
      }
    }
    
    Log.i(TAG, "Snap to wall ${if (newEnabled) "enabled" else "disabled"}")
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
   * Passes current streaming info for debugging display.
   */
  private fun startPanelActivityInOverlay() {
    val prefs = PreferenceConfiguration.readPreferences(this)
    val isConnected = connectionManager.isConnected()
    val connectionParams = connectionManager.getCurrentConnectionParams()
    
    val panelIntent = Intent(this, PancakeActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra("streaming_active", isConnected)
      if (isConnected && connectionParams != null) {
        putExtra("connected_host", connectionParams.first)
        putExtra("streaming_resolution", "${prefs.width}x${prefs.height}")
        putExtra("streaming_fps", prefs.fps)
        putExtra("streaming_audio_channels", audioRenderer.channelCount)
      }
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
    
    // Clean up stereo depth slider visibility system
    stereoDepthSliderVisibilitySystem?.stopTracking()
    stereoDepthSliderVisibilitySystem = null
    
    // Clean up stereo depth slider entity
    stereoDepthSliderEntity?.detachFromEntity()
    stereoDepthSliderEntity?.destroy()
    stereoDepthSliderEntity = null
    
    // Clean up stereo video system
    stereoVideoSystem?.cleanup()
    stereoVideoSystem = null
    
    // Destroy video panel entity completely on disconnect
    // The surface becomes invalid after stream stops, so we need a fresh panel for reconnection
    videoPanelEntity?.let { entity ->
      entity.setComponent(Visible(false))
      entity.destroy()
      Log.i(TAG, "Video panel entity destroyed for clean reconnection")
    }
    videoPanelEntity = null
    
    // Unregister panel registration to allow recreation
    // This is critical for proper cleanup after crashes or improper disconnects
    SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
      try {
        immersiveActivity.unregisterPanel(R.id.ui_example)
        Log.i(TAG, "Video panel registration unregistered for cleanup")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to unregister panel (may already be unregistered)", e)
      }
    }
    
    // Clean up decoder renderer - detach surface and cleanup
    try {
      val decoder = moonlightPanelRenderer.getDecoder()
      decoder.stop()
      decoder.cleanup()
      // Clear the render target (set to null) - this detaches the surface
      (decoder as? com.limelight.binding.video.NativeDecoderRenderer)?.setRenderTarget(null)
      Log.i(TAG, "Decoder renderer cleaned up")
    } catch (e: Exception) {
      Log.w(TAG, "Error cleaning up decoder renderer", e)
    }
    
    connectionManager.stopStream()
    _connectionStatus.value = "Disconnected"
    _isConnected.value = false
    pendingConnectionParams = null
    isPaired = false
    isSurfaceReady = false
    
    Log.i(TAG, "Disconnected - returning to 2D panel mode")
  }
  

  /**
   * Poll for video panel entity creation via Query if SDK callback didn't set it.
   * Maximum 10 attempts with 50ms delay between attempts.
   */
  private suspend fun pollForVideoPanelEntity() {
    var pollAttempts = 0
    val maxPollAttempts = 10
    val pollDelayMs = 50L
    
    while (pollAttempts < maxPollAttempts && videoPanelEntity == null) {
      delay(pollDelayMs)
      val query = Query.where { has(Panel.id) }
      val entity = query.eval().firstOrNull { entity ->
        val panel = entity.tryGetComponent<Panel>()
        panel != null && panel.panelRegistrationId == R.id.ui_example
      }
      if (entity != null) {
        videoPanelEntity = entity
        Log.i(TAG, "Found video panel entity via Query polling (attempt ${pollAttempts + 1})")
        return
      }
      pollAttempts++
    }
    
    if (videoPanelEntity == null) {
      Log.w(TAG, "Video panel entity not found after $maxPollAttempts polling attempts")
    }
  }

  /**
   * Create fallback video panel entity if SDK callbacks fail.
   * This ensures at least something is visible when ImmersiveActivity launches.
   */
  private fun createFallbackVideoPanelEntity(panelSize: Vector2, useLightingEmission: Boolean) {
    Log.w(TAG, "Creating fallback video panel entity - SDK callbacks did not execute")
    
    val managerEntity = panelManager?.panelManagerEntity
    if (managerEntity == null) {
      Log.e(TAG, "Cannot create fallback entity - PanelManager entity is null")
      return
    }
    
    val parentComponent = TransformParent(managerEntity)
    
    videoPanelEntity = Entity.create(
        listOf(
            Panel(R.id.ui_example),
            Transform(Pose(Vector3(0f, 0f, 0f))),
            PanelDimensions(panelSize),
            Scale(Vector3(1f)),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(pendingConnectionParams == null), // Visible if no pending params
            Scalable(),
            ScaledParent(),
            parentComponent
        )
    )
    
    if (useLightingEmission) {
      videoPanelEntity?.setComponent(HeroLighting(isEnabled = true))
    }
    
    // Register with scaling system
    val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
    touchScalableSystem?.registerEntity(videoPanelEntity!!)
    
    Log.i(TAG, "Fallback video panel entity created - size: ${panelSize.x}m x ${panelSize.y}m")
  }

  /**
   * Verify video panel entity was created and finalize setup.
   * If entity is null, polls for it, then creates fallback if still null.
   */
  private suspend fun verifyAndFinalizeVideoPanelEntity(
      panelSize: Vector2,
      useLightingEmission: Boolean,
      registrationMode: String
  ) {
    // If entity not set by callback, poll for it
    if (videoPanelEntity == null) {
      Log.w(TAG, "Video panel entity not set by $registrationMode callback, polling for entity...")
      pollForVideoPanelEntity()
    }
    
    // If still null after polling, create fallback
    if (videoPanelEntity == null) {
      Log.e(TAG, "Video panel entity still null after polling, creating fallback entity")
      createFallbackVideoPanelEntity(panelSize, useLightingEmission)
    } else {
      Log.i(TAG, "Video panel entity verified: $videoPanelEntity")
      
      // Ensure visibility if no pending connection params
      if (pendingConnectionParams == null) {
        videoPanelEntity?.setComponent(Visible(true))
        Log.i(TAG, "Video panel made visible on launch (no pending connection params)")
      }
    }
  }

  private fun createVideoPanelEntity() {
    Log.i(TAG, "Creating video panel entity with Panel(R.id.ui_example)")
    
    // Load immersive settings to determine panel type
    immersiveSettings = ImmersiveSettings.load(this)
    val useLightingEmission = immersiveSettings.lightingEmissionEnabled || immersiveSettings.reflectionsEnabled
    val useStereoscopicDepth = immersiveSettings.stereoscopicDepthEnabled
    
    Log.i(TAG, "Video panel settings: lightingEmission=$useLightingEmission, stereoscopicDepth=$useStereoscopicDepth")
    
    // Retry logic for executeOnVrActivity with exponential backoff
    var retryCount = 0
    val maxRetries = 3
    val retryDelays = listOf(100L, 200L, 400L)
    var isRegistrationInProgress = false
    
    fun attemptRegistration() {
      if (isRegistrationInProgress) {
        Log.d(TAG, "Registration already in progress, skipping duplicate attempt")
        return
      }
      
      isRegistrationInProgress = true
      Log.i(TAG, "Attempting video panel registration (attempt ${retryCount + 1}/$maxRetries)...")
      
      // Register panel dynamically using executeOnVrActivity to ensure activity is fully ready
      // This matches PremiumMediaSample pattern and ensures panelManager is initialized
      SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
        Log.i(TAG, "executeOnVrActivity callback executed - registering video panel")
      // Use PanelCreator with PanelConfigOptions for stereoscopic depth (allows panelShader to be set directly)
      if (useStereoscopicDepth) {
        Log.i(TAG, "Using PanelCreator with PanelConfigOptions for stereoscopic depth (custom shader support)")
        
        // Foundation: Panel size matches doubled texture resolution for side-by-side stereo
        // User resolution (e.g., 2560x1440p) -> Panel texture (5120x1440p) -> Panel physical size matches aspect ratio
        // This ensures the shader output matches the panel size
        val textureWidth = prefs.width * 2  // Doubled width for side-by-side stereo (e.g., 2560 -> 5120)
        val textureHeight = prefs.height    // Height remains same (e.g., 1440)
        val aspectRatio = textureWidth.toFloat() / textureHeight.toFloat()
        val panelWidth = basePanelHeightMeters * aspectRatio
        val panelHeight = basePanelHeightMeters
        
        Log.i(TAG, "Creating ultrawide panel: ${panelWidth}m x ${panelHeight}m (aspect ratio: $aspectRatio)")
        Log.i(TAG, "Panel texture resolution: ${textureWidth}x${textureHeight} (user resolution: ${prefs.width}x${prefs.height})")
        
        immersiveActivity.registerPanel(
            PanelCreator(
                registrationId = R.id.ui_example,
                panelCreator = { entity ->
                  Log.i(TAG, "PanelCreator callback executed - entity=$entity")
                  videoPanelEntity = entity
                  Log.i(TAG, "videoPanelEntity set to $entity")
                  
                  val panelConfigOptions = PanelConfigOptions().apply {
                    // Texture resolution: doubled width for side-by-side stereo
                    // This ensures the surface/texture matches the panel size
                    layoutWidthInPx = textureWidth  // e.g., 5120 for 2560x1440p user resolution
                    layoutHeightInPx = textureHeight  // e.g., 1440
                    // Physical panel dimensions in meters (matches texture aspect ratio)
                    width = panelWidth
                    height = panelHeight
                    mips = 1 // Disable mipmaps for low latency
                    stereoMode = StereoMode.None // TEST: Set to None to see full ultrawide panel (left half red, right half blue)
                    panelShader = "stereo_video" // Set custom shader directly - this is the key!
                    forceSceneTexture = true // Enable scene texture for shader support
                    enableTransparent = false
                    themeResourceId = R.style.PanelAppThemeTransparent
                  }
                  
                  // CRITICAL: Set PanelDimensions BEFORE PanelSceneObject creation
                  // PanelSceneObject may use PanelDimensions from entity to determine panel outline
                  // Setting it before creation ensures the panel is created with correct ultrawide dimensions
                  val panelSize = Vector2(panelWidth, panelHeight)
                  entity.setComponent(PanelDimensions(panelSize))
                  Log.i(TAG, "Set PanelDimensions BEFORE PanelSceneObject: ${panelSize.x}m x ${panelSize.y}m (ultrawide)")
                  
                  val panelSceneObject = PanelSceneObject(immersiveActivity.scene, entity, panelConfigOptions)
                  
                  // Check if PanelSceneObject changed PanelDimensions (it shouldn't, but verify)
                  val panelDimensionsAfterCreation = entity.getComponent<PanelDimensions>()
                  Log.i(TAG, "PanelDimensions after PanelSceneObject: ${panelDimensionsAfterCreation.dimensions.x}m x ${panelDimensionsAfterCreation.dimensions.y}m (expected: ${panelWidth}m x ${panelHeight}m)")
                  
                  // Add remaining components (PanelDimensions already set above)
                  addVideoPanelComponents(entity, panelSize, useLightingEmission)
                  
                  // Get surface from PanelSceneObject
                  val surface = panelSceneObject.getSurface()
                  Log.i(TAG, "Panel surface created for stereoscopic depth panel entity=$entity")
                  Log.i(TAG, "Panel surface size: ${panelConfigOptions.layoutWidthInPx}x${panelConfigOptions.layoutHeightInPx} (matches panel texture resolution)")
                  Log.i(TAG, "Panel physical size: ${panelWidth}m x ${panelHeight}m (aspect ratio: $aspectRatio)")
                  
                  SurfaceUtil.paintBlack(surface)
                  
                  // Configure decoder with preferences when panel is created
                  // Note: Decoder will output at prefs.width x prefs.height, but surface is prefs.width*2 x prefs.height
                  // The shader will handle mapping the decoder output to the full panel size
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
                  
                  // Add SceneObject to system
                  immersiveActivity.systemManager
                      .findSystem<SceneObjectSystem>()
                      ?.addSceneObject(entity, CompletableFuture.completedFuture(panelSceneObject))
                  
                  // Attach child entities after panel is ready
                  attachChildEntitiesToVideoPanel()
                  
                  // Verify entity was set and finalize setup
                  coroutineScope.launch {
                    verifyAndFinalizeVideoPanelEntity(panelSize, useLightingEmission, "PanelCreator (stereoscopic)")
                  }
                  
                  panelSceneObject
                }
            )
        )
      } else if (useLightingEmission) {
        // Use ReadableVideoSurfacePanelRegistration for lighting emission (allows texture sampling)
        Log.i(TAG, "Using ReadableVideoSurfacePanelRegistration for lighting emission")
        val panelShape = computePanelShape()
        val panelSize = Vector2(panelShape.width, panelShape.height)
        
        immersiveActivity.registerPanel(
            ReadableVideoSurfacePanelRegistration(
                R.id.ui_example,
                surfaceConsumer = { panelEntity, surface ->
                  Log.i(TAG, "ReadableVideoSurfacePanelRegistration surfaceConsumer callback executed - entity=$panelEntity")
                  
                  // Store SDK-provided entity and add components
                  videoPanelEntity = panelEntity
                  Log.i(TAG, "videoPanelEntity set to $panelEntity")
                  addVideoPanelComponents(panelEntity, panelSize, useLightingEmission)
                  
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
                  
                  // Attach child entities after panel is ready
                  attachChildEntitiesToVideoPanel()
                  
                  // Verify entity was set and finalize setup
                  coroutineScope.launch {
                    verifyAndFinalizeVideoPanelEntity(panelSize, useLightingEmission, "ReadableVideoSurfacePanelRegistration")
                  }
                },
                settingsCreator = {
                  ReadableMediaPanelSettings(
                      shape = computePanelShape(),
                      display = PixelDisplayOptions(
                          width = prefs.width,
                          height = prefs.height
                      ),
                      rendering = ReadableMediaPanelRenderOptions(
                          mips = 4,
                      ),
                      style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  )
                },
            )
        )
      } else {
        // Use standard VideoSurfacePanelRegistration for better performance
        Log.i(TAG, "Using VideoSurfacePanelRegistration (standard mode)")
        val panelShape = computePanelShape()
        val panelSize = Vector2(panelShape.width, panelShape.height)
        
        immersiveActivity.registerPanel(
            VideoSurfacePanelRegistration(
                R.id.ui_example,
                surfaceConsumer = { panelEntity, surface ->
                  Log.i(TAG, "VideoSurfacePanelRegistration surfaceConsumer callback executed - entity=$panelEntity")
                  
                  // Store SDK-provided entity and add components
                  videoPanelEntity = panelEntity
                  Log.i(TAG, "videoPanelEntity set to $panelEntity")
                  addVideoPanelComponents(panelEntity, panelSize, useLightingEmission)
                  
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
                  
                  // Attach child entities after panel is ready
                  attachChildEntitiesToVideoPanel()
                  
                  // Verify entity was set and finalize setup
                  coroutineScope.launch {
                    verifyAndFinalizeVideoPanelEntity(panelSize, useLightingEmission, "VideoSurfacePanelRegistration")
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
    }
    
    // After registration attempt, verify entity was created
    // If not set and we haven't exhausted retries, schedule retry
    coroutineScope.launch {
      delay(500) // Wait for callbacks to potentially execute (increased from 300ms for reliability)
      isRegistrationInProgress = false // Allow new registration attempts
      
      if (videoPanelEntity == null && retryCount < maxRetries) {
        retryCount++
        Log.w(TAG, "Video panel entity not set after registration attempt ${retryCount}, retrying in ${retryDelays[retryCount - 1]}ms...")
        delay(retryDelays[retryCount - 1])
        attemptRegistration()
      } else if (videoPanelEntity == null) {
        // All retries exhausted - determine panel size and create fallback
        val panelSize = if (useStereoscopicDepth) {
          val textureWidth = prefs.width * 2
          val textureHeight = prefs.height
          val aspectRatio = textureWidth.toFloat() / textureHeight.toFloat()
          Vector2(basePanelHeightMeters * aspectRatio, basePanelHeightMeters)
        } else {
          val panelShape = computePanelShape()
          Vector2(panelShape.width, panelShape.height)
        }
        Log.e(TAG, "All registration retries exhausted, creating fallback entity")
        createFallbackVideoPanelEntity(panelSize, useLightingEmission)
      } else {
        Log.i(TAG, "Video panel entity successfully created after ${retryCount + 1} attempt(s)")
      }
    }
    }
    
    attemptRegistration()
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

/**
 * Disconnect dialog composable for stream options.
 * Provides options to reset panel size or end the stream.
 */
@Composable
private fun DisconnectDialog(
    showDialog: StateFlow<Boolean>,
    onResetPanelSize: () -> Unit,
    onEndStream: () -> Unit,
    onCancel: () -> Unit,
) {
    val show by showDialog.collectAsState()
    
    if (show) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = SpatialTheme.colorScheme.primaryAlphaBackground.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clip(SpatialTheme.shapes.large)
                    .background(brush = LocalColorScheme.current.panel)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Stream Options",
                    style = LocalTypography.current.headline2Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground
                    )
                )
                
                // Vertically stacked buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SecondaryButton(
                        label = "Reset Panel Size",
                        expanded = true,
                        onClick = {
                            onResetPanelSize()
                        }
                    )
                    Spacer(Modifier.size(28.dp))
                    DestructiveButton(
                        label = "End Stream",
                        expanded = true,
                        onClick = {
                            onEndStream()
                        }
                    )
                }
                
                Spacer(Modifier.height(8.dp))
                
                SecondaryButton(
                    label = "Cancel",
                    expanded = true,
                    onClick = {
                        onCancel()
                    }
                )
            }
        }
    }
}

package com.example.moonlight_spatialsdk

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.binding.input.ControllerHandler
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import java.util.concurrent.Executors

/**
 * Manages Moonlight streaming connection lifecycle for Spatial SDK.
 * 
 * This class bridges Moonlight's NvConnection to the Spatial SDK environment,
 * handling connection initialization, stream configuration, and lifecycle callbacks.
 */
class MoonlightConnectionManager(
    private val context: Context,
    private val activity: Activity,
    private val decoderRenderer: VideoDecoderRenderer,
    private val audioRenderer: AndroidAudioRenderer,
    private val onStatusUpdate: ((String, Boolean) -> Unit)? = null
) : NvConnectionListener {
    data class ServerCapabilities(
        val codecModeSupport: Long,
        val maxLumaH264: Long,
        val maxLumaHEVC: Long,
        val supports4k: Boolean,
    )
    private val tag = "MoonlightConnectionMgr"
    private var connection: NvConnection? = null
    private var isConnected: Boolean = false
    private var controllerHandler: ControllerHandler? = null
    private var currentPrefs: PreferenceConfiguration? = null
    private val cryptoProvider: LimelightCryptoProvider = AndroidCryptoProvider(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Check if server requires pairing.
     * 
     * @param host Server hostname or IP address
     * @param port Server port (typically 47989)
     * @param callback Callback with pairing state (true if paired, false if needs pairing)
     */
    fun checkPairing(
        host: String,
        port: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        executor.execute {
            try {
                Log.i(tag, "checkPairing start host=$host port=$port")
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val pairState = http.getPairState()
                val hasPinnedCert = IdentityStore.loadServerCert(context, host) != null
                val isPaired = pairState == PairingManager.PairState.PAIRED && hasPinnedCert
                val error =
                    when {
                        pairState != PairingManager.PairState.PAIRED -> "Server requires pairing"
                        !hasPinnedCert -> "Pairing certificate missing; re-pair required"
                        else -> null
                    }
                Log.i(tag, "checkPairing result host=$host state=$pairState")
                postToMain { callback(isPaired, error) }
            } catch (e: Exception) {
                Log.e(tag, "checkPairing error host=$host", e)
                postToMain { callback(false, "Error checking pairing: ${e.message}") }
            }
        }
    }

    /**
     * Pair with server using PIN.
     * 
     * @param host Server hostname or IP address
     * @param port Server port (typically 47989)
     * @param pin PIN code from server
     * @param callback Callback with pairing result (true if successful, false with error message)
     */
    fun pairWithServer(
        host: String,
        port: Int,
        pin: String,
        callback: (Boolean, String?) -> Unit
    ) {
        executor.execute {
            try {
                Log.i(tag, "pairWithServer start host=$host port=$port pin=$pin")
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val pairingManager = PairingManager(http, cryptoProvider)
                val serverInfo = http.getServerInfo(true)
                val pairState = pairingManager.pair(serverInfo, pin)
                
                when (pairState) {
                    PairingManager.PairState.PAIRED -> {
                        pairingManager.pairedCert?.let { IdentityStore.saveServerCert(context, host, it) }
                        Log.i(tag, "pairWithServer success host=$host")
                        postToMain { callback(true, null) }
                    }
                    PairingManager.PairState.PIN_WRONG -> {
                        Log.w(tag, "pairWithServer pin wrong host=$host")
                        postToMain { callback(false, "Incorrect PIN") }
                    }
                    PairingManager.PairState.ALREADY_IN_PROGRESS -> {
                        Log.w(tag, "pairWithServer already in progress host=$host")
                        postToMain { callback(false, "Pairing already in progress") }
                    }
                    else -> {
                        Log.w(tag, "pairWithServer failed state=$pairState host=$host")
                        postToMain { callback(false, "Pairing failed") }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "pairWithServer error host=$host", e)
                postToMain { callback(false, "Pairing error: ${e.message}") }
            }
        }
    }

    fun fetchServerCapabilities(
        host: String,
        port: Int,
        callback: (ServerCapabilities?, String?) -> Unit
    ) {
        executor.execute {
            try {
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val serverInfo = http.getServerInfo(true)
                val caps =
                    ServerCapabilities(
                        codecModeSupport = http.getServerCodecModeSupport(serverInfo),
                        maxLumaH264 = http.getMaxLumaPixelsH264(serverInfo),
                        maxLumaHEVC = http.getMaxLumaPixelsHEVC(serverInfo),
                        supports4k = http.supports4K(serverInfo),
                    )
                postToMain { callback(caps, null) }
            } catch (e: Exception) {
                Log.e(tag, "fetchServerCapabilities error host=$host", e)
                postToMain { callback(null, "Failed to load capabilities: ${e.message}") }
            }
        }
    }

    /**
     * Start a streaming session with the specified parameters.
     * 
     * @param host Server hostname or IP address
     * @param port Server port (typically 47989)
     * @param appId Application ID to launch (0 for desktop)
     * @param uniqueId Unique device identifier
     * @param prefs Stream preferences (resolution, bitrate, etc.)
     */
    fun startStream(
        host: String,
        port: Int,
        appId: Int,
        prefs: PreferenceConfiguration
    ) {
        executor.execute {
            try {
                // Ensure decoder is in a clean state before starting a new connection
                try {
                    decoderRenderer.stop()
                } catch (_: Exception) {
                    // If decoder wasn't started yet, stop() may throw; ignore and continue
                }

                Log.i(tag, "startStream host=$host port=$port appId=$appId res=${prefs.width}x${prefs.height} fps=${prefs.fps} bitrate=${prefs.bitrate}")
                Log.i(tag, "startStream: audioConfig=${prefs.audioConfiguration} videoFormats=0x${Integer.toHexString(getSupportedVideoFormats(prefs))}")
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                Log.i(tag, "startStream: uniqueId=$uniqueId serverCert=${if (serverCert != null) "present" else "null"}")

                // Check server HDR capability before requesting HDR
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val serverInfo = http.getServerInfo(true)
                val serverCodecModeSupport = http.getServerCodecModeSupport(serverInfo)
                Log.i(tag, "startStream: serverCodecModeSupport=0x${java.lang.Long.toHexString(serverCodecModeSupport)}")
                
                // Server HDR support mask: 0x20200 (HEVC Main10 HDR10 + AV1 Main10 HDR10)
                val serverSupportsHdr = (serverCodecModeSupport and 0x20200L) != 0L
                var effectiveHdrEnabled = prefs.enableHdr
                
                if (prefs.enableHdr && !serverSupportsHdr) {
                    Log.w(tag, "startStream: HDR requested but server does not support HDR (serverCodecModeSupport=0x${java.lang.Long.toHexString(serverCodecModeSupport)}). Disabling HDR for this connection.")
                    postToMain { 
                        onStatusUpdate?.invoke("Your PC GPU does not support streaming HDR. The stream will be SDR.", false) 
                    }
                    effectiveHdrEnabled = false
                }

                // Determine color space and range based on effective HDR state
                // Request FULL range BT709 for SDR, BT2020 for HDR
                val colorSpace = if (effectiveHdrEnabled) {
                    MoonBridge.COLORSPACE_REC_2020
                } else {
                    MoonBridge.COLORSPACE_REC_709
                }
                val colorRange = if (prefs.fullRange) {
                    MoonBridge.COLOR_RANGE_FULL
                } else {
                    MoonBridge.COLOR_RANGE_LIMITED
                }
                
                Log.i(tag, "startStream: colorSpace=${colorSpace} (${if (effectiveHdrEnabled) "BT2020" else "BT709"}) colorRange=${colorRange} (${if (prefs.fullRange) "FULL" else "LIMITED"}) enableHdr=${prefs.enableHdr} effectiveHdrEnabled=$effectiveHdrEnabled")
                
                // Get supported formats - remove 10-bit mask if HDR was disabled due to server capability
                val supportedFormats = if (effectiveHdrEnabled) {
                    getSupportedVideoFormats(prefs)
                } else {
                    // Remove 10-bit formats if HDR is not enabled
                    val baseFormats = when (prefs.videoFormat) {
                        PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
                        PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
                        PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
                        PreferenceConfiguration.FormatOption.AUTO -> {
                            MoonBridge.VIDEO_FORMAT_H264 or MoonBridge.VIDEO_FORMAT_H265
                        }
                    }
                    baseFormats
                }
                Log.i(tag, "startStream: supportedVideoFormats=0x${Integer.toHexString(supportedFormats)} has10Bit=${(supportedFormats and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0} enableHdr=${prefs.enableHdr}")
                
                val streamConfig = StreamConfiguration.Builder()
                    .setApp(NvApp(if (appId == 0) "Desktop" else "Moonlight", appId, false))
                    .setResolution(prefs.width, prefs.height)
                    .setRefreshRate(prefs.fps)
                    .setBitrate(prefs.bitrate)
                    // Disable SOPS to simplify stream setup on device
                    .setEnableSops(false)
                    .setAudioConfiguration(prefs.audioConfiguration)
                    .setSupportedVideoFormats(supportedFormats)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setClientRefreshRateX100(0) // Set to 0 for compatibility with older servers
                    .setColorSpace(colorSpace)
                    .setColorRange(colorRange)
                    .build()
                Log.i(tag, "startStream: streamConfig created width=${streamConfig.width} height=${streamConfig.height} fps=${streamConfig.refreshRate} bitrate=${streamConfig.bitrate} supportedFormats=0x${Integer.toHexString(streamConfig.getSupportedVideoFormats())}")
            
                // CRITICAL: Setup bridge BEFORE creating NvConnection
                // This ensures videoRenderer is registered when native code calls bridgeDrSetup()
                // If we don't do this, the server will connect but terminate immediately because
                // bridgeDrSetup() can't be called (videoRenderer is null)
                com.limelight.nvstream.jni.MoonBridge.setupBridge(decoderRenderer, audioRenderer, this)
                Log.i(tag, "startStream: MoonBridge.setupBridge() called before connection start")
            
                connection = NvConnection(
                    context,
                    computerDetails,
                    0, // httpsPort (0 means use default)
                    uniqueId,
                    streamConfig,
                    cryptoProvider,
                    serverCert // serverCert (null means use default)
                )
                Log.i(tag, "startStream: NvConnection created, calling start()")
                currentPrefs = prefs
                connection?.start(audioRenderer, decoderRenderer, this)
                Log.i(tag, "NvConnection.start invoked host=$host")
            } catch (e: Exception) {
                Log.e(tag, "startStream error host=$host", e)
                postToMain { onStatusUpdate?.invoke("Failed to start stream: ${e.message}", false) }
                connection = null
                isConnected = false
            }
        }
    }

    /**
     * Stop the current streaming session and clean up resources.
     */
    fun stopStream() {
        executor.execute {
            Log.i(tag, "stopStream invoked")
            
            // Destroy ControllerHandler
            controllerHandler?.destroy()
            controllerHandler = null
            currentPrefs = null
            
            connection?.stop()
            connection = null
            isConnected = false
        }
    }

    /**
     * Get supported video formats based on preferences.
     * Includes 10-bit formats when HDR is enabled.
     */
    private fun getSupportedVideoFormats(prefs: PreferenceConfiguration): Int {
        val baseFormats = when (prefs.videoFormat) {
            PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
            PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
            PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            PreferenceConfiguration.FormatOption.AUTO -> {
                // Support both H.264 and HEVC, let server choose based on capabilities
                MoonBridge.VIDEO_FORMAT_H264 or MoonBridge.VIDEO_FORMAT_H265
            }
        }
        
        // Add 10-bit formats if HDR is enabled
        return if (prefs.enableHdr) {
            baseFormats or MoonBridge.VIDEO_FORMAT_MASK_10BIT
        } else {
            baseFormats
        }
    }

    // NvConnectionListener implementation
    override fun stageStarting(stageName: String) {
        Log.i(tag, "stageStarting $stageName")
        onStatusUpdate?.let { postToMain { it("Starting: $stageName", false) } }
    }

    override fun stageComplete(stageName: String) {
        Log.i(tag, "stageComplete $stageName")
        onStatusUpdate?.let { postToMain { it("Completed: $stageName", false) } }
    }

    override fun stageFailed(stageName: String, portFlags: Int, errorCode: Int) {
        isConnected = false
        Log.w(tag, "stageFailed $stageName error=$errorCode portFlags=$portFlags")
        onStatusUpdate?.let { postToMain { it("Failed: $stageName (error: $errorCode)", false) } }
        try {
            decoderRenderer.stop()
        } catch (_: Exception) {
            // Ignore stop errors
        }
        connection = null
    }

    override fun connectionStarted() {
        isConnected = true
        Log.i(tag, "connectionStarted")
        
        // Try to create ControllerHandler automatically if connection and prefs are available
        // If not available now, user can call initializeControllerHandler() manually later
        initializeControllerHandler()
        
        onStatusUpdate?.let { postToMain { it("Connected", true) } }
    }

    override fun connectionTerminated(errorCode: Int) {
        isConnected = false
        val message = if (errorCode == 0) "Disconnected" else "Connection terminated (error: $errorCode)"
        Log.w(tag, "connectionTerminated error=$errorCode")
        
        // Destroy ControllerHandler
        controllerHandler?.destroy()
        controllerHandler = null
        currentPrefs = null
        
        onStatusUpdate?.let { postToMain { it(message, false) } }
        try {
            decoderRenderer.stop()
        } catch (_: Exception) {
            // Ignore stop errors
        }
        connection = null
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        val status = when (connectionStatus) {
            com.limelight.nvstream.jni.MoonBridge.CONN_STATUS_OKAY -> "Connection: Good"
            com.limelight.nvstream.jni.MoonBridge.CONN_STATUS_POOR -> "Connection: Poor"
            else -> "Connection: Unknown"
        }
        Log.i(tag, "connectionStatusUpdate statusCode=$connectionStatus status=$status")
        onStatusUpdate?.let { postToMain { it(status, true) } }
    }

    override fun displayMessage(message: String) {
        Log.i(tag, "displayMessage $message")
        onStatusUpdate?.let { postToMain { it(message, isConnected) } }
    }

    override fun displayTransientMessage(message: String) {
        // Transient messages can be logged but don't need to update main status
        Log.d(tag, "displayTransientMessage $message")
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        // Controller rumble feedback
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        // Controller trigger rumble feedback
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        // #region agent log
        try {
            java.io.FileWriter("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"MoonlightConnectionManager.kt:380\",\"message\":\"setHdrMode called in connection manager\",\"data\":{\"enabled\":$enabled,\"hdrMetadata\":\"${if (hdrMetadata != null) "present(${hdrMetadata.size} bytes)" else "null"}\"},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        decoderRenderer.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        // Controller motion sensor state changed
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        // Controller LED color changed
    }

    /**
     * Manually initialize ControllerHandler for input passthrough.
     * Can be called after connection is established, e.g., when a controller is paired.
     * Safe to call multiple times - will only create if not already initialized.
     * 
     * @return true if ControllerHandler was created or already exists, false if creation failed
     */
    fun initializeControllerHandler(): Boolean {
        // If already initialized, return success
        if (controllerHandler != null) {
            Log.d(tag, "initializeControllerHandler: Already initialized")
            return true
        }
        
        // Check if connection and prefs are available
        val conn = connection
        val prefs = currentPrefs
        if (conn == null || prefs == null) {
            Log.w(tag, "initializeControllerHandler: Cannot create - connection=${conn != null} prefs=${prefs != null}")
            return false
        }
        
        // Create ControllerHandler
        try {
            controllerHandler = ControllerHandler(activity, conn, null, prefs)
            Log.i(tag, "initializeControllerHandler: ControllerHandler created successfully")
            return true
        } catch (e: Exception) {
            Log.e(tag, "initializeControllerHandler: Failed to create ControllerHandler", e)
            return false
        }
    }

    /**
     * Get the ControllerHandler instance for input event forwarding.
     * Returns null if ControllerHandler is not created yet.
     */
    fun getControllerHandler(): ControllerHandler? {
        return controllerHandler
    }

    /**
     * Check if currently connected to the server.
     * Used for input event filtering.
     */
    fun isConnected(): Boolean {
        return isConnected
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}


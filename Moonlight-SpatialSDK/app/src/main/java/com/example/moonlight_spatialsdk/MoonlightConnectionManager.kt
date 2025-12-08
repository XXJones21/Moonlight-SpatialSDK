package com.example.moonlight_spatialsdk

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
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

                val streamConfig = StreamConfiguration.Builder()
                    .setApp(NvApp(if (appId == 0) "Desktop" else "Moonlight", appId, false))
                    .setResolution(prefs.width, prefs.height)
                    .setRefreshRate(prefs.fps)
                    .setBitrate(prefs.bitrate)
                    // Disable SOPS to simplify stream setup on device
                    .setEnableSops(false)
                    .setAudioConfiguration(prefs.audioConfiguration)
                    .setSupportedVideoFormats(getSupportedVideoFormats(prefs))
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setClientRefreshRateX100(0) // Set to 0 for compatibility with older servers
                    .build()
                Log.i(tag, "startStream: streamConfig created width=${streamConfig.width} height=${streamConfig.height} fps=${streamConfig.refreshRate} bitrate=${streamConfig.bitrate}")
            
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
            connection?.stop()
            connection = null
            isConnected = false
        }
    }

    /**
     * Get supported video formats based on preferences.
     */
    private fun getSupportedVideoFormats(prefs: PreferenceConfiguration): Int {
        return when (prefs.videoFormat) {
            PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
            PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
            PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            PreferenceConfiguration.FormatOption.AUTO -> {
                // Support both H.264 and HEVC, let server choose based on capabilities
                MoonBridge.VIDEO_FORMAT_H264 or MoonBridge.VIDEO_FORMAT_H265
            }
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
        onStatusUpdate?.let { postToMain { it("Connected", true) } }
    }

    override fun connectionTerminated(errorCode: Int) {
        isConnected = false
        val message = if (errorCode == 0) "Disconnected" else "Connection terminated (error: $errorCode)"
        Log.w(tag, "connectionTerminated error=$errorCode")
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
        decoderRenderer.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        // Controller motion sensor state changed
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        // Controller LED color changed
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}


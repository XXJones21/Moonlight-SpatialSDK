package com.example.moonlight_spatialsdk

import android.app.Activity
import android.content.Context
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.binding.video.MediaCodecDecoderRenderer
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
    private val decoderRenderer: MediaCodecDecoderRenderer,
    private val audioRenderer: AndroidAudioRenderer,
    private val onStatusUpdate: ((String, Boolean) -> Unit)? = null
) : NvConnectionListener {
    private var connection: NvConnection? = null
    private var isConnected: Boolean = false
    private val cryptoProvider: LimelightCryptoProvider = AndroidCryptoProvider(context)
    private val executor = Executors.newSingleThreadExecutor()

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
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val http = NvHTTP(computerDetails, 0, "0123456789ABCDEF", null, cryptoProvider)
                val pairState = http.getPairState()
                val isPaired = pairState == PairingManager.PairState.PAIRED
                val error = if (isPaired) null else "Server requires pairing"
                callback(isPaired, error)
            } catch (e: Exception) {
                callback(false, "Error checking pairing: ${e.message}")
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
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val http = NvHTTP(computerDetails, 0, "0123456789ABCDEF", null, cryptoProvider)
                val pairingManager = PairingManager(http, cryptoProvider)
                val serverInfo = http.getServerInfo(true)
                val pairState = pairingManager.pair(serverInfo, pin)
                
                when (pairState) {
                    PairingManager.PairState.PAIRED -> {
                        callback(true, null)
                    }
                    PairingManager.PairState.PIN_WRONG -> {
                        callback(false, "Incorrect PIN")
                    }
                    PairingManager.PairState.ALREADY_IN_PROGRESS -> {
                        callback(false, "Pairing already in progress")
                    }
                    else -> {
                        callback(false, "Pairing failed")
                    }
                }
            } catch (e: Exception) {
                callback(false, "Pairing error: ${e.message}")
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
        uniqueId: String,
        prefs: PreferenceConfiguration
    ) {
        executor.execute {
        val computerDetails = ComputerDetails.AddressTuple(host, port)
        
        val streamConfig = StreamConfiguration.Builder()
            .setApp(NvApp("Moonlight", appId, false))
            .setResolution(prefs.width, prefs.height)
            .setRefreshRate(prefs.fps)
            .setBitrate(prefs.bitrate)
            .setEnableSops(prefs.enableSops)
            .setAudioConfiguration(prefs.audioConfiguration)
            .setSupportedVideoFormats(getSupportedVideoFormats(prefs))
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setClientRefreshRateX100(prefs.fps * 100)
            .build()
        
            connection = NvConnection(
                context,
                computerDetails,
                0, // httpsPort (0 means use default)
                uniqueId,
                streamConfig,
                cryptoProvider,
                null // serverCert (null means use default)
            )
            
            connection?.start(audioRenderer, decoderRenderer, this)
        }
    }

    /**
     * Stop the current streaming session and clean up resources.
     */
    fun stopStream() {
        executor.execute {
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
                var formats = MoonBridge.VIDEO_FORMAT_H264
                formats = formats or MoonBridge.VIDEO_FORMAT_H265
                if (prefs.enableHdr) {
                    formats = formats or MoonBridge.VIDEO_FORMAT_H265_MAIN10
                }
                formats
            }
        }
    }

    // NvConnectionListener implementation
    override fun stageStarting(stageName: String) {
        onStatusUpdate?.invoke("Starting: $stageName", false)
    }

    override fun stageComplete(stageName: String) {
        onStatusUpdate?.invoke("Completed: $stageName", false)
    }

    override fun stageFailed(stageName: String, portFlags: Int, errorCode: Int) {
        isConnected = false
        onStatusUpdate?.invoke("Failed: $stageName (error: $errorCode)", false)
        connection = null
    }

    override fun connectionStarted() {
        isConnected = true
        onStatusUpdate?.invoke("Connected", true)
    }

    override fun connectionTerminated(errorCode: Int) {
        isConnected = false
        val message = if (errorCode == 0) "Disconnected" else "Connection terminated (error: $errorCode)"
        onStatusUpdate?.invoke(message, false)
        connection = null
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        val status = when (connectionStatus) {
            com.limelight.nvstream.jni.MoonBridge.CONN_STATUS_OKAY -> "Connection: Good"
            com.limelight.nvstream.jni.MoonBridge.CONN_STATUS_POOR -> "Connection: Poor"
            else -> "Connection: Unknown"
        }
        onStatusUpdate?.invoke(status, true)
    }

    override fun displayMessage(message: String) {
        onStatusUpdate?.invoke(message, isConnected)
    }

    override fun displayTransientMessage(message: String) {
        // Transient messages can be logged but don't need to update main status
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        // Controller rumble feedback
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        // Controller trigger rumble feedback
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray) {
        // HDR mode changed
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        // Controller motion sensor state changed
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        // Controller LED color changed
    }
}


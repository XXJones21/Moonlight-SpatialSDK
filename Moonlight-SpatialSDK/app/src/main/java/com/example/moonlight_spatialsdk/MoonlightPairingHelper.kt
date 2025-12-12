package com.example.moonlight_spatialsdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import java.util.concurrent.Executors

/**
 * Lightweight helper for pairing operations that doesn't require decoder/audio renderers.
 * Used by PancakeActivity to avoid creating unnecessary connection infrastructure.
 */
class MoonlightPairingHelper(
    private val context: Context
) {
    private val tag = "MoonlightPairingHelper"
    private val cryptoProvider: LimelightCryptoProvider = AndroidCryptoProvider(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ServerCapabilities(
        val codecModeSupport: Long,
        val maxLumaH264: Long,
        val maxLumaHEVC: Long,
        val supports4k: Boolean,
    )

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

    fun fetchAppList(
        host: String,
        port: Int,
        callback: (List<NvApp>?, String?) -> Unit
    ) {
        executor.execute {
            try {
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val appList = http.getAppList()
                postToMain { callback(appList, null) }
            } catch (e: Exception) {
                Log.e(tag, "fetchAppList error host=$host", e)
                postToMain { callback(null, "Failed to load app list: ${e.message}") }
            }
        }
    }

    fun fetchServerName(
        host: String,
        port: Int,
        callback: (String?) -> Unit
    ) {
        executor.execute {
            try {
                val computerDetails = ComputerDetails.AddressTuple(host, port)
                val uniqueId = IdentityStore.getOrCreateUniqueId(context)
                val serverCert = IdentityStore.loadServerCert(context, host)
                val http = NvHTTP(computerDetails, 0, uniqueId, serverCert, cryptoProvider)
                val computerDetailsObj = http.getComputerDetails(true)
                val serverName = computerDetailsObj.name?.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: host
                postToMain { callback(serverName) }
            } catch (e: Exception) {
                Log.e(tag, "fetchServerName error host=$host", e)
                postToMain { callback(null) }
            }
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}


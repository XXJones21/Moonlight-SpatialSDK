package com.example.moonlight_spatialsdk

import android.app.Activity
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.NativeDecoderRenderer
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration

/**
 * Callback interface for when actual stream resolution is detected.
 */
interface OnStreamResolutionDetected {
    fun onResolutionDetected(actualWidth: Int, actualHeight: Int)
}

/**
 * Wrapper around NativeDecoderRenderer that captures actual resolution during setup.
 */
private class ResolutionAwareDecoderRenderer(
    private val onSetupCallback: (Int, Int) -> Unit
) : NativeDecoderRenderer() {
    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        android.util.Log.i("ResolutionAwareDecoderRenderer", "setup intercepted: ${width}x${height} format=0x${Integer.toHexString(format)} fps=$redrawRate")
        val result = super.setup(format, width, height, redrawRate)
        if (result == 0) {
            // Setup successful, notify callback with actual resolution
            android.util.Log.i("ResolutionAwareDecoderRenderer", "Setup successful, notifying callback with resolution ${width}x${height}")
            onSetupCallback(width, height)
        } else {
            android.util.Log.w("ResolutionAwareDecoderRenderer", "Setup failed with code $result, not notifying callback")
        }
        return result
    }
}

/**
 * Bridges the Spatial panel Surface to Moonlight's native decoder path.
 * Assumes the Moonlight dependencies (JNI + prefs) are available on the classpath.
 */
class MoonlightPanelRenderer(
    private val activity: Activity,
    private val prefs: PreferenceConfiguration,
    private val crashListener: CrashListener,
) {
  private val decoderRenderer: NativeDecoderRenderer by lazy { 
    ResolutionAwareDecoderRenderer { width, height ->
      onDecoderSetup(width, height)
    }
  }
  private var surfaceTexture: SurfaceTexture? = null
  private var panelSurface: Surface? = null
  private var onResolutionDetected: OnStreamResolutionDetected? = null

  private fun applyDecoderColorConfig() {
    // #region agent log
    try {
      val logData = java.io.FileWriter("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", true).use { writer ->
        writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"MoonlightPanelRenderer.kt:25\",\"message\":\"applyDecoderColorConfig entry\",\"data\":{\"prefs.enableHdr\":${prefs.enableHdr},\"prefs.fullRange\":${prefs.fullRange}},\"timestamp\":${System.currentTimeMillis()}}\n")
      }
    } catch (e: Exception) {}
    // #endregion
    // Use preferences to determine color settings - this matches what we request in StreamConfiguration
    // Request FULL range BT709 for SDR, BT2020 for HDR
    val colorRange = if (prefs.fullRange) {
      MediaFormat.COLOR_RANGE_FULL
    } else {
      MediaFormat.COLOR_RANGE_LIMITED
    }
    
    val colorStandard = if (prefs.enableHdr) {
      MediaFormat.COLOR_STANDARD_BT2020
    } else {
      MediaFormat.COLOR_STANDARD_BT709
    }
    
    val colorTransfer = if (prefs.enableHdr) {
      MediaFormat.COLOR_TRANSFER_ST2084
    } else {
      MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    }
    
    val dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (prefs.enableHdr) {
        // HDR uses BT2020 PQ dataspace
        DataSpace.DATASPACE_BT2020_PQ
      } else {
        // SDR uses sRGB/BT709 dataspace
        DataSpace.DATASPACE_SRGB
      }
    } else {
      -1
    }
    
    // #region agent log
    try {
      val logData = java.io.FileWriter("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", true).use { writer ->
        writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"MoonlightPanelRenderer.kt:59\",\"message\":\"applyDecoderColorConfig before nativeDecoderSetColorConfig\",\"data\":{\"colorRange\":$colorRange,\"colorStandard\":$colorStandard,\"colorTransfer\":$colorTransfer,\"dataSpace\":$dataSpace,\"dataSpaceHex\":\"0x${Integer.toHexString(dataSpace)}\"},\"timestamp\":${System.currentTimeMillis()}}\n")
      }
    } catch (e: Exception) {}
    // #endregion
    
    android.util.Log.i("MoonlightPanelRenderer", "applyDecoderColorConfig: range=${if (prefs.fullRange) "FULL" else "LIMITED"} standard=${if (prefs.enableHdr) "BT2020" else "BT709"} transfer=${if (prefs.enableHdr) "ST2084" else "SDR_VIDEO"} dataspace=0x${Integer.toHexString(dataSpace)}")
    MoonBridge.nativeDecoderSetColorConfig(colorRange, colorStandard, colorTransfer, dataSpace)
  }

  fun attachSurface(surface: Surface, useStereoscopicDuplication: Boolean = false) {
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED === stereoscopic=$useStereoscopicDuplication")
    android.util.Log.i("MoonlightPanelRenderer", "attachSurface called - setting render target")
    applyDecoderColorConfig()
    
    panelSurface = surface
    
    if (useStereoscopicDuplication) {
      // For stereoscopic mode, create SurfaceTexture to get frames as OpenGL textures
      // MediaCodec will render to SurfaceTexture's Surface, then we duplicate frames to panel Surface
      // Create texture for SurfaceTexture (will be attached in native code)
      val textureIds = IntArray(1)
      android.opengl.GLES20.glGenTextures(1, textureIds, 0)
      val textureId = textureIds[0]
      
      surfaceTexture = SurfaceTexture(textureId).apply {
        setDefaultBufferSize(prefs.width, prefs.height)
        setOnFrameAvailableListener {
          // Frame available - native code will handle duplication in output loop
        }
      }
      val surfaceTextureSurface = Surface(surfaceTexture!!)
      
      android.util.Log.i("MoonlightPanelRenderer", "Stereoscopic mode: Created SurfaceTexture with texture ID=$textureId for frame duplication")
      android.util.Log.i("MoonlightPanelRenderer", "MediaCodec will render to SurfaceTexture, frames will be duplicated to panel Surface")
      
      // Set SurfaceTexture in native code for texture access (pass texture ID explicitly)
      MoonBridge.nativeDecoderSetSurfaceTexture(surfaceTexture, textureId)
      
      // Set panel Surface in native code for OpenGL rendering target
      MoonBridge.nativeDecoderSetPanelSurface(surface)
      
      // MediaCodec renders to SurfaceTexture's Surface
      val holder = LegacySurfaceHolderAdapter(surfaceTextureSurface)
      decoderRenderer.setRenderTarget(holder)
    } else {
      // Normal mode: MediaCodec renders directly to panel Surface
      val holder = LegacySurfaceHolderAdapter(surface)
      decoderRenderer.setRenderTarget(holder)
    }
    
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_COMPLETED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_COMPLETED ===")
    android.util.Log.i("MoonlightPanelRenderer", "attachSurface completed - render target set")
  }

  fun preConfigureDecoder() {
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_PRECONFIGURE_DECODER_CALLED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_PRECONFIGURE_DECODER_CALLED ===")
    // Only push color config early; let the native bridge perform the actual decoder setup
    // when the stream is negotiated to avoid double setup.
    applyDecoderColorConfig()
    System.out.println("=== PRECONFIGURE_DECODER_RESULT=SKIPPED_SETUP ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== PRECONFIGURE_DECODER_RESULT=SKIPPED_SETUP ===")
  }

  fun getDecoder(): VideoDecoderRenderer = decoderRenderer

  /**
   * Set callback to be notified when actual stream resolution is detected.
   */
  fun setOnStreamResolutionDetected(callback: OnStreamResolutionDetected?) {
    this.onResolutionDetected = callback
    android.util.Log.i("MoonlightPanelRenderer", "setOnStreamResolutionDetected: callback ${if (callback != null) "set" else "cleared"}")
  }

  /**
   * Called when decoder setup completes with actual resolution.
   * This is called from NativeDecoderRenderer when setup() completes.
   */
  fun onDecoderSetup(width: Int, height: Int) {
    android.util.Log.i("MoonlightPanelRenderer", "onDecoderSetup: actual resolution detected: ${width}x${height}")
    onResolutionDetected?.onResolutionDetected(width, height)
  }
}


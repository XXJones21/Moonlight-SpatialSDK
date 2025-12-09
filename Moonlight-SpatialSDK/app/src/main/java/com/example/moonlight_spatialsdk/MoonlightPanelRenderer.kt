package com.example.moonlight_spatialsdk

import android.app.Activity
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
 * Bridges the Spatial panel Surface to Moonlight's native decoder path.
 * Assumes the Moonlight dependencies (JNI + prefs) are available on the classpath.
 */
class MoonlightPanelRenderer(
    private val activity: Activity,
    private val prefs: PreferenceConfiguration,
    private val crashListener: CrashListener,
) {
  private val decoderRenderer: NativeDecoderRenderer by lazy { NativeDecoderRenderer() }

  private fun applyDecoderColorConfig() {
    // Use server-announced stream defaults: SDR Rec.709 full range (Sunshine announce).
    val colorRange = MediaFormat.COLOR_RANGE_FULL
    val colorStandard = MediaFormat.COLOR_STANDARD_BT709
    val colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    val dataSpace =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          // Request full-range sRGB/BT709; V0_SRGB is not in older SDKs, so use DATASPACE_SRGB.
          DataSpace.DATASPACE_SRGB
        } else {
          -1
        }
    MoonBridge.nativeDecoderSetColorConfig(colorRange, colorStandard, colorTransfer, dataSpace)
  }

  fun attachSurface(surface: Surface) {
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED ===")
    android.util.Log.i("MoonlightPanelRenderer", "attachSurface called - setting render target")
    applyDecoderColorConfig()
    val holder = LegacySurfaceHolderAdapter(surface)
    decoderRenderer.setRenderTarget(holder)
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
}


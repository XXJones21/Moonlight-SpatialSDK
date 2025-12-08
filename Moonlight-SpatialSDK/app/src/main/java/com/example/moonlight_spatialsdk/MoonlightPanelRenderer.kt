package com.example.moonlight_spatialsdk

import android.app.Activity
import android.view.Surface
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration

/**
 * Bridges the Spatial panel Surface to Moonlight's MediaCodecDecoderRenderer.
 * Assumes the Moonlight dependencies (JNI + prefs) are available on the classpath.
 */
class MoonlightPanelRenderer(
    private val activity: Activity,
    private val prefs: PreferenceConfiguration,
    private val crashListener: CrashListener,
    private val perfOverlayListener: PerfOverlayListener = PerfOverlayListener { _ -> },
    private val consecutiveCrashCount: Int = 0,
    private val meteredData: Boolean = false,
    private val requestedHdr: Boolean = false,
    private val glRenderer: String = "spatial-panel",
) {
  private val decoderRenderer: MediaCodecDecoderRenderer by lazy {
    MediaCodecDecoderRenderer(
        activity,
        prefs,
        crashListener,
        consecutiveCrashCount,
        meteredData,
        requestedHdr,
        glRenderer,
        perfOverlayListener,
    )
  }

  fun attachSurface(surface: Surface) {
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_CALLED ===")
    android.util.Log.i("MoonlightPanelRenderer", "attachSurface called - setting render target")
    val holder = LegacySurfaceHolderAdapter(surface)
    decoderRenderer.setRenderTarget(holder)
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_COMPLETED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_ATTACH_SURFACE_COMPLETED ===")
    android.util.Log.i("MoonlightPanelRenderer", "attachSurface completed - render target set")
  }

  fun preConfigureDecoder() {
    System.out.println("=== MOONLIGHT_PANEL_RENDERER_PRECONFIGURE_DECODER_CALLED ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== MOONLIGHT_PANEL_RENDERER_PRECONFIGURE_DECODER_CALLED ===")
    val format = when (prefs.videoFormat) {
      PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
      PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
      PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
      PreferenceConfiguration.FormatOption.AUTO -> MoonBridge.VIDEO_FORMAT_H265
    }
    System.out.println("=== PRECONFIGURE_DECODER format=$format width=${prefs.width} height=${prefs.height} fps=${prefs.fps} ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== PRECONFIGURE_DECODER format=$format width=${prefs.width} height=${prefs.height} fps=${prefs.fps} ===")
    val result = decoderRenderer.setup(format, prefs.width, prefs.height, prefs.fps)
    System.out.println("=== PRECONFIGURE_DECODER_RESULT=$result ===")
    android.util.Log.e("MoonlightPanelRenderer", "=== PRECONFIGURE_DECODER_RESULT=$result ===")
  }

  fun getDecoder(): MediaCodecDecoderRenderer = decoderRenderer
}


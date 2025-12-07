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
    val holder = LegacySurfaceHolderAdapter(surface)
    decoderRenderer.setRenderTarget(holder)
  }

  fun preConfigureDecoder() {
    val format = when (prefs.videoFormat) {
      PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
      PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
      PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
      PreferenceConfiguration.FormatOption.AUTO -> MoonBridge.VIDEO_FORMAT_H265
    }
    decoderRenderer.setup(format, prefs.width, prefs.height, prefs.fps)
  }

  fun getDecoder(): MediaCodecDecoderRenderer = decoderRenderer
}


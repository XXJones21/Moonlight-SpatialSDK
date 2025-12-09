package com.example.moonlight_spatialsdk

import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration

data class VideoStreamParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: Int,
    val colorSpace: Int,
    val colorRange: Int,
    val bitDepth: Int,
    val hdr: Boolean,
) {
  companion object {
    fun fromPrefs(prefs: PreferenceConfiguration): VideoStreamParams {
      val format =
          when (prefs.videoFormat) {
            PreferenceConfiguration.FormatOption.FORCE_H264 -> MoonBridge.VIDEO_FORMAT_H264
            PreferenceConfiguration.FormatOption.FORCE_HEVC -> MoonBridge.VIDEO_FORMAT_H265
            PreferenceConfiguration.FormatOption.FORCE_AV1 -> MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            PreferenceConfiguration.FormatOption.AUTO -> MoonBridge.VIDEO_FORMAT_H265
          }

      val hdrEnabled = prefs.enableHdr
      val colorSpace =
          if (hdrEnabled) MoonBridge.COLORSPACE_REC_2020 else MoonBridge.COLORSPACE_REC_709
      val colorRange =
          if (prefs.fullRange) MoonBridge.COLOR_RANGE_FULL else MoonBridge.COLOR_RANGE_LIMITED
      val bitDepth = if (hdrEnabled) 10 else 8

      return VideoStreamParams(
          width = prefs.width,
          height = prefs.height,
          fps = prefs.fps,
          format = format,
          colorSpace = colorSpace,
          colorRange = colorRange,
          bitDepth = bitDepth,
          hdr = hdrEnabled,
      )
    }
  }
}



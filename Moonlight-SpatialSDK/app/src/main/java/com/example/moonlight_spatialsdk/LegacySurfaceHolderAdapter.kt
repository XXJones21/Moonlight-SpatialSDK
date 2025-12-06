package com.example.moonlight_spatialsdk

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin adapter to present a Surface as a SurfaceHolder for Moonlight's MediaCodecDecoderRenderer.
 * Only the methods used by Moonlight are implemented; others are no-ops.
 */
class LegacySurfaceHolderAdapter(private val surface: Surface) : SurfaceHolder {
  private val valid = AtomicBoolean(true)

  override fun getSurface(): Surface = surface

  override fun getSurfaceFrame(): Rect = Rect() // Not used downstream

  override fun lockCanvas(): Canvas {
    return surface.lockCanvas(null)
  }

  override fun lockCanvas(dirty: Rect?): Canvas {
    return surface.lockCanvas(dirty)
  }

  override fun unlockCanvasAndPost(canvas: Canvas) {
    surface.unlockCanvasAndPost(canvas)
  }

  override fun addCallback(callback: SurfaceHolder.Callback?) {
    // No-op: not required for direct Surface usage
  }

  override fun removeCallback(callback: SurfaceHolder.Callback?) {
    // No-op
  }

  override fun setType(type: Int) {
    // Deprecated/no-op
  }

  override fun setFixedSize(width: Int, height: Int) {
    // No-op
  }

  override fun setSizeFromLayout() {
    // No-op
  }

  override fun setFormat(format: Int) {
    // No-op
  }

  override fun setKeepScreenOn(keepScreenOn: Boolean) {
    // No-op
  }

  override fun isCreating(): Boolean = !valid.get()
}


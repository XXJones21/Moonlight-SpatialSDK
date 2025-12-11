package com.limelight.binding.video;

import android.view.SurfaceHolder;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

public class NativeDecoderRenderer extends VideoDecoderRenderer {
    private SurfaceHolder renderTarget;

    public void setRenderTarget(SurfaceHolder holder) {
        renderTarget = holder;
        if (holder != null) {
            android.util.Log.i("NativeDecoderRenderer", "setRenderTarget: surface set");
            MoonBridge.nativeDecoderSetSurface(holder.getSurface());
        } else {
            android.util.Log.i("NativeDecoderRenderer", "setRenderTarget: surface cleared");
            MoonBridge.nativeDecoderSetSurface(null);
        }
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        android.util.Log.i("NativeDecoderRenderer", "setup: fmt=" + format + " " + width + "x" + height + " fps=" + redrawRate + " surface=" + (renderTarget != null));
        if (renderTarget == null) {
            return -1;
        }
        MoonBridge.nativeDecoderSetSurface(renderTarget.getSurface());
        return MoonBridge.nativeDecoderSetup(format, width, height, redrawRate);
    }

    @Override
    public void start() {
        android.util.Log.i("NativeDecoderRenderer", "start");
        MoonBridge.nativeDecoderStart();
    }

    @Override
    public void stop() {
        android.util.Log.i("NativeDecoderRenderer", "stop");
        MoonBridge.nativeDecoderStop();
    }

    @Override
    public void cleanup() {
        android.util.Log.i("NativeDecoderRenderer", "cleanup");
        MoonBridge.nativeDecoderCleanup();
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        return MoonBridge.nativeDecoderSubmit(decodeUnitData, decodeUnitLength, decodeUnitType,
                frameNumber, frameType, frameHostProcessingLatency, receiveTimeMs, enqueueTimeMs);
    }

    @Override
    public int getCapabilities() {
        return 0;
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        // #region agent log
        try {
            java.io.FileWriter writer = new java.io.FileWriter("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", true);
            writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"NativeDecoderRenderer.java:64\",\"message\":\"setHdrMode called\",\"data\":{\"enabled\":" + enabled + ",\"hdrMetadata\":\"" + (hdrMetadata != null ? "present(" + hdrMetadata.length + " bytes)" : "null") + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n");
            writer.close();
        } catch (Exception e) {}
        // #endregion
        MoonBridge.nativeDecoderSetHdrMode(enabled, hdrMetadata);
    }
}


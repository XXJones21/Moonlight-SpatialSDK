package com.limelight.binding.video;

import android.view.SurfaceHolder;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

public class NativeDecoderRenderer extends VideoDecoderRenderer {
    private SurfaceHolder renderTarget;

    public void setRenderTarget(SurfaceHolder holder) {
        renderTarget = holder;
        if (holder != null) {
            MoonBridge.nativeDecoderSetSurface(holder.getSurface());
        } else {
            MoonBridge.nativeDecoderSetSurface(null);
        }
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        if (renderTarget == null) {
            return -1;
        }
        MoonBridge.nativeDecoderSetSurface(renderTarget.getSurface());
        return MoonBridge.nativeDecoderSetup(format, width, height, redrawRate);
    }

    @Override
    public void start() {
        MoonBridge.nativeDecoderStart();
    }

    @Override
    public void stop() {
        MoonBridge.nativeDecoderStop();
    }

    @Override
    public void cleanup() {
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
        // No-op for now
    }
}


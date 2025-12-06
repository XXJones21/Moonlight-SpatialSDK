package com.limelight.binding.input.capture;

import android.app.Activity;

import com.example.moonlight_spatialsdk.BuildConfig;
import com.limelight.LimeLog;
import com.example.moonlight_spatialsdk.R;
import com.limelight.binding.input.evdev.EvdevCaptureProviderShim;
import com.limelight.binding.input.evdev.EvdevListener;

public class InputCaptureManager {
    public static InputCaptureProvider getInputCaptureProvider(Activity activity, EvdevListener rootListener) {
        // In Spatial SDK, we don't have a traditional SurfaceView - Surface is provided by panel system
        // Try to find the surface view, but fall back to null if not available
        android.view.View surfaceView = null;
        try {
            int surfaceViewId = activity.getResources().getIdentifier("surfaceView", "id", activity.getPackageName());
            if (surfaceViewId != 0) {
                surfaceView = activity.findViewById(surfaceViewId);
            }
        } catch (Exception e) {
            // R.id.surfaceView may not exist in Spatial SDK context
        }
        
        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported() && surfaceView != null) {
            LimeLog.info("Using Android O+ native mouse capture");
            return new AndroidNativePointerCaptureProvider(activity, surfaceView);
        }
        // LineageOS implemented broken NVIDIA capture extensions, so avoid using them on root builds.
        // See https://github.com/LineageOS/android_frameworks_base/commit/d304f478a023430f4712dbdc3ee69d9ad02cebd3
        else if (ShieldCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using NVIDIA mouse capture extension");
            return new ShieldCaptureProvider(activity);
        }
        else if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            LimeLog.info("Using Evdev mouse capture");
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener);
        }
        else if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported() && surfaceView != null) {
            // Android N's native capture can't capture over system UI elements
            // so we want to only use it if there's no other option.
            LimeLog.info("Using Android N+ pointer hiding");
            return new AndroidPointerIconCaptureProvider(activity, surfaceView);
        }
        else {
            LimeLog.info("Mouse capture not available (no SurfaceView in Spatial SDK context)");
            return new NullCaptureProvider();
        }
    }
}

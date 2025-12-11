#include "native_decoder.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <stdio.h>

#include <Limelight.h>

#define LOG_TAG "NativeDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Fallbacks for older NDKs that don't expose color enums
#ifndef AMEDIAFORMAT_COLOR_RANGE_FULL
#define AMEDIAFORMAT_COLOR_RANGE_FULL 1
#endif
#ifndef AMEDIAFORMAT_COLOR_STANDARD_BT709
#define AMEDIAFORMAT_COLOR_STANDARD_BT709 1
#endif
#ifndef AMEDIAFORMAT_COLOR_STANDARD_BT601_NTSC
#define AMEDIAFORMAT_COLOR_STANDARD_BT601_NTSC 2
#endif
#ifndef AMEDIAFORMAT_COLOR_STANDARD_BT2020
#define AMEDIAFORMAT_COLOR_STANDARD_BT2020 6
#endif
#ifndef AMEDIAFORMAT_COLOR_TRANSFER_SRGB
#define AMEDIAFORMAT_COLOR_TRANSFER_SRGB 1
#endif
#ifndef AMEDIAFORMAT_COLOR_TRANSFER_SDR_VIDEO
#define AMEDIAFORMAT_COLOR_TRANSFER_SDR_VIDEO 3
#endif
#ifndef AMEDIAFORMAT_COLOR_TRANSFER_ST2084
#define AMEDIAFORMAT_COLOR_TRANSFER_ST2084 6
#endif
#ifndef HAL_DATASPACE_V0_JFIF
// JPEG full range BT.601 dataspace (deprecated constants but still honored by ANativeWindow)
#define HAL_DATASPACE_V0_JFIF 0x101
#endif

static ANativeWindow* g_window = NULL;
static AMediaCodec* g_codec = NULL;
static AMediaFormat* g_format = NULL;
static pthread_t g_outputThread;
static volatile bool g_outputRunning = false;
static volatile bool g_started = false;
static int g_width = 0;
static int g_height = 0;
static int g_fps = 0;
static int g_videoFormat = 0;
static int64_t g_lastPtsUs = 0;
static bool g_hdrEnabled = false;
static uint8_t g_hdrStaticInfo[64];
static size_t g_hdrStaticInfoLen = 0;
static int g_colorRange = -1;
static int g_colorStandard = -1;
static int g_colorTransfer = -1;
static int g_dataspace = -1;
static bool g_codec_configured = false;
static bool g_lastHdrEnabled = false;
static char g_decoderName[256] = {0};
static bool g_isQtiDecoder = false;

// Phase 4: Decoder state tracking for error handling and recovery
typedef enum {
    DECODER_STATE_UNINITIALIZED,
    DECODER_STATE_CREATED,
    DECODER_STATE_CONFIGURED,
    DECODER_STATE_STARTED,
    DECODER_STATE_ERROR,
    DECODER_STATE_STOPPED
} decoder_state_t;

static decoder_state_t g_decoderState = DECODER_STATE_UNINITIALIZED;
static int g_errorRecoveryAttempts = 0;
static const int MAX_RECOVERY_ATTEMPTS = 3;

static const char* mime_from_format(int videoFormat) {
    if ((videoFormat & 0x0F00) != 0) {
        return "video/hevc";
    }
    if ((videoFormat & 0xF000) != 0) {
        return "video/av01";
    }
    return "video/avc";
}

static const char* color_range_to_string(int range) {
    if (range == AMEDIAFORMAT_COLOR_RANGE_FULL) {
        return "FULL";
    }
    if (range == 2) { // COLOR_RANGE_LIMITED
        return "LIMITED";
    }
    return "UNKNOWN";
}

static const char* color_standard_to_string(int standard) {
    if (standard == AMEDIAFORMAT_COLOR_STANDARD_BT709) {
        return "BT709";
    }
    if (standard == AMEDIAFORMAT_COLOR_STANDARD_BT601_NTSC) {
        return "BT601_NTSC";
    }
    if (standard == AMEDIAFORMAT_COLOR_STANDARD_BT2020) {
        return "BT2020";
    }
    return "UNKNOWN";
}

static const char* color_transfer_to_string(int transfer) {
    if (transfer == AMEDIAFORMAT_COLOR_TRANSFER_SDR_VIDEO) {
        return "SDR_VIDEO";
    }
    if (transfer == AMEDIAFORMAT_COLOR_TRANSFER_ST2084) {
        return "ST2084";
    }
    return "UNKNOWN";
}

static void detect_decoder_info(const char* mime) {
    g_decoderName[0] = '\0';
    g_isQtiDecoder = false;
    
    // Phase 1: Use system properties to detect Qualcomm devices
    // This is a heuristic approach since AMediaCodecList is not available in all NDK versions
    // Qualcomm devices typically use QTI decoders (c2.qti.* or omx.qcom.*)
    char hardware[PROP_VALUE_MAX] = {0};
    char board_platform[PROP_VALUE_MAX] = {0};
    
    __system_property_get("ro.hardware", hardware);
    __system_property_get("ro.board.platform", board_platform);
    
    // Check if device is Qualcomm-based
    // Common Qualcomm indicators: qcom, msm, apq, sdm, sm, lahaina, taro, etc.
    bool isQualcommDevice = false;
    if (strlen(hardware) > 0) {
        const char* hwLower = hardware;
        isQualcommDevice = (strstr(hwLower, "qcom") != NULL) ||
                          (strstr(hwLower, "msm") != NULL) ||
                          (strstr(hwLower, "apq") != NULL);
    }
    if (!isQualcommDevice && strlen(board_platform) > 0) {
        const char* bpLower = board_platform;
        isQualcommDevice = (strstr(bpLower, "qcom") != NULL) ||
                          (strstr(bpLower, "msm") != NULL) ||
                          (strstr(bpLower, "apq") != NULL) ||
                          (strstr(bpLower, "sdm") != NULL) ||
                          (strstr(bpLower, "sm") != NULL) ||
                          (strstr(bpLower, "lahaina") != NULL) ||
                          (strstr(bpLower, "taro") != NULL);
    }
    
    g_isQtiDecoder = isQualcommDevice;
    
    // Store MIME type as decoder identifier (we can't get actual decoder name without AMediaCodecList)
    strncpy(g_decoderName, mime, sizeof(g_decoderName) - 1);
    g_decoderName[sizeof(g_decoderName) - 1] = '\0';
    
    if (g_isQtiDecoder) {
        LOGE("Detected Qualcomm device (hardware: %s, platform: %s) - assuming QTI decoder", 
             hardware[0] != '\0' ? hardware : "unknown",
             board_platform[0] != '\0' ? board_platform : "unknown");
    } else {
        LOGE("Non-Qualcomm device detected (hardware: %s, platform: %s) - assuming non-QTI decoder",
             hardware[0] != '\0' ? hardware : "unknown",
             board_platform[0] != '\0' ? board_platform : "unknown");
    }
}

static void log_color_format_details(const char* prefix, AMediaFormat* format) {
    if (format == NULL) {
        LOGE("%s: format is NULL", prefix);
        return;
    }

    int32_t colorRange = -1;
    int32_t colorStandard = -1;
    int32_t colorTransfer = -1;
    int32_t colorFormat = -1;
    size_t hdrStaticInfoSize = 0;
    uint8_t* hdrStaticInfo = NULL;

    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, &colorRange)) {
        LOGE("%s: COLOR_RANGE=%d (%s)", prefix, colorRange, color_range_to_string(colorRange));
    } else {
        LOGE("%s: COLOR_RANGE=not set", prefix);
    }

    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, &colorStandard)) {
        LOGE("%s: COLOR_STANDARD=%d (%s)", prefix, colorStandard, color_standard_to_string(colorStandard));
    } else {
        LOGE("%s: COLOR_STANDARD=not set", prefix);
    }

    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &colorTransfer)) {
        LOGE("%s: COLOR_TRANSFER=%d (%s)", prefix, colorTransfer, color_transfer_to_string(colorTransfer));
    } else {
        LOGE("%s: COLOR_TRANSFER=not set", prefix);
    }

    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat)) {
        LOGE("%s: COLOR_FORMAT=%d (0x%x)", prefix, colorFormat, colorFormat);
    } else {
        LOGE("%s: COLOR_FORMAT=not set", prefix);
    }

    void* hdrStaticInfoVoid = NULL;
    if (AMediaFormat_getBuffer(format, AMEDIAFORMAT_KEY_HDR_STATIC_INFO, &hdrStaticInfoVoid, &hdrStaticInfoSize)) {
        hdrStaticInfo = (uint8_t*)hdrStaticInfoVoid;
        LOGE("%s: HDR_STATIC_INFO present, size=%zu", prefix, hdrStaticInfoSize);
        if (hdrStaticInfoSize > 0 && hdrStaticInfo != NULL) {
            LOGE("%s: HDR_STATIC_INFO bytes: ", prefix);
            for (size_t i = 0; i < hdrStaticInfoSize && i < 32; i++) {
                LOGE("%s:   [%zu]=0x%02x", prefix, i, hdrStaticInfo[i]);
            }
        }
    } else {
        LOGE("%s: HDR_STATIC_INFO=not set", prefix);
    }
}

static void stop_output_thread() {
    if (g_outputRunning) {
        g_outputRunning = false;
        pthread_join(g_outputThread, NULL);
    }
}

// Phase 4: Error recovery functions
static bool attempt_flush_recovery() {
    if (g_codec == NULL || g_decoderState != DECODER_STATE_STARTED) {
        return false;
    }
    
    LOGE("Attempting flush recovery (decoder: %s, state: %d)", 
         g_decoderName[0] != '\0' ? g_decoderName : "unknown", g_decoderState);
    media_status_t status = AMediaCodec_flush(g_codec);
    if (status == AMEDIA_OK) {
        LOGE("Flush recovery successful");
        g_decoderState = DECODER_STATE_STARTED; // Reset to started after flush
        g_errorRecoveryAttempts = 0; // Reset recovery attempts on success
        return true;
    } else {
        LOGE("Flush recovery failed, status=%d", status);
        return false;
    }
}

static bool attempt_restart_recovery() {
    if (g_codec == NULL || g_format == NULL || g_window == NULL) {
        LOGE("Restart recovery failed - codec, format, or window is NULL");
        return false;
    }
    
    LOGE("Attempting restart recovery (decoder: %s, attempts: %d/%d)", 
         g_decoderName[0] != '\0' ? g_decoderName : "unknown", 
         g_errorRecoveryAttempts + 1, MAX_RECOVERY_ATTEMPTS);
    
    // Stop the decoder first
    if (g_started && g_codec != NULL) {
        AMediaCodec_stop(g_codec);
        g_started = false;
    }
    
    // Reconfigure and restart
    media_status_t status = AMediaCodec_configure(g_codec, g_format, g_window, NULL, 0);
    if (status == AMEDIA_OK) {
        status = AMediaCodec_start(g_codec);
        if (status == AMEDIA_OK) {
            g_started = true;
            g_decoderState = DECODER_STATE_STARTED;
            g_errorRecoveryAttempts = 0; // Reset on success
            LOGE("Restart recovery successful");
            return true;
        } else {
            LOGE("Phase 4: Restart recovery failed at start, status=%d (decoder: %s)", 
                 status, g_decoderName[0] != '\0' ? g_decoderName : "unknown");
        }
    } else {
        LOGE("Phase 4: Restart recovery failed at configure, status=%d (decoder: %s)", 
             status, g_decoderName[0] != '\0' ? g_decoderName : "unknown");
    }
    
    g_decoderState = DECODER_STATE_ERROR;
    g_errorRecoveryAttempts++;
    return false;
}

static void release_codec() {
    stop_output_thread();

    if (g_started && g_codec != NULL) {
        AMediaCodec_stop(g_codec);
    }
    g_started = false;
    g_codec_configured = false;
    g_decoderState = DECODER_STATE_UNINITIALIZED;
    g_errorRecoveryAttempts = 0;

    if (g_codec != NULL) {
        AMediaCodec_delete(g_codec);
        g_codec = NULL;
    }

    if (g_format != NULL) {
        AMediaFormat_delete(g_format);
        g_format = NULL;
    }
}

static void release_window() {
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
}

static void* output_loop(void* context) {
    (void)context;

    AMediaCodecBufferInfo info;
    while (g_outputRunning) {
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(g_codec, &info, 10000);
        if (idx >= 0) {
            AMediaCodec_releaseOutputBuffer(g_codec, idx, true);
        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            continue;
        }
    }

    return NULL;
}

#ifndef HAL_DATASPACE_V0_SRGB
// SRGB dataspace constant (0x143 = HAL_DATASPACE_V0_SRGB)
#define HAL_DATASPACE_V0_SRGB 0x143
#endif

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetSurface(JNIEnv* env, jclass clazz, jobject surface) {
    (void)clazz;
    // Use both LOGE and __android_log_print directly to ensure visibility
    __android_log_print(ANDROID_LOG_ERROR, "NativeDecoder", "=== nativeDecoderSetSurface called ===");
    __android_log_print(ANDROID_LOG_ERROR, "NativeDecoder", "  Surface: %s", surface != NULL ? "provided" : "NULL");
    LOGE("=== nativeDecoderSetSurface called ===");
    LOGE("  Surface: %s", surface != NULL ? "provided" : "NULL");
    release_window();
    if (surface != NULL) {
        g_window = ANativeWindow_fromSurface(env, surface);
        if (g_window != NULL) {
            if (g_dataspace >= 0) {
                ANativeWindow_setBuffersDataSpace(g_window, g_dataspace);
                LOGE("  Applied dataspace to window: 0x%x", g_dataspace);
                LOGE("  Window dataspace set successfully (will be updated in setup if HDR state differs)");
            } else {
                // Hint the target dataspace to full-range BT.601 to match Sunshine's SDR Rec.601 JPEG signaling
                ANativeWindow_setBuffersDataSpace(g_window, HAL_DATASPACE_V0_JFIF);
                LOGE("  No dataspace provided, using fallback: HAL_DATASPACE_V0_JFIF (0x%x)", HAL_DATASPACE_V0_JFIF);
            }
        } else {
            LOGE("  ERROR: ANativeWindow_fromSurface returned NULL");
        }
    }
    LOGE("=== nativeDecoderSetSurface completed ===");
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetColorConfig(JNIEnv* env, jclass clazz, jint colorRange, jint colorStandard, jint colorTransfer, jint dataspace) {
    (void)env;
    (void)clazz;
    // #region agent log
    FILE* logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"native_decoder.c:229\",\"message\":\"nativeDecoderSetColorConfig entry\",\"data\":{\"colorRange\":%d,\"colorStandard\":%d,\"colorTransfer\":%d,\"dataspace\":%d,\"dataspaceHex\":\"0x%x\"},\"timestamp\":%lld}\n",
                colorRange, colorStandard, colorTransfer, dataspace, dataspace, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion
    LOGE("=== nativeDecoderSetColorConfig called ===");
    LOGE("  Input params: range=%d, standard=%d, transfer=%d, dataspace=0x%x",
         colorRange, colorStandard, colorTransfer, dataspace);
    LOGE("  Range: %d (%s)", colorRange, color_range_to_string(colorRange));
    LOGE("  Standard: %d (%s)", colorStandard, color_standard_to_string(colorStandard));
    LOGE("  Transfer: %d (%s)", colorTransfer, color_transfer_to_string(colorTransfer));
    LOGE("  Dataspace: 0x%x", dataspace);
    g_colorRange = colorRange;
    g_colorStandard = colorStandard;
    g_colorTransfer = colorTransfer;
    g_dataspace = dataspace;
    // #region agent log
    logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"native_decoder.c:243\",\"message\":\"nativeDecoderSetColorConfig exit\",\"data\":{\"g_colorRange\":%d,\"g_colorStandard\":%d,\"g_colorTransfer\":%d,\"g_dataspace\":%d},\"timestamp\":%lld}\n",
                g_colorRange, g_colorStandard, g_colorTransfer, g_dataspace, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion
    LOGE("=== nativeDecoderSetColorConfig completed ===");
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetup(JNIEnv* env, jclass clazz, jint videoFormat, jint width, jint height, jint fps) {
    (void)clazz;
    // Force log visibility - use direct __android_log_print
    __android_log_print(ANDROID_LOG_ERROR, "NativeDecoder", "=== NATIVE_DECODER_SETUP_CALLED === format=0x%x %dx%d fps=%d", videoFormat, width, height, fps);
    // #region agent log
    FILE* logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"native_decoder.c:265\",\"message\":\"nativeDecoderSetup entry\",\"data\":{\"g_hdrEnabled\":%s,\"g_hdrStaticInfoLen\":%zu,\"g_colorRange\":%d,\"g_colorStandard\":%d,\"g_colorTransfer\":%d,\"g_dataspace\":%d},\"timestamp\":%lld}\n",
                g_hdrEnabled ? "true" : "false", g_hdrStaticInfoLen, g_colorRange, g_colorStandard, g_colorTransfer, g_dataspace, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion

    release_codec();

    g_videoFormat = videoFormat;
    g_width = width;
    g_height = height;
    g_fps = fps;
    g_lastPtsUs = 0;

    // Early HDR inference: Check if format includes 10-bit mask (VIDEO_FORMAT_MASK_10BIT = 0x2200)
    // If format suggests HDR but HDR mode is not enabled, infer HDR from format negotiation
    bool isHdrFormat = (videoFormat & 0x2200) != 0;
    if (isHdrFormat && !g_hdrEnabled) {
        LOGE("Early HDR inference: Format includes 10-bit mask (0x%x), enabling HDR mode", videoFormat);
        g_hdrEnabled = true;
        // Note: HDR static info may not be available yet, but format negotiation indicates HDR
    }
    
    // Initialize last HDR state to current state for change detection
    g_lastHdrEnabled = g_hdrEnabled;

    if (g_window == NULL) {
        LOGE("nativeDecoderSetup failed: surface is null");
        return -1;
    }

    if (g_colorRange < 0 || g_colorStandard < 0 || g_colorTransfer < 0) {
        LOGE("nativeDecoderSetup: color config missing (range=%d standard=%d transfer=%d) - aborting to avoid silent fallback",
             g_colorRange, g_colorStandard, g_colorTransfer);
        return -2;
    }
    if (g_dataspace < 0) {
        LOGE("nativeDecoderSetup: dataspace not provided - aborting to avoid silent fallback");
        return -3;
    }

    const char* mime = mime_from_format(videoFormat);
    
    LOGE("=== NATIVE_DECODER_SETUP_COLOR_DEBUG_START ===");
    LOGE("Video format: 0x%x, MIME: %s", videoFormat, mime);
    LOGE("Resolution: %dx%d, FPS: %d", width, height, fps);
    LOGE("HDR enabled: %d, HDR static info length: %zu", g_hdrEnabled, g_hdrStaticInfoLen);
    
    // Log current color configuration state
    LOGE("Color config state - Range: %d (%s), Standard: %d (%s), Transfer: %d (%s), Dataspace: 0x%x",
         g_colorRange, color_range_to_string(g_colorRange),
         g_colorStandard, color_standard_to_string(g_colorStandard),
         g_colorTransfer, color_transfer_to_string(g_colorTransfer),
         g_dataspace);
    
    // Log window dataspace and update it based on actual HDR state
    if (g_window != NULL) {
        // Update dataspace based on actual HDR state, not just color config
        // If HDR is not enabled, use SRGB dataspace for SDR content
        int effectiveDataspace = g_dataspace;
        if (!g_hdrEnabled && g_dataspace >= 0 && g_dataspace == 0x9c60000) {
            // HDR dataspace (BT2020_PQ) was set but HDR is not enabled - use SRGB instead
            effectiveDataspace = HAL_DATASPACE_V0_SRGB;
            ANativeWindow_setBuffersDataSpace(g_window, effectiveDataspace);
            LOGE("Window dataspace: HDR dataspace (0x%x) was set but HDR not enabled, updated to SRGB (0x%x)", g_dataspace, effectiveDataspace);
        } else if (g_dataspace >= 0) {
            LOGE("Window dataspace: 0x%x (set via ANativeWindow_setBuffersDataSpace)", g_dataspace);
        } else {
            // No dataspace was set, use SRGB for SDR
            effectiveDataspace = HAL_DATASPACE_V0_SRGB;
            ANativeWindow_setBuffersDataSpace(g_window, effectiveDataspace);
            LOGE("Window dataspace: No dataspace provided, using SRGB (0x%x) for SDR", effectiveDataspace);
        }
    }

    // Phase 2: Explicit decoder selection via JNI
    const char* decoderName = NULL;
    jstring jMime = (*env)->NewStringUTF(env, mime);
    if (jMime != NULL) {
        jmethodID findDecoderMethod = (*env)->GetStaticMethodID(env, clazz, "findBestDecoderForMime", "(Ljava/lang/String;)Ljava/lang/String;");
        if (findDecoderMethod != NULL) {
            jstring jDecoderName = (jstring)(*env)->CallStaticObjectMethod(env, clazz, findDecoderMethod, jMime);
            if (jDecoderName != NULL) {
                const char* nameStr = (*env)->GetStringUTFChars(env, jDecoderName, NULL);
                if (nameStr != NULL) {
                    strncpy(g_decoderName, nameStr, sizeof(g_decoderName) - 1);
                    g_decoderName[sizeof(g_decoderName) - 1] = '\0';
                    decoderName = g_decoderName;
                    (*env)->ReleaseStringUTFChars(env, jDecoderName, nameStr);
                    LOGE("Selected decoder via Java: %s", decoderName);
                }
                (*env)->DeleteLocalRef(env, jDecoderName);
            }
        }
        (*env)->DeleteLocalRef(env, jMime);
    }
    
    // Use explicit decoder name if available, otherwise fall back to createDecoderByType
    if (decoderName != NULL && strlen(decoderName) > 0) {
        g_codec = AMediaCodec_createCodecByName(decoderName);
        if (g_codec == NULL) {
            LOGE("Failed to create decoder by name '%s', falling back to createDecoderByType", decoderName);
            g_codec = AMediaCodec_createDecoderByType(mime);
        }
    } else {
        LOGE("Decoder selection via Java failed, using createDecoderByType");
        g_codec = AMediaCodec_createDecoderByType(mime);
    }
    
    if (g_codec == NULL) {
        LOGE("nativeDecoderSetup failed: decoder creation returned null (MIME: %s)", mime);
        LOGE("=== NATIVE_DECODER_SETUP_COLOR_DEBUG_END (FAILED) ===");
        g_decoderState = DECODER_STATE_ERROR;
        return -1;
    }
    
    // Phase 4: Update decoder state
    g_decoderState = DECODER_STATE_CREATED;

    // Update QTI detection based on actual decoder name
    if (decoderName != NULL && strlen(decoderName) > 0) {
        g_isQtiDecoder = (strncmp(decoderName, "c2.qti", 6) == 0) || 
                        (strncmp(decoderName, "omx.qcom", 8) == 0);
    } else {
        // Fallback to device-based detection if decoder name not available
        detect_decoder_info(mime);
    }
    LOGE("Decoder created for MIME: %s, name: %s, isQTI: %s", mime, g_decoderName[0] != '\0' ? g_decoderName : "unknown", g_isQtiDecoder ? "yes" : "no");

    g_format = AMediaFormat_new();
    AMediaFormat_setString(g_format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_HEIGHT, height);
    if (fps > 0) {
        AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
    }
    
    // Phase 3: Low latency and adaptive playback configuration
    if (decoderName != NULL && strlen(decoderName) > 0) {
        jstring jDecoderName = (*env)->NewStringUTF(env, decoderName);
        jstring jMimeForCaps = (*env)->NewStringUTF(env, mime);
        
        if (jDecoderName != NULL && jMimeForCaps != NULL) {
            // Check low latency support
            jmethodID supportsLowLatencyMethod = (*env)->GetStaticMethodID(env, clazz, "decoderSupportsLowLatency", "(Ljava/lang/String;Ljava/lang/String;)Z");
            if (supportsLowLatencyMethod != NULL) {
                jboolean supportsLowLatency = (*env)->CallStaticBooleanMethod(env, clazz, supportsLowLatencyMethod, jDecoderName, jMimeForCaps);
                if (supportsLowLatency) {
                    // Android 11+ official low latency option
                    AMediaFormat_setInt32(g_format, "low-latency", 1);
                    LOGE("Set low-latency=1 (Android 11+ official option)");
                }
            }
            
            // Set vendor-specific low latency options
            char sdk_version_str[PROP_VALUE_MAX] = {0};
            int deviceApiLevel = 0;
            if (__system_property_get("ro.build.version.sdk", sdk_version_str) > 0) {
                deviceApiLevel = atoi(sdk_version_str);
            } else {
                #ifdef __ANDROID_API__
                    deviceApiLevel = __ANDROID_API__;
                #else
                    deviceApiLevel = 24; // Assume API 24+ if unknown
                #endif
            }
            if (deviceApiLevel >= 26) { // Android O (API 26) for vendor extensions
                if (g_isQtiDecoder) {
                    // Qualcomm low latency options
                    AMediaFormat_setInt32(g_format, "vendor.qti-ext-dec-picture-order.enable", 1);
                    AMediaFormat_setInt32(g_format, "vendor.qti-ext-dec-low-latency.enable", 1);
                    LOGE("Set QTI low latency options");
                } else if (strncmp(decoderName, "c2.hisi", 7) == 0 || strncmp(decoderName, "omx.hisi", 8) == 0) {
                    // HiSilicon (Kirin) low latency options
                    AMediaFormat_setInt32(g_format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
                    AMediaFormat_setInt32(g_format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
                    LOGE("Set HiSilicon low latency options");
                } else if (strncmp(decoderName, "c2.exynos", 9) == 0 || strncmp(decoderName, "omx.Exynos", 10) == 0 || strncmp(decoderName, "omx.rtc", 7) == 0) {
                    // Exynos low latency option
                    AMediaFormat_setInt32(g_format, "vendor.rtc-ext-dec-low-latency.enable", 1);
                    LOGE("Set Exynos low latency option");
                } else if (strncmp(decoderName, "c2.amlogic", 10) == 0 || strncmp(decoderName, "omx.amlogic", 11) == 0) {
                    // Amlogic low latency option
                    AMediaFormat_setInt32(g_format, "vendor.low-latency.enable", 1);
                    LOGE("Set Amlogic low latency option");
                }
            }
            
            // Set max operating rate for Qualcomm decoders (Android M+)
            jmethodID supportsMaxOpRateMethod = (*env)->GetStaticMethodID(env, clazz, "decoderSupportsMaxOperatingRate", "(Ljava/lang/String;)Z");
            if (supportsMaxOpRateMethod != NULL && deviceApiLevel >= 23) { // Android M (API 23)
                jboolean supportsMaxOpRate = (*env)->CallStaticBooleanMethod(env, clazz, supportsMaxOpRateMethod, jDecoderName);
                if (supportsMaxOpRate) {
                    AMediaFormat_setInt32(g_format, "operating-rate", 32767); // Short.MAX_VALUE
                    LOGE("Set operating-rate=32767 for Qualcomm decoder");
                }
            }
            
            // Check adaptive playback support
            jmethodID supportsAdaptiveMethod = (*env)->GetStaticMethodID(env, clazz, "decoderSupportsAdaptivePlayback", "(Ljava/lang/String;Ljava/lang/String;)Z");
            if (supportsAdaptiveMethod != NULL) {
                jboolean supportsAdaptive = (*env)->CallStaticBooleanMethod(env, clazz, supportsAdaptiveMethod, jDecoderName, jMimeForCaps);
                if (supportsAdaptive) {
                    // Set max width/height for adaptive playback
                    AMediaFormat_setInt32(g_format, "max-width", width);
                    AMediaFormat_setInt32(g_format, "max-height", height);
                    LOGE("Set adaptive playback (max-width=%d, max-height=%d)", width, height);
                }
            }
            
            (*env)->DeleteLocalRef(env, jMimeForCaps);
            (*env)->DeleteLocalRef(env, jDecoderName);
        }
    }
    
    // #region agent log
    FILE* logFile1 = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile1) {
        fprintf(logFile1, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"B\",\"location\":\"native_decoder.c:314\",\"message\":\"Before setting color params in MediaFormat\",\"data\":{\"g_hdrEnabled\":%s,\"g_hdrStaticInfoLen\":%zu,\"g_colorRange\":%d,\"g_colorStandard\":%d,\"g_colorTransfer\":%d,\"g_dataspace\":%d,\"g_dataspaceHex\":\"0x%x\"},\"timestamp\":%lld}\n",
                g_hdrEnabled ? "true" : "false", g_hdrStaticInfoLen, g_colorRange, g_colorStandard, g_colorTransfer, g_dataspace, g_dataspace, (long long)time(NULL) * 1000);
        fclose(logFile1);
    }
    // #endregion
    // Log color parameters being set in format
    LOGE("Setting color parameters in MediaFormat:");
    // #region agent log
    FILE* logFile2 = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile2) {
        fprintf(logFile2, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"native_decoder.c:341\",\"message\":\"HDR check before setting color params\",\"data\":{\"g_hdrEnabled\":%s,\"g_hdrStaticInfoLen\":%zu,\"willUseHdrBranch\":%s},\"timestamp\":%lld}\n",
                g_hdrEnabled ? "true" : "false", g_hdrStaticInfoLen, (g_hdrEnabled && g_hdrStaticInfoLen > 0) ? "true" : "false", (long long)time(NULL) * 1000);
        fclose(logFile2);
    }
    // #endregion
    
    // Android 7.0 (API 24) adds color options to MediaFormat.
    // QTI decoders don't recognize MediaFormat color keys; skip them for QTI decoders.
    // Only set color keys if Android N+ and not QTI decoder (matching moonlight-android behavior).
    int deviceApiLevel = 0;
    char sdk_version_str[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.build.version.sdk", sdk_version_str) > 0) {
        deviceApiLevel = atoi(sdk_version_str);
    } else {
        // Fallback to compile-time API level if system property not available
        #ifdef __ANDROID_API__
            deviceApiLevel = __ANDROID_API__;
        #else
            deviceApiLevel = 24; // Assume API 24+ if unknown
        #endif
    }
    
    bool shouldSetColorKeys = (deviceApiLevel >= 24) && !g_isQtiDecoder;
    
    if (shouldSetColorKeys) {
        LOGE("  Setting color keys (Android N+, non-QTI decoder, API %d)", deviceApiLevel);
    } else {
        if (deviceApiLevel < 24) {
            LOGE("  Skipping color keys (Android < N, API %d)", deviceApiLevel);
        } else if (g_isQtiDecoder) {
            LOGE("  Skipping color keys (QTI decoder: %s)", g_decoderName);
        }
    }
    
    // Reuse isHdrFormat variable that was already calculated earlier
    if (g_hdrEnabled && (g_hdrStaticInfoLen > 0 || isHdrFormat)) {
        // HDR mode: Only set COLOR_RANGE and HDR_STATIC_INFO
        // Do NOT set COLOR_STANDARD and COLOR_TRANSFER - let decoder detect color transitions automatically
        // This matches moonlight-android's approach and works correctly with QTI decoders (c2.qti.*)
        // which don't recognize MediaFormat color keys and use C2 parameters instead
        if (shouldSetColorKeys) {
            AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_RANGE, AMEDIAFORMAT_COLOR_RANGE_FULL);
        }
        if (g_hdrStaticInfoLen > 0) {
            AMediaFormat_setBuffer(g_format, AMEDIAFORMAT_KEY_HDR_STATIC_INFO, g_hdrStaticInfo, g_hdrStaticInfoLen);
        }
        LOGE("  HDR mode: COLOR_RANGE=%s, COLOR_STANDARD and COLOR_TRANSFER not set (decoder will detect transitions)",
             shouldSetColorKeys ? "FULL (set)" : "not set (QTI/old Android)");
        LOGE("  HDR_STATIC_INFO: %zu bytes", g_hdrStaticInfoLen);
        if (g_hdrStaticInfoLen > 0) {
            LOGE("  HDR_STATIC_INFO content:");
            for (size_t i = 0; i < g_hdrStaticInfoLen && i < 32; i++) {
                LOGE("    [%zu]=0x%02x", i, g_hdrStaticInfo[i]);
            }
        }
    } else {
        // SDR mode: Use SDR color values (BT709/SRGB) regardless of what was set in color config
        // This ensures we don't use HDR color values when HDR is not actually enabled
        // The color config may have been set to HDR values based on preferences, but if HDR mode
        // is not enabled (no HDR metadata), we must use SDR values for correct color rendering.
        // Using SRGB transfer (1) as requested - matches SRGB dataspace (0x143) for display
        int sdrColorRange = g_colorRange; // Keep the range preference (FULL vs LIMITED)
        int sdrColorStandard = AMEDIAFORMAT_COLOR_STANDARD_BT709; // Always BT709 for SDR
        int sdrColorTransfer = AMEDIAFORMAT_COLOR_TRANSFER_SRGB; // Use SRGB transfer for SDR display
        
        // #region agent log
        logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
        if (logFile) {
            fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"native_decoder.c:356\",\"message\":\"Using SDR branch - overriding to SDR values\",\"data\":{\"originalColorRange\":%d,\"originalColorStandard\":%d,\"originalColorTransfer\":%d,\"sdrColorRange\":%d,\"sdrColorStandard\":%d,\"sdrColorTransfer\":%d},\"timestamp\":%lld}\n",
                    g_colorRange, g_colorStandard, g_colorTransfer, sdrColorRange, sdrColorStandard, sdrColorTransfer, (long long)time(NULL) * 1000);
            fclose(logFile);
        }
        // #endregion
        
        if (shouldSetColorKeys) {
            AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_RANGE, sdrColorRange);
            AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_STANDARD, sdrColorStandard);
            AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, sdrColorTransfer);
        }
        
        // Note: We don't set HDR_STATIC_INFO when HDR is disabled, which is correct.
        // Some decoders may include a default empty HDR_STATIC_INFO in output format,
        // but that's a decoder behavior we can't control. The important thing is we're
        // not setting it in the input format, and we're using correct SDR color values.
        LOGE("  SDR mode: COLOR_RANGE=%s, COLOR_STANDARD=%s, COLOR_TRANSFER=%s",
             shouldSetColorKeys ? (color_range_to_string(sdrColorRange)) : "not set (QTI/old Android)",
             shouldSetColorKeys ? (color_standard_to_string(sdrColorStandard)) : "not set (QTI/old Android)",
             shouldSetColorKeys ? (color_transfer_to_string(sdrColorTransfer)) : "not set (QTI/old Android)");
    }
    // #region agent log
    logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        int32_t setColorRange = -1, setColorStandard = -1, setColorTransfer = -1;
        AMediaFormat_getInt32(g_format, AMEDIAFORMAT_KEY_COLOR_RANGE, &setColorRange);
        AMediaFormat_getInt32(g_format, AMEDIAFORMAT_KEY_COLOR_STANDARD, &setColorStandard);
        AMediaFormat_getInt32(g_format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &setColorTransfer);
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"B\",\"location\":\"native_decoder.c:337\",\"message\":\"After setting color params in MediaFormat\",\"data\":{\"setColorRange\":%d,\"setColorStandard\":%d,\"setColorTransfer\":%d},\"timestamp\":%lld}\n",
                setColorRange, setColorStandard, setColorTransfer, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion

    media_status_t status = AMediaCodec_configure(g_codec, g_format, g_window, NULL, 0);
    if (status != AMEDIA_OK) {
        LOGE("nativeDecoderSetup failed: AMediaCodec_configure status=%d (decoder: %s, MIME: %s)", 
             status, g_decoderName[0] != '\0' ? g_decoderName : "unknown", mime);
        LOGE("=== NATIVE_DECODER_SETUP_COLOR_DEBUG_END (FAILED) ===");
        g_decoderState = DECODER_STATE_ERROR;
        release_codec();
        return -1;
    }
    
    // Phase 4: Update decoder state
    g_decoderState = DECODER_STATE_CONFIGURED;
    
    // Mark decoder as configured after successful setup
    g_codec_configured = true;
    
    // Re-apply dataspace to window after decoder configuration
    // Some decoders may override the dataspace during configure, so we need to set it again
    if (g_window != NULL && g_dataspace >= 0) {
        int effectiveDataspace = g_dataspace;
        // Ensure dataspace matches HDR state
        if (!g_hdrEnabled && g_dataspace == 0x9c60000) {
            effectiveDataspace = HAL_DATASPACE_V0_SRGB;
        }
        ANativeWindow_setBuffersDataSpace(g_window, effectiveDataspace);
        LOGE("Re-applied dataspace to window after decoder configure: 0x%x", effectiveDataspace);
    }

    // Log negotiated formats with detailed color information
    LOGE("--- Negotiated Input Format (after configure) ---");
    AMediaFormat* inFmt = AMediaCodec_getInputFormat(g_codec);
    if (inFmt) {
        const char* dump = AMediaFormat_toString(inFmt);
        LOGE("Input format string: %s", dump ? dump : "(null)");
        log_color_format_details("Input format", inFmt);
        AMediaFormat_delete(inFmt);
    } else {
        LOGE("Input format: NULL");
    }

    LOGE("--- Negotiated Output Format (after configure) ---");
    AMediaFormat* outFmt = AMediaCodec_getOutputFormat(g_codec);
    if (outFmt) {
        const char* dump = AMediaFormat_toString(outFmt);
        LOGE("Output format string: %s", dump ? dump : "(null)");
        log_color_format_details("Output format", outFmt);
        // #region agent log
        int32_t outColorFormat = -1;
        int32_t outColorRange = -1;
        int32_t outColorStandard = -1;
        int32_t outColorTransfer = -1;
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, &outColorFormat);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_COLOR_RANGE, &outColorRange);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_COLOR_STANDARD, &outColorStandard);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &outColorTransfer);
        logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
        if (logFile) {
            fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"E\",\"location\":\"native_decoder.c:398\",\"message\":\"Output format color values\",\"data\":{\"colorFormat\":%d,\"colorFormatHex\":\"0x%x\",\"colorRange\":%d,\"colorStandard\":%d,\"colorTransfer\":%d},\"timestamp\":%lld}\n",
                    outColorFormat, outColorFormat, outColorRange, outColorStandard, outColorTransfer, (long long)time(NULL) * 1000);
            fclose(logFile);
        }
        // #endregion
        AMediaFormat_delete(outFmt);
    } else {
        LOGE("Output format: NULL");
    }

    // Log configured format details
    LOGE("--- Configured Format (what we set) ---");
    log_color_format_details("Configured format", g_format);

    LOGE("nativeDecoderSetup complete - mime=%s size=%dx%d fps=%d hdr=%d hdrStatic=%zu",
         mime, width, height, fps, g_hdrEnabled, g_hdrStaticInfoLen);
    LOGE("Decoder setup summary - decoder: %s, state: %d, isQTI: %s, configured: %s",
         g_decoderName[0] != '\0' ? g_decoderName : "unknown", 
         g_decoderState,
         g_isQtiDecoder ? "yes" : "no",
         g_codec_configured ? "yes" : "no");
    LOGE("=== NATIVE_DECODER_SETUP_COLOR_DEBUG_END ===");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderStart(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    if (g_codec == NULL || g_started) {
        return;
    }

    media_status_t status = AMediaCodec_start(g_codec);
    if (status != AMEDIA_OK) {
        LOGE("nativeDecoderStart failed: AMediaCodec_start status=%d (decoder: %s, state: %d)", 
             status, g_decoderName[0] != '\0' ? g_decoderName : "unknown", g_decoderState);
        g_decoderState = DECODER_STATE_ERROR;
        return;
    }

    g_started = true;
    g_outputRunning = true;
    g_decoderState = DECODER_STATE_STARTED;
    LOGE("Decoder started successfully (decoder: %s)", g_decoderName[0] != '\0' ? g_decoderName : "unknown");
    pthread_create(&g_outputThread, NULL, output_loop, NULL);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderStop(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    stop_output_thread();

    if (g_started && g_codec != NULL) {
        AMediaCodec_stop(g_codec);
    }
    g_started = false;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderCleanup(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    release_codec();
    release_window();
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetHdrMode(JNIEnv* env, jclass clazz, jboolean enabled, jbyteArray hdrMetadata) {
    (void)clazz;
    // #region agent log
    FILE* logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        jsize metadataLen = (hdrMetadata != NULL) ? (*env)->GetArrayLength(env, hdrMetadata) : 0;
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"native_decoder.c:423\",\"message\":\"nativeDecoderSetHdrMode entry\",\"data\":{\"enabled\":%s,\"hdrMetadataLen\":%d},\"timestamp\":%lld}\n",
                (enabled == JNI_TRUE) ? "true" : "false", (int)metadataLen, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion
    LOGE("=== nativeDecoderSetHdrMode called ===");
    LOGE("  HDR enabled: %s", (enabled == JNI_TRUE) ? "true" : "false");
    
    bool newHdrEnabled = enabled == JNI_TRUE;
    bool hdrStateChanged = (g_lastHdrEnabled != newHdrEnabled);
    
    g_hdrEnabled = newHdrEnabled;
    g_hdrStaticInfoLen = 0;

    if (g_hdrEnabled && hdrMetadata != NULL) {
        jsize len = (*env)->GetArrayLength(env, hdrMetadata);
        LOGE("  HDR metadata array length: %d", len);
        if (len > 0 && (size_t)len <= sizeof(g_hdrStaticInfo)) {
            (*env)->GetByteArrayRegion(env, hdrMetadata, 0, len, (jbyte*)g_hdrStaticInfo);
            g_hdrStaticInfoLen = (size_t)len;
            LOGE("  HDR static info copied: %zu bytes", g_hdrStaticInfoLen);
            LOGE("  HDR static info content:");
            for (size_t i = 0; i < g_hdrStaticInfoLen && i < 32; i++) {
                LOGE("    [%zu]=0x%02x", i, g_hdrStaticInfo[i]);
            }
        } else {
            LOGE("  WARNING: HDR metadata length %d is invalid (max %zu)", len, sizeof(g_hdrStaticInfo));
        }
    } else {
        LOGE("  HDR metadata: %s", (hdrMetadata == NULL) ? "NULL" : "not provided");
    }
    
    // If decoder is already configured and HDR state changed, restart decoder
    if (g_codec_configured && hdrStateChanged) {
        LOGE("  HDR state changed (was %s, now %s) - decoder restart required", 
             g_lastHdrEnabled ? "enabled" : "disabled",
             g_hdrEnabled ? "enabled" : "disabled");
        LOGE("  Releasing decoder to trigger restart on next setup");
        release_codec();
        // Note: Decoder will be reconfigured on next nativeDecoderSetup() call
        // The bridge will call setup() again when it detects the decoder needs restart
    }
    
    g_lastHdrEnabled = g_hdrEnabled;
    // #region agent log
    logFile = fopen("d:\\Tools\\Moonlight-SpatialSDK\\.cursor\\debug.log", "a");
    if (logFile) {
        fprintf(logFile, "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"native_decoder.c:447\",\"message\":\"nativeDecoderSetHdrMode exit\",\"data\":{\"g_hdrEnabled\":%s,\"g_hdrStaticInfoLen\":%zu},\"timestamp\":%lld}\n",
                g_hdrEnabled ? "true" : "false", g_hdrStaticInfoLen, (long long)time(NULL) * 1000);
        fclose(logFile);
    }
    // #endregion
    LOGE("=== nativeDecoderSetHdrMode completed ===");
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSubmit(JNIEnv* env, jclass clazz, jbyteArray data, jint length, jint decodeUnitType, jint frameNumber, jint frameType, jchar frameHostProcessingLatency, jlong receiveTimeMs, jlong enqueueTimeMs) {
    (void)clazz;
    (void)frameHostProcessingLatency;
    (void)receiveTimeMs;
    (void)frameNumber;

    if (!g_started || g_codec == NULL) {
        LOGE("nativeDecoderSubmit: decoder not started or NULL (state: %d, decoder: %s)", 
             g_decoderState, g_decoderName[0] != '\0' ? g_decoderName : "unknown");
        return DR_NEED_IDR;
    }

    // Phase 4: Check decoder state and attempt recovery if in error state
    if (g_decoderState == DECODER_STATE_ERROR) {
        if (g_errorRecoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            LOGE("Decoder in error state, attempting recovery (attempt %d/%d)", 
                 g_errorRecoveryAttempts + 1, MAX_RECOVERY_ATTEMPTS);
            if (attempt_flush_recovery()) {
                // Flush successful, continue
            } else if (attempt_restart_recovery()) {
                // Restart successful, continue
            } else {
                LOGE("Recovery failed, returning DR_NEED_IDR");
                return DR_NEED_IDR;
            }
        } else {
            LOGE("Max recovery attempts reached, decoder needs full restart");
            return DR_NEED_IDR;
        }
    }

    ssize_t bufIndex = AMediaCodec_dequeueInputBuffer(g_codec, 10000);
    if (bufIndex < 0) {
        // Phase 4: Enhanced error logging
        if (bufIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            // Normal case, buffer not available yet
        } else {
            LOGE("nativeDecoderSubmit: dequeueInputBuffer failed, index=%zd (decoder: %s, state: %d)", 
                 bufIndex, g_decoderName[0] != '\0' ? g_decoderName : "unknown", g_decoderState);
            // Mark as error and attempt recovery on next call
            if (g_decoderState == DECODER_STATE_STARTED) {
                g_decoderState = DECODER_STATE_ERROR;
            }
        }
        return DR_NEED_IDR;
    }

    size_t bufSize = 0;
    uint8_t* buf = AMediaCodec_getInputBuffer(g_codec, (size_t)bufIndex, &bufSize);
    if (buf == NULL || bufSize < (size_t)length) {
        AMediaCodec_queueInputBuffer(g_codec, (size_t)bufIndex, 0, 0, 0, 0);
        return DR_NEED_IDR;
    }

    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte*)buf);

    uint32_t flags = 0;
    if (decodeUnitType != BUFFER_TYPE_PICDATA) {
        flags |= AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG;
    }
    if (frameType == FRAME_TYPE_IDR) {
        flags |= AMEDIACODEC_BUFFER_FLAG_KEY_FRAME;
    }

    int64_t ptsUs;
    if ((flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0) {
        ptsUs = 0;
    } else {
        ptsUs = enqueueTimeMs * 1000;
        if (ptsUs <= g_lastPtsUs) {
            ptsUs = g_lastPtsUs + 1;
        }
        g_lastPtsUs = ptsUs;
    }

    media_status_t status = AMediaCodec_queueInputBuffer(g_codec, (size_t)bufIndex, 0, (size_t)length, ptsUs, flags);
    if (status != AMEDIA_OK) {
        LOGE("nativeDecoderSubmit: AMediaCodec_queueInputBuffer failed status=%d (decoder: %s, state: %d)", 
             status, g_decoderName[0] != '\0' ? g_decoderName : "unknown", g_decoderState);
        // Mark as error and attempt recovery on next call
        if (g_decoderState == DECODER_STATE_STARTED) {
            g_decoderState = DECODER_STATE_ERROR;
        }
        return DR_NEED_IDR;
    }

    return DR_OK;
}


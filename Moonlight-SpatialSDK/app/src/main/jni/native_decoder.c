#include "native_decoder.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

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
#ifndef AMEDIAFORMAT_COLOR_TRANSFER_SDR_VIDEO
#define AMEDIAFORMAT_COLOR_TRANSFER_SDR_VIDEO 3
#endif
#ifndef AMEDIAFORMAT_COLOR_TRANSFER_ST2084
#define AMEDIAFORMAT_COLOR_TRANSFER_ST2084 6
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

static const char* mime_from_format(int videoFormat) {
    if ((videoFormat & 0x0F00) != 0) {
        return "video/hevc";
    }
    if ((videoFormat & 0xF000) != 0) {
        return "video/av01";
    }
    return "video/avc";
}

static void stop_output_thread() {
    if (g_outputRunning) {
        g_outputRunning = false;
        pthread_join(g_outputThread, NULL);
    }
}

static void release_codec() {
    stop_output_thread();

    if (g_started && g_codec != NULL) {
        AMediaCodec_stop(g_codec);
    }
    g_started = false;

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

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetSurface(JNIEnv* env, jclass clazz, jobject surface) {
    (void)clazz;
    release_window();
    if (surface != NULL) {
        g_window = ANativeWindow_fromSurface(env, surface);
    }
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetup(JNIEnv* env, jclass clazz, jint videoFormat, jint width, jint height, jint fps) {
    (void)env;
    (void)clazz;

    release_codec();

    g_videoFormat = videoFormat;
    g_width = width;
    g_height = height;
    g_fps = fps;
    g_lastPtsUs = 0;

    if (g_window == NULL) {
        LOGE("nativeDecoderSetup failed: surface is null");
        return -1;
    }

    const char* mime = mime_from_format(videoFormat);
    g_codec = AMediaCodec_createDecoderByType(mime);
    if (g_codec == NULL) {
        LOGE("nativeDecoderSetup failed: AMediaCodec_createDecoderByType returned null");
        return -1;
    }

    g_format = AMediaFormat_new();
    AMediaFormat_setString(g_format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_HEIGHT, height);
    if (fps > 0) {
        AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
    }
    if (g_hdrEnabled && g_hdrStaticInfoLen > 0) {
        AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_RANGE, AMEDIAFORMAT_COLOR_RANGE_FULL);
        AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_STANDARD, AMEDIAFORMAT_COLOR_STANDARD_BT2020);
        AMediaFormat_setInt32(g_format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, AMEDIAFORMAT_COLOR_TRANSFER_ST2084);
        AMediaFormat_setBuffer(g_format, AMEDIAFORMAT_KEY_HDR_STATIC_INFO, g_hdrStaticInfo, g_hdrStaticInfoLen);
    }

    media_status_t status = AMediaCodec_configure(g_codec, g_format, g_window, NULL, 0);
    if (status != AMEDIA_OK) {
        LOGE("nativeDecoderSetup failed: AMediaCodec_configure status=%d", status);
        release_codec();
        return -1;
    }

    // Log negotiated formats to debug color handling
    AMediaFormat* inFmt = AMediaCodec_getInputFormat(g_codec);
    AMediaFormat* outFmt = AMediaCodec_getOutputFormat(g_codec);
    if (inFmt) {
        const char* dump = AMediaFormat_toString(inFmt);
        LOGI("nativeDecoderSetup input format: %s", dump ? dump : "(null)");
        AMediaFormat_delete(inFmt);
    }
    if (outFmt) {
        const char* dump = AMediaFormat_toString(outFmt);
        LOGI("nativeDecoderSetup output format: %s", dump ? dump : "(null)");
        AMediaFormat_delete(outFmt);
    }

    LOGI("nativeDecoderSetup complete mime=%s size=%dx%d fps=%d hdr=%d hdrStatic=%zu", mime, width, height, fps, g_hdrEnabled, g_hdrStaticInfoLen);
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
        LOGE("nativeDecoderStart: AMediaCodec_start failed status=%d", status);
        return;
    }

    g_started = true;
    g_outputRunning = true;
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
    g_hdrEnabled = enabled == JNI_TRUE;
    g_hdrStaticInfoLen = 0;

    if (g_hdrEnabled && hdrMetadata != NULL) {
        jsize len = (*env)->GetArrayLength(env, hdrMetadata);
        if (len > 0 && (size_t)len <= sizeof(g_hdrStaticInfo)) {
            (*env)->GetByteArrayRegion(env, hdrMetadata, 0, len, (jbyte*)g_hdrStaticInfo);
            g_hdrStaticInfoLen = (size_t)len;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSubmit(JNIEnv* env, jclass clazz, jbyteArray data, jint length, jint decodeUnitType, jint frameNumber, jint frameType, jchar frameHostProcessingLatency, jlong receiveTimeMs, jlong enqueueTimeMs) {
    (void)clazz;
    (void)frameHostProcessingLatency;
    (void)receiveTimeMs;
    (void)frameNumber;

    if (!g_started || g_codec == NULL) {
        return DR_NEED_IDR;
    }

    ssize_t bufIndex = AMediaCodec_dequeueInputBuffer(g_codec, 10000);
    if (bufIndex < 0) {
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
        LOGE("nativeDecoderSubmit: queueInputBuffer failed status=%d", status);
        return DR_NEED_IDR;
    }

    return DR_OK;
}


#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

jint Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetup(JNIEnv* env, jclass clazz, jint videoFormat, jint width, jint height, jint fps);
void Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderStart(JNIEnv* env, jclass clazz);
void Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderStop(JNIEnv* env, jclass clazz);
void Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderCleanup(JNIEnv* env, jclass clazz);
void Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetSurface(JNIEnv* env, jclass clazz, jobject surface);
void Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSetHdrMode(JNIEnv* env, jclass clazz, jboolean enabled, jbyteArray hdrMetadata);
jint Java_com_limelight_nvstream_jni_MoonBridge_nativeDecoderSubmit(JNIEnv* env, jclass clazz, jbyteArray data, jint length, jint decodeUnitType, jint frameNumber, jint frameType, jchar frameHostProcessingLatency, jlong receiveTimeMs, jlong enqueueTimeMs);

#ifdef __cplusplus
}
#endif


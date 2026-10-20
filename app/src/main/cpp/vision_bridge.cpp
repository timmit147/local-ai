#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"

#define LOG_TAG "PocketAI-Vision"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("MTMD vision bridge loaded");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_describeImage(
        JNIEnv* env,
        jobject,
        jstring modelPath,
        jstring mmprojPath,
        jstring imagePath,
        jstring prompt) {

    return env->NewStringUTF(
        "Vision inference setup requires the matching llama.cpp "
        "image-loading and decoding API. This function is not yet "
        "connected to model inference."
    );
}

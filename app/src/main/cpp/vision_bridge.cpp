#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"

#define TAG "PocketAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("MTMD native vision engine loaded");
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

    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    const char* projector = env->GetStringUTFChars(mmprojPath, nullptr);
    const char* image = env->GetStringUTFChars(imagePath, nullptr);
    const char* question = env->GetStringUTFChars(prompt, nullptr);

    std::string result =
        "MTMD inference is not connected. Model: " +
        std::string(model ? model : "") +
        ". The model and image-processing calls must be implemented "
        "against the matching llama.cpp API.";

    if (model) env->ReleaseStringUTFChars(modelPath, model);
    if (projector) env->ReleaseStringUTFChars(mmprojPath, projector);
    if (image) env->ReleaseStringUTFChars(imagePath, image);
    if (question) env->ReleaseStringUTFChars(prompt, question);

    return env->NewStringUTF(result.c_str());
}

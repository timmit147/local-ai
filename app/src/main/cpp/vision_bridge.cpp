#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env,
        jobject /* thiz */) {
    return env->NewStringUTF(
        "Vision native bridge loaded. Image inference is not connected yet."
    );
}

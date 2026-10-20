#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env,
        jobject /* thiz */) {
    return env->NewStringUTF(
        "Vision libraries loaded. Model inference is not connected yet."
    );
}

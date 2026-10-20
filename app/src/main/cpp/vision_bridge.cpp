#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include "llama.h"
#include "mtmd.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env, jobject) {
    return env->NewStringUTF(
        "MTMD loaded. Ready to initialize vision model."
    );
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

    const char* model_file =
        env->GetStringUTFChars(modelPath, nullptr);
    const char* projector_file =
        env->GetStringUTFChars(mmprojPath, nullptr);
    const char* image_file =
        env->GetStringUTFChars(imagePath, nullptr);
    const char* question =
        env->GetStringUTFChars(prompt, nullptr);

    std::string result;

    llama_backend_init();

    llama_model_params model_params =
        llama_model_default_params();

    llama_model* model = llama_model_load_from_file(
        model_file, model_params
    );

    if (model == nullptr) {
        result = "Could not load the vision language model.";
    } else {
        mtmd_context_params vision_params =
            mtmd_context_params_default();

        vision_params.use_gpu = false;
        vision_params.n_threads = 4;

        mtmd_context* vision = mtmd_init_from_file(
            projector_file, model, vision_params
        );

        if (vision == nullptr) {
            result = "Could not load the mmproj file.";
        } else {
            result =
                "Vision model and projector loaded, but image "
                "tokenization and text generation still need "
                "to be connected.";
            mtmd_free(vision);
        }

        llama_model_free(model);
    }

    llama_backend_free();

    env->ReleaseStringUTFChars(modelPath, model_file);
    env->ReleaseStringUTFChars(mmprojPath, projector_file);
    env->ReleaseStringUTFChars(imagePath, image_file);
    env->ReleaseStringUTFChars(prompt, question);

    return env->NewStringUTF(result.c_str());
}

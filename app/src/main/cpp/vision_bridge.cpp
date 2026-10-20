#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"

static std::string errorMessage;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_pocketai_VisionEngine_nativeStatus(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("MTMD API available");
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

    const char* modelFile = env->GetStringUTFChars(modelPath, nullptr);
    const char* projectorFile = env->GetStringUTFChars(mmprojPath, nullptr);
    const char* imageFile = env->GetStringUTFChars(imagePath, nullptr);
    const char* question = env->GetStringUTFChars(prompt, nullptr);

    std::string result;
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(modelFile, modelParams);

    if (!model) {
        result = "Failed to load vision language model.";
    } else {
        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = 2048;
        contextParams.n_batch = 1024;

        llama_context* context = llama_init_from_model(model, contextParams);

        if (!context) {
            result = "Failed to create inference context.";
        } else {
            mtmd_context_params visionParams = mtmd_context_params_default();
            visionParams.use_gpu = true;
            visionParams.n_threads = 4;

            mtmd_context* vision = mtmd_init_from_file(
                projectorFile, model, visionParams
            );

            if (!vision) {
                result = "Failed to load the matching mmproj file.";
            } else {
                result =
                    "The model and projector loaded successfully, but "
                    "image tokenization and decoding must be connected "
                    "before inference can return an answer.";
                mtmd_free(vision);
            }

            llama_free(context);
        }

        llama_model_free(model);
    }

    llama_backend_free();

    env->ReleaseStringUTFChars(modelPath, modelFile);
    env->ReleaseStringUTFChars(mmprojPath, projectorFile);
    env->ReleaseStringUTFChars(imagePath, imageFile);
    env->ReleaseStringUTFChars(prompt, question);

    return env->NewStringUTF(result.c_str());
}

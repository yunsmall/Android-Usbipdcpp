#include "jni_log_callback.h"
#include <android/log.h>

namespace jni_log {

namespace {
    JavaVM* g_jvm = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_log_method = nullptr;
}

void init(JavaVM* jvm, jobject callback_obj, jmethodID log_method) {
    g_jvm = jvm;
    g_callback_obj = callback_obj;
    g_log_method = log_method;
}

void cleanup(JNIEnv* env) {
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_jvm = nullptr;
    g_log_method = nullptr;
}

void log_callback(spdlog::level::level_enum level, const std::string& message) {
    if (!g_jvm || !g_callback_obj || !g_log_method) return;

    JNIEnv* env = nullptr;
    bool need_detach = false;

    int get_env_result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            need_detach = true;
        } else {
            return;
        }
    } else if (get_env_result != JNI_OK) {
        return;
    }

    jstring jmessage = env->NewStringUTF(message.c_str());
    jint jlevel = static_cast<jint>(level);
    env->CallVoidMethod(g_callback_obj, g_log_method, jlevel, jmessage);
    env->DeleteLocalRef(jmessage);

    if (need_detach) {
        g_jvm->DetachCurrentThread();
    }
}

} // namespace jni_log
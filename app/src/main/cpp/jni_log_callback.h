#pragma once

#include <jni.h>
#include <spdlog/spdlog.h>

namespace jni_log {

// 初始化JNI日志回调
void init(JavaVM* jvm, jobject callback_obj, jmethodID log_method);

// 清理
void cleanup(JNIEnv* env);

// spdlog回调函数
void log_callback(spdlog::level::level_enum level, const std::string& message);

} // namespace jni_log
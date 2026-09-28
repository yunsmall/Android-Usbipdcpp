#pragma once

#include <jni.h>
#include <spdlog/spdlog.h>

namespace jni_log {

// 初始化JNI日志回调。env 用于替换已有回调时释放旧全局引用，防止重复调用泄漏。
// callback_obj 必须是全局引用，所有权交给本函数：成功后被回调持有、由后续的
// init/cleanup 释放；失败时（取不到 JavaVM）当场释放，调用方不要重复释放。
// 返回 false 表示全局状态完全未变（引用已释放），调用方不应启用新的日志通路
bool init(JNIEnv* env, jobject callback_obj, jmethodID log_method);

// 清理
void cleanup(JNIEnv* env);

// spdlog回调函数
void log_callback(spdlog::level::level_enum level, const std::string& message);

} // namespace jni_log
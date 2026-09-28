#include "jni_log_callback.h"
#include <android/log.h>
#include <mutex>

namespace jni_log {

namespace {
    JavaVM* g_jvm = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_log_method = nullptr;
    // 保护 g_callback_obj 等全局：cleanup 删除全局引用时，log_callback
    // 可能正由 usbipdcpp 的日志线程执行，不用锁会访问已释放的引用
    std::mutex g_mutex;
}

bool init(JNIEnv* env, jobject callback_obj, jmethodID log_method) {
    // MainActivity 的 DisposableEffect 每次重建都会调用 setLogCallback，
    // 不释放旧引用会导致全局引用永久泄漏
    // GetJavaVM 只读 env 内部的 vm 指针（不碰引用表、不阻塞），放锁外执行；
    // 写 g_jvm 仍在锁内，避免与 cleanup 的置空竞争
    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
        // 规范上允许失败（实现上取 env 缓存的 vm 指针，基本不会）：失败时全局
        // 状态保持不变（旧回调继续工作），只把本次的新引用释放掉
        env->DeleteGlobalRef(callback_obj);
        return false;
    }
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_jvm = jvm;
        g_callback_obj = callback_obj;
        g_log_method = log_method;
    }
    // DeleteGlobalRef 触碰 JVM 引用表，放锁外执行：与本文件"JNI 调用不进锁"
    // 的约定一致，也避免持锁等待 JVM 内部锁
    if (old) {
        env->DeleteGlobalRef(old);
    }
    return true;
}

void cleanup(JNIEnv* env) {
    // 与 init 同纪律：锁内只摘引用，DeleteGlobalRef 放锁外执行
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_callback_obj = nullptr;
        g_jvm = nullptr;
        g_log_method = nullptr;
    }
    if (old) {
        env->DeleteGlobalRef(old);
    }
}

void log_callback(spdlog::level::level_enum level, const std::string& message) {
    // 锁内只派生本地引用并拷贝所需值，JNI 调用放锁外：
    // - 本地引用独立引用计数，锁外调用期间 cleanup 删除全局引用对象仍存活
    //   （快照裸指针方案在这里是 use-after-free，不能用于缩小锁范围）
    // - 锁外执行用户代码，Kotlin 回调未来即使同步触发 native 日志也不会死锁
    JavaVM* jvm = nullptr;
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    bool need_detach = false;
    {
        std::lock_guard lock(g_mutex);
        if (!g_jvm || !g_callback_obj || !g_log_method) return;
        jvm = g_jvm;

        // attach 放锁内执行：JVM 的 attach 只建线程局部表、不执行 Java 代码，
        // 不会回调用户逻辑；需要 GetEnv 后才能在锁内派生本地引用
        int get_env_result = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_EDETACHED) {
            // 用完即 detach 而不缓存 attach 状态：日志线程由库创建，线程退出时
            // 我们没有补救机会，保持 attached 会违反 JNI"退出前必须 detach"的要求；
            // attach/detach 是微秒级操作，不做优化
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                need_detach = true;
            } else {
                return;
            }
        } else if (get_env_result != JNI_OK) {
            return;
        }

        callback = env->NewLocalRef(g_callback_obj);
        method = g_log_method;
    }
    if (!env || !callback || !method) {
        // NewLocalRef 失败（OOM）会挂起 OutOfMemoryError，必须清掉：异常残留在
        // 已 attach 的线程上（如发起调用的 UI 线程）会破坏其后续 JNI 调用。
        // 已 attach 的线程必须 detach，否则线程永久附加在 JVM 上
        if (env) {
            env->ExceptionClear();
        }
        if (need_detach) {
            jvm->DetachCurrentThread();
        }
        return;
    }

    jstring jmessage = env->NewStringUTF(message.c_str());
    if (!jmessage) {
        // OOM 时 NewStringUTF 返回 null 并挂 OutOfMemoryError，清掉避免残留
        env->ExceptionClear();
        env->DeleteLocalRef(callback);
        if (need_detach) {
            jvm->DetachCurrentThread();
        }
        return;
    }

    jint jlevel = static_cast<jint>(level);
    env->CallVoidMethod(callback, method, jlevel, jmessage);
    env->DeleteLocalRef(jmessage);
    env->DeleteLocalRef(callback);

    // Kotlin 回调抛异常时清除，避免异常残留在 JNI 栈上破坏后续调用
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (need_detach) {
        jvm->DetachCurrentThread();
    }
}

} // namespace jni_log
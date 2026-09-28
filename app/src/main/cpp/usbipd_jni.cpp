#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <libusb-1.0/libusb.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/android_sink.h>

#include <usbipdcpp/Server.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>

#include "jni_callback_sink.h"
#include "jni_log_callback.h"

#define LOG_TAG "UsbIpNative"

// 错误码定义 - 必须与 Kotlin 层 UsbIpNative.kt 中的常量保持一致
namespace ErrorCode {
    constexpr int SUCCESS = 0;
    constexpr int DEVICE_NOT_FOUND = 1;
    constexpr int DEVICE_IN_USE = 2;
    constexpr int DEVICE_OPEN_FAILED = 3;
    constexpr int GET_DESCRIPTOR_FAILED = 4;
    constexpr int GET_CONFIG_FAILED = 5;
    constexpr int CLAIM_INTERFACE_FAILED = 6;
    constexpr int DEVICE_ALREADY_BOUND = 7;
    constexpr int HUB_FILTERED = 8;
    constexpr int SERVER_NOT_RUNNING = 9;
    constexpr int UNKNOWN_ERROR = 99;
}

namespace {
    std::mutex g_server_mutex;
    // g_server、g_server_running、g_initialized 三者的读写全部在 g_server_mutex 内。
    // g_server 与 g_server_running 成对维护：置 true 只发生在启动成功之后，失败/停止
    // 路径都在置 true 之前返回或一并归零，不存在"running 为 true 而 server 为空"的
    // 状态——stopServerImpl 等处的短路判断依赖这个不变量，不需要自愈分支。
    // g_initialized 的 libusb 初始化也在锁内完成：并发调用 nativeInit 时后来的调用
    // 会在锁上排队等前者做完，不会出现"看到已初始化标记但 libusb 尚未就绪"的窗口
    std::unique_ptr<usbipdcpp::LibusbServer> g_server;
    bool g_initialized = false;
    bool g_server_running = false;

    int toErrorCode(usbipdcpp::DeviceOperationResult result) {
        using namespace usbipdcpp;
        switch (result) {
            case DeviceOperationResult::Success: return ErrorCode::SUCCESS;
            case DeviceOperationResult::DeviceNotFound: return ErrorCode::DEVICE_NOT_FOUND;
            case DeviceOperationResult::DeviceInUse: return ErrorCode::DEVICE_IN_USE;
            case DeviceOperationResult::DeviceAlreadyBound: return ErrorCode::DEVICE_ALREADY_BOUND;
            case DeviceOperationResult::DeviceOpenFailed: return ErrorCode::DEVICE_OPEN_FAILED;
            case DeviceOperationResult::GetDescriptorFailed: return ErrorCode::GET_DESCRIPTOR_FAILED;
            case DeviceOperationResult::GetConfigFailed: return ErrorCode::GET_CONFIG_FAILED;
            case DeviceOperationResult::ClaimInterfaceFailed: return ErrorCode::CLAIM_INTERFACE_FAILED;
            case DeviceOperationResult::HubFiltered: return ErrorCode::HUB_FILTERED;
            // DeviceFiltered 只在设置了 device_bind_filter 时才会出现，本应用没设，
            // 归入未知错误即可
            default: return ErrorCode::UNKNOWN_ERROR;
        }
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    // 刻意留空：Android 上 System.loadLibrary 的库随进程存活，不会触发 unload，
    // 资源统一由 releaseImpl 清理；若将来需要支持 unload（如 ClassLoader 隔离的
    // 测试环境），这里要补 g_server 析构与 jni_log 的全局引用清理
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInitImpl(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Initializing native layer");

    // 初始化全程持 g_server_mutex：并发调用只有一个真正执行，其余在锁上等到做完后
    // 直接看到"已初始化"。不用原子 CAS：它只保证初始化执行一次而不保证已完成，后来的
    // 调用会乐观返回"已初始化"，而 libusb 可能尚未就绪
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (g_initialized) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Already initialized");
        return JNI_TRUE;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);

    int err = libusb_init(nullptr);
    if (err < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to initialize libusb: %s", libusb_strerror(err));
        // 未置位，允许下次重试
        return JNI_FALSE;
    }

    g_initialized = true;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Native layer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_setLogCallbackImpl(JNIEnv* env, jobject thiz, jobject callback) {
    if (callback == nullptr) {
        // null 表示清除回调：UI 销毁（应用退后台）时释放全局引用，
        // 否则旧 Activity 会被 JNI 全局引用一直持有导致泄漏
        jni_log::cleanup(env);
        return;
    }

    jobject callback_global = env->NewGlobalRef(callback);
    if (callback_global == nullptr) {
        // OOM 时 NewGlobalRef 返回 null 并挂 OutOfMemoryError：不清掉会抛给 Kotlin
        // 调用方（setLogCallback 没有 try-catch），冒到 Compose 里就是崩溃
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "NewGlobalRef failed");
        return;
    }

    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        // OOM 时 GetObjectClass 可能返回 null，传给 GetMethodID 是 UB（ART 下 abort）
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetObjectClass failed");
        return;
    }
    jmethodID log_method = env->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(callback_class);
    if (log_method == nullptr) {
        // GetMethodID 失败时 JNI 栈上挂着 NoSuchMethodError，清掉并释放引用。
        // 不重置 spdlog 与旧回调：保留旧回调继续输出日志，比静默丢日志更合理
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find onLog method");
        return;
    }

    // make_shared/spdlog 可能抛 bad_alloc，C++ 异常不能穿越 JNI 边界（ART 无法
    // unwind，直接 terminate）。callback_ref_owned 标记引用是否还在本函数手上：
    // 构造在所有权转移之前，异常时 jni_log 与旧 logger 都保持原状
    bool callback_ref_owned = true;
    try {
        auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>("usbipdcpp");
        auto jni_sink = std::make_shared<jni_callback_sink_mt>(jni_log::log_callback);

        // 每次重建（Activity 旋转/重启）都新建 logger 替换 default：旧 logger 由
        // set_default_logger 释放 default 引用后随 shared_ptr 引用计数析构，
        // 不经过 registry 也不会泄漏
        auto logger = std::make_shared<spdlog::logger>("usbipdcpp", spdlog::sinks_init_list{android_sink, jni_sink});
        logger->set_level(spdlog::level::debug);

        bool installed = jni_log::init(env, callback_global, log_method);
        // init 无论成败都不再由本函数持有该引用（失败时它已当场释放）
        callback_ref_owned = false;
        if (!installed) {
            // 回调没装上：保持旧 logger 不动，避免出现"新 logger 已生效但回调
            // 还是旧的"的错位状态
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to install log callback");
            return;
        }

        spdlog::set_default_logger(logger);
        spdlog::set_pattern("[%H:%M:%S] [%l] %v");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Log callback set");
    } catch (const std::exception& e) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw: %s", e.what());
    } catch (...) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw unknown exception");
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_bindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd, jint vendor_id, jint product_id, jobjectArray outBusid) {

    // 整体兜底：库调用与 std::string 分配都可能抛（如 bad_alloc），C++ 异常穿越
    // extern "C" 的 JNI 边界时 ART 无法 unwind，会直接 terminate
    try {
        spdlog::info("Binding USB device: fd={}, vid=0x{:04x}, pid=0x{:04x}", fd, vendor_id, product_id);

        // 出参数组无效就无法把 busid 交回调用方：先验后绑，否则会留下"绑定成功但
        // 调用方拿不到 busid"的半成品状态（Kotlin 侧拿不到 busid 只会关连接，不会解绑）
        if (outBusid == nullptr || env->GetArrayLength(outBusid) == 0) {
            spdlog::error("outBusid is null or empty");
            return ErrorCode::UNKNOWN_ERROR;
        }

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        // 先取 busid 再绑定：取信息失败时尚未绑定，直接返回即可，不存在"已绑定却
        // 交付不出 busid"的中间态。临时 wrap 是 usbipdcpp 的标准用法（库内部绑定与
        // 客户端连接时也各 wrap 一次，lazy binding 互不影响）；libusb 对 wrap 进来的
        // fd 设 fd_keep，close 临时句柄不会关闭原 fd（UsbDeviceConnection 不受影响）。
        // fd_keep 与 wrap API 同为 libusb 1.0.23 引入，不存在"老版本会关 fd"的组合
        libusb_device_handle* temp_handle = nullptr;
        int err = libusb_wrap_sys_device(nullptr, static_cast<intptr_t>(fd), &temp_handle);
        if (err < 0) {
            spdlog::error("Failed to get device info for busid: {}", libusb_strerror(err));
            return ErrorCode::DEVICE_OPEN_FAILED;
        }

        // 临时 handle 用 RAII 托管：get_device_busid 的 std::string 构造可能抛，
        // 走外层 catch 时不能漏掉 libusb_close。作用域限定在取 busid 这段，
        // 之后的绑定要在"临时 handle 已关闭"的状态下进行
        std::string busid;
        {
            struct TempHandleGuard {
                libusb_device_handle* handle;
                ~TempHandleGuard() {
                    if (handle) libusb_close(handle);
                }
            } guard{temp_handle};

            libusb_device* dev = libusb_get_device(temp_handle);
            if (!dev) {
                spdlog::error("libusb_get_device returned nullptr");
                return ErrorCode::DEVICE_NOT_FOUND;
            }
            busid = usbipdcpp::get_device_busid(dev);
        }

        auto result = g_server->bind_host_device_with_wrapped_fd(static_cast<intptr_t>(fd));
        if (result != usbipdcpp::DeviceOperationResult::Success) {
            spdlog::error("bind_host_device_with_wrapped_fd failed: {}", static_cast<int>(result));
            return toErrorCode(result);
        }

        spdlog::info("Device bound successfully: {}", busid);

        // 交付失败按 busid 兜底移除：fd 解绑失败时 fd 匹配已不可靠，
        // notify_device_removed 走 busid 键，是独立于 fd 的第二条清理路径。
        // noexcept + 内层 try：回滚自身再抛异常同样不能穿越边界，那样连"设备残留"
        // 都没人知道，至少要留下日志
        auto rollback = [&]() noexcept {
            try {
                if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) == usbipdcpp::DeviceOperationResult::Success) {
                    return;
                }
                auto notify_result = g_server->notify_device_removed(busid);
                spdlog::warn("Rollback by fd failed, busid fallback result: {}", static_cast<int>(notify_result));
            } catch (const std::exception& e) {
                spdlog::error("Rollback threw: {}", e.what());
            } catch (...) {
                spdlog::error("Rollback threw unknown exception");
            }
        };

        // 输出 busid（数组有效性已在入口校验）
        jstring busid_str = env->NewStringUTF(busid.c_str());
        if (busid_str == nullptr) {
            // OOM 时 NewStringUTF 返回 null：写 null 进数组会让 Kotlin 侧
            // outBusid[0]!! 抛 NPE，回滚绑定并返回错误
            env->ExceptionClear();
            rollback();
            return ErrorCode::UNKNOWN_ERROR;
        }
        env->SetObjectArrayElement(outBusid, 0, busid_str);
        // DeleteLocalRef 在挂起异常时也是安全调用（JNI 规范允许），先释放再统一检查
        env->DeleteLocalRef(busid_str);

        // 写入失败时同样回滚：契约是"要么把 busid 交给调用方，要么 native 不留绑定"，
        // 否则调用方拿不到 busid 也没法解绑（正常参数下 SetObjectArrayElement 不会失败）
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            rollback();
            return ErrorCode::UNKNOWN_ERROR;
        }

        return ErrorCode::SUCCESS;
    } catch (const std::exception& e) {
        spdlog::error("bindUsbDevice threw exception: {}", e.what());
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        spdlog::error("bindUsbDevice threw unknown exception");
        return ErrorCode::UNKNOWN_ERROR;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_startServerImpl(JNIEnv* env, jobject thiz, jint port) {

    spdlog::info("Starting USB/IP server on port {}", port);

    // 端口越界直接拒绝：endpoint 内部按 unsigned short 存端口，65536 会回绕成 0
    //（系统随机分配端口），99999 回绕成别的端口，UI 显示与实际监听不一致
    if (port < 1 || port > 65535) {
        spdlog::error("Invalid port: {}", port);
        return JNI_FALSE;
    }

    // 无条件走一次初始化（幂等，已初始化时立即返回）：g_initialized 受
    // g_server_mutex 保护，这里尚未持锁不能直接读；JNI 是公共边界，不假设调用方
    // 一定先调过 nativeInit，自己保证 libusb 就绪，失败就直接返回
    if (!Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInitImpl(env, thiz)) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (g_server_running) {
        // true 只表示"服务器在运行"，不是"本次入参端口启动成功"：
        // 调用方不能用入参回填实际端口状态
        spdlog::info("Server already running");
        return JNI_TRUE;
    }

    try {
        g_server = std::make_unique<usbipdcpp::LibusbServer>();
        g_server->set_hotplug_enabled(false);
        asio::ip::tcp::endpoint endpoint(asio::ip::tcp::v4(), static_cast<unsigned short>(port));
        // v1.0.8 起 start 不再抛异常，启动失败（如端口被占用）通过返回值报告
        auto ec = g_server->start(endpoint);
        if (ec) {
            spdlog::error("Failed to start server: {}", ec.message());
            // start 失败路径内部已自清理（热插拔监控、libusb 事件线程），无需 stop 直接析构
            g_server.reset();
            return JNI_FALSE;
        }
        g_server_running = true;
        spdlog::info("Server started successfully");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        // make_unique 等构造路径的异常兜底（start 本身不再抛）
        spdlog::error("Failed to start server: {}", e.what());
        if (g_server) {
            try {
                g_server->stop();
            } catch (...) {}
        }
        g_server.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(JNIEnv* env, jobject thiz) {

    spdlog::info("Stopping USB/IP server");

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server_running || !g_server) {
        spdlog::info("Server not running");
        return;
    }

    try {
        g_server->stop();
        g_server.reset();
        g_server_running = false;

        spdlog::info("Server stopped successfully");
    } catch (const std::exception& e) {
        // 停止失败也必须重置状态，否则 g_server_running 卡在 true 无法再次启动
        spdlog::error("Error stopping server: {}", e.what());
        g_server.reset();
        g_server_running = false;
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unbindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd) {

    // 同 bindUsbDeviceImpl：库调用可能抛，异常不能穿越 extern "C" 边界
    try {
        spdlog::info("Unbinding USB device with fd={}", fd);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        auto result = g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd));
        int errorCode = toErrorCode(result);

        if (errorCode == ErrorCode::SUCCESS) {
            spdlog::info("Device unbound successfully");
        } else {
            spdlog::error("Failed to unbind device: {}", errorCode);
        }

        return errorCode;
    } catch (const std::exception& e) {
        spdlog::error("unbindUsbDevice threw exception: {}", e.what());
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        spdlog::error("unbindUsbDevice threw unknown exception");
        return ErrorCode::UNKNOWN_ERROR;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_notifyDeviceRemovedImpl(
    JNIEnv* env, jobject thiz, jstring busid) {

    // 同 bind/unbind：字符串构造与库调用都可能抛，异常不能穿越 extern "C" 边界
    try {
        const char* busid_cstr = env->GetStringUTFChars(busid, nullptr);
        if (busid_cstr == nullptr) {
            // OOM：GetStringUTFChars 返回 null 并挂 OutOfMemoryError，
            // 直接构造 std::string 会崩溃，清异常后安全返回
            env->ExceptionClear();
            spdlog::error("Failed to get busid string");
            return;
        }
        std::string busid_str(busid_cstr);
        env->ReleaseStringUTFChars(busid, busid_cstr);

        spdlog::info("Notifying device removed: {}", busid_str);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_server) {
            spdlog::warn("Server not created, ignoring device removal notification");
            return;
        }

        g_server->notify_device_removed(busid_str);
    } catch (const std::exception& e) {
        spdlog::error("notifyDeviceRemoved threw exception: {}", e.what());
    } catch (...) {
        spdlog::error("notifyDeviceRemoved threw unknown exception");
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_releaseImpl(JNIEnv* env, jobject thiz) {
    // 整体兜底：spdlog 的分配等可能抛，而 Kotlin 侧的 try/catch 抓不到 C++ 异常
    try {
        spdlog::info("Releasing native resources");

        Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(env, thiz);

        // 状态在锁内改，libusb_exit 放锁外：它会停 libusb 的内部事件线程，耗时不可控。
        // 与 init 的并发不会发生：Kotlin 侧全部 JNI 调用经 nativeDispatcher 串行，
        // release 是终局操作，之后不会再有 init
        bool was_initialized = false;
        {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            was_initialized = g_initialized;
            g_initialized = false;
        }
        if (was_initialized) {
            libusb_exit(nullptr);
        }

        jni_log::cleanup(env);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw unknown exception");
    }
}

} // extern "C"

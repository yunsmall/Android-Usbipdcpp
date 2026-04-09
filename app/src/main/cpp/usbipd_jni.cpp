#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <set>

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
    constexpr int UNKNOWN_ERROR = 99;
}

namespace {
    JavaVM* g_jvm = nullptr;
    std::mutex g_server_mutex;
    std::unique_ptr<usbipdcpp::LibusbServer> g_server;
    std::atomic<bool> g_initialized{false};
    std::atomic<bool> g_server_running{false};

    std::set<std::string> g_bound_devices;

    int toErrorCode(usbipdcpp::DeviceOperationResult result) {
        using namespace usbipdcpp;
        switch (result) {
            case DeviceOperationResult::Success: return ErrorCode::SUCCESS;
            case DeviceOperationResult::DeviceNotFound: return ErrorCode::DEVICE_NOT_FOUND;
            case DeviceOperationResult::DeviceInUse: return ErrorCode::DEVICE_IN_USE;
            case DeviceOperationResult::DeviceOpenFailed: return ErrorCode::DEVICE_OPEN_FAILED;
            case DeviceOperationResult::GetDescriptorFailed: return ErrorCode::GET_DESCRIPTOR_FAILED;
            case DeviceOperationResult::GetConfigFailed: return ErrorCode::GET_CONFIG_FAILED;
            case DeviceOperationResult::ClaimInterfaceFailed: return ErrorCode::CLAIM_INTERFACE_FAILED;
            default: return ErrorCode::UNKNOWN_ERROR;
        }
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInit(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Initializing native layer");

    if (g_initialized) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Already initialized");
        return JNI_TRUE;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);

    int err = libusb_init(nullptr);
    if (err < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to initialize libusb: %s", libusb_strerror(err));
        return JNI_FALSE;
    }

    g_initialized = true;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Native layer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_setLogCallback(JNIEnv* env, jobject thiz, jobject callback) {
    jobject callback_global = env->NewGlobalRef(callback);
    jclass callback_class = env->GetObjectClass(callback);
    jmethodID log_method = env->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");

    jni_log::init(g_jvm, callback_global, log_method);

    auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>("usbipdcpp");
    auto jni_sink = std::make_shared<jni_callback_sink_mt>(jni_log::log_callback);

    auto logger = std::make_shared<spdlog::logger>("usbipdcpp", spdlog::sinks_init_list{android_sink, jni_sink});
    logger->set_level(spdlog::level::debug);
    spdlog::set_default_logger(logger);
    spdlog::set_pattern("[%H:%M:%S] [%l] %v");

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Log callback set");
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_bindUsbDeviceNative(
    JNIEnv* env, jobject thiz, jint fd, jint vendor_id, jint product_id, jobjectArray outBusid) {

    spdlog::info("Binding USB device: fd={}, vid=0x{:04x}, pid=0x{:04x}", fd, vendor_id, product_id);

    if (!g_initialized) {
        spdlog::error("Native layer not initialized");
        return ErrorCode::UNKNOWN_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::error("Server not created");
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    auto result = g_server->bind_host_device_with_wrapped_fd(static_cast<intptr_t>(fd));
    if (result != usbipdcpp::DeviceOperationResult::Success) {
        spdlog::error("bind_host_device_with_wrapped_fd failed: {}", static_cast<int>(result));
        return toErrorCode(result);
    }

    // 获取 busid
    libusb_device_handle* temp_handle = nullptr;
    int err = libusb_wrap_sys_device(nullptr, static_cast<intptr_t>(fd), &temp_handle);
    if (err < 0) {
        spdlog::error("Failed to get device info for busid: {}", libusb_strerror(err));
        return ErrorCode::DEVICE_OPEN_FAILED;
    }

    libusb_device* dev = libusb_get_device(temp_handle);
    if (!dev) {
        spdlog::error("libusb_get_device returned nullptr");
        libusb_close(temp_handle);
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    std::string busid = usbipdcpp::get_device_busid(dev);
    libusb_close(temp_handle);

    g_bound_devices.insert(busid);
    spdlog::info("Device bound successfully: {}", busid);

    // 输出 busid
    if (outBusid != nullptr && env->GetArrayLength(outBusid) > 0) {
        env->SetObjectArrayElement(outBusid, 0, env->NewStringUTF(busid.c_str()));
    }

    return ErrorCode::SUCCESS;
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_startServer(JNIEnv* env, jobject thiz, jint port) {

    spdlog::info("Starting USB/IP server on port {}", port);

    if (!g_initialized) {
        spdlog::error("Native layer not initialized, initializing now...");
        if (!Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInit(env, thiz)) {
            return JNI_FALSE;
        }
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (g_server_running) {
        spdlog::info("Server already running");
        return JNI_TRUE;
    }

    try {
        g_server = std::make_unique<usbipdcpp::LibusbServer>();
        g_server->set_hotplug_enabled(false);
        asio::ip::tcp::endpoint endpoint(asio::ip::tcp::v4(), static_cast<unsigned short>(port));
        g_server->start(endpoint);
        g_server_running = true;
        spdlog::info("Server started successfully");
        return JNI_TRUE;
    } catch (const std::exception& e) {
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
Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServer(JNIEnv* env, jobject thiz) {

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
        g_bound_devices.clear();

        spdlog::info("Server stopped successfully");
    } catch (const std::exception& e) {
        spdlog::error("Error stopping server: {}", e.what());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_isServerRunning(JNIEnv* env, jobject thiz) {
    return g_server_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unbindUsbDeviceNative(
    JNIEnv* env, jobject thiz, jint fd) {

    spdlog::info("Unbinding USB device with fd={}", fd);

    if (!g_initialized) {
        spdlog::error("Native layer not initialized");
        return ErrorCode::UNKNOWN_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::error("Server not created");
        return ErrorCode::DEVICE_NOT_FOUND;
    }

    auto result = g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd));
    int errorCode = toErrorCode(result);

    if (errorCode == ErrorCode::SUCCESS) {
        spdlog::info("Device unbound successfully");
    } else {
        spdlog::error("Failed to unbind device: {}", errorCode);
    }

    return errorCode;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_notifyDeviceRemovedNative(
    JNIEnv* env, jobject thiz, jstring busid) {

    const char* busid_cstr = env->GetStringUTFChars(busid, nullptr);
    std::string busid_str(busid_cstr);
    env->ReleaseStringUTFChars(busid, busid_cstr);

    spdlog::info("Notifying device removed: {}", busid_str);

    std::lock_guard<std::mutex> lock(g_server_mutex);

    if (!g_server) {
        spdlog::warn("Server not created, ignoring device removal notification");
        return;
    }

    g_server->notify_device_removed(busid_str);
    // 从本地集合中移除
    g_bound_devices.erase(busid_str);
}

JNIEXPORT jobjectArray JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_getBoundDevices(JNIEnv* env, jobject thiz) {
    jclass stringClass = env->FindClass("java/lang/String");
    auto size = static_cast<jsize>(g_bound_devices.size());
    jobjectArray result = env->NewObjectArray(size, stringClass, nullptr);

    int i = 0;
    for (const auto& busid : g_bound_devices) {
        env->SetObjectArrayElement(result, i++, env->NewStringUTF(busid.c_str()));
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_release(JNIEnv* env, jobject thiz) {
    spdlog::info("Releasing native resources");

    Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServer(env, thiz);

    if (g_initialized) {
        libusb_exit(nullptr);
        g_initialized = false;
    }

    jni_log::cleanup(env);
}

} // extern "C"

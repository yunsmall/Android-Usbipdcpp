package com.yunsmall.usbipdcpp

import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object UsbIpNative {
    private const val TAG = "UsbIpNative"

    // 加载放在类初始化里：访问本对象任意成员都会先触发这里，从而保证任何 JNI
    // 调用都在库加载之后——UI 首次组合里的 setLogCallback 早于 Service.onCreate
    // 执行时（冷启动竞态）也不会抛 UnsatisfiedLinkError。
    // 异常必须就地捕获：逃出类初始化会让 JVM 把这个类标记为初始化失败，之后
    // 每次访问都抛 NoClassDefFoundError，外层的 try-catch 也救不回来
    private val libraryLoaded: Boolean = try {
        System.loadLibrary("usbipdcpp_native")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load native library", e)
        false
    }

    // LibusbServer 内部没有锁，状态变量/容器并发访问会把状态搞乱，所有调用必须
    // 串行。下面每个 suspend 方法内部都切到这个 dispatcher，调用方不必自己记得
    // 切；需要"Kotlin 侧状态与 native 调用同临界区"的场景（UsbService 的设备表）
    // 仍可在外面再包一层同一 dispatcher 的 withContext——withContext 遇到相同
    // dispatcher 会原地执行，不会重复派发也不会嵌套等待
    val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // 所有 JNI 调用都在这个上下文执行：
    // - nativeDispatcher 保证串行（LibusbServer 无锁）
    // - NonCancellable 保证一旦开始就不会被取消：JNI 调用是原子的，被取消会
    //   让 native 与 Kotlin 两侧状态不一致——native 已经绑定设备而调用方拿不到
    //   结果，既没登记到设备表也没机会关掉已打开的 connection
    private val jniContext = nativeDispatcher + NonCancellable

    // ==================== JNI 实现，全部私有 ====================
    // 库没加载成功时 JNI 调用会抛 UnsatisfiedLinkError（冷启动崩溃的根因），
    // 守卫统一收在下面的公开方法里：分散到各调用点判断，漏一处就是崩溃
    private external fun nativeInitImpl(): Boolean
    private external fun setLogCallbackImpl(callback: LogCallback?)
    private external fun bindUsbDeviceImpl(fd: Int, vendorId: Int, productId: Int, outBusid: Array<String?>): Int
    private external fun unbindUsbDeviceImpl(fd: Int): Int
    private external fun notifyDeviceRemovedImpl(busid: String)
    private external fun startServerImpl(port: Int): Boolean
    private external fun stopServerImpl()
    private external fun releaseImpl()

    // 错误码 - 必须与 JNI 层 usbipd_jni.cpp 中的 ErrorCode 命名空间保持一致
    private object ErrorCode {
        const val SUCCESS = 0
        const val DEVICE_NOT_FOUND = 1
        const val DEVICE_IN_USE = 2
        const val DEVICE_OPEN_FAILED = 3
        const val GET_DESCRIPTOR_FAILED = 4
        const val GET_CONFIG_FAILED = 5
        const val CLAIM_INTERFACE_FAILED = 6
        const val UNKNOWN_ERROR = 99
    }

    // ==================== 公开 API ====================

    /**
     * 初始化 native 层。返回 false 表示 native 不可用（库加载或 libusb 初始化失败），
     * 调用方必须跳过后续所有 native 操作
     */
    suspend fun init(): Boolean {
        if (!libraryLoaded) return false
        return withContext(jniContext) { nativeInitImpl() }
    }

    /**
     * 设置日志回调，传 null 表示清除（释放 JNI 全局引用）。
     * 由 UI 线程在组合/销毁时调用，不能做成 suspend，库不可用时静默忽略
     */
    fun setLogCallback(callback: LogCallback?) {
        if (libraryLoaded) setLogCallbackImpl(callback)
    }

    suspend fun bindUsbDevice(fd: Int, vendorId: Int, productId: Int): DeviceBindResult {
        if (!libraryLoaded) return DeviceBindResult.Failure.UnknownError
        return withContext(jniContext) {
            val outBusid = arrayOfNulls<String>(1)
            val code = bindUsbDeviceImpl(fd, vendorId, productId, outBusid)
            // JNI 层 SUCCESS 时必已写 busid，防御性检查防止未来实现改动导致 NPE
            val busid = outBusid[0]
            if (code == ErrorCode.SUCCESS && busid != null) {
                DeviceBindResult.Success(busid)
            } else {
                if (code == ErrorCode.SUCCESS) Log.e(TAG, "bind returned SUCCESS but busid is null")
                errorCodeToBindFailure(code)
            }
        }
    }

    suspend fun unbindUsbDevice(fd: Int): DeviceUnbindResult {
        if (!libraryLoaded) return DeviceUnbindResult.Failure.UnknownError
        return withContext(jniContext) {
            when (val code = unbindUsbDeviceImpl(fd)) {
                ErrorCode.SUCCESS -> DeviceUnbindResult.Success
                ErrorCode.DEVICE_NOT_FOUND -> DeviceUnbindResult.Failure.DeviceNotFound
                ErrorCode.DEVICE_IN_USE -> DeviceUnbindResult.Failure.DeviceInUse
                else -> {
                    Log.e(TAG, "Unknown unbind error: $code")
                    DeviceUnbindResult.Failure.UnknownError
                }
            }
        }
    }

    suspend fun notifyDeviceRemoved(busid: String) {
        if (!libraryLoaded) return
        withContext(jniContext) { notifyDeviceRemovedImpl(busid) }
    }

    suspend fun startServer(port: Int): Boolean {
        if (!libraryLoaded) return false
        return withContext(jniContext) { startServerImpl(port) }
    }

    suspend fun stopServer() {
        if (!libraryLoaded) return
        withContext(jniContext) { stopServerImpl() }
    }

    suspend fun release() {
        if (!libraryLoaded) return
        withContext(jniContext) { releaseImpl() }
    }

    /**
     * 在 native 线程同步执行代码块，供 Service 销毁等非协程场景使用。
     * 注意：runBlocking 会阻塞当前线程，禁止在主线程调用（会 ANR）
     */
    @WorkerThread
    fun runOnNativeThread(block: suspend () -> Unit) {
        runBlocking {
            withContext(nativeDispatcher) {
                block()
            }
        }
    }

    // JNI 错误码 → 失败结果：映射只有这一处，加错误码时不会漏改调用方
    private fun errorCodeToBindFailure(code: Int): DeviceBindResult.Failure = when (code) {
        ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
        ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
        ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
        ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
        ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
        ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
        else -> DeviceBindResult.Failure.UnknownError
    }
}

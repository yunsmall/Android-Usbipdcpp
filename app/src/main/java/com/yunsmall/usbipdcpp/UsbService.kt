package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
    }

    /**
     * 对外可见的全部 Service 状态。UI 与通知栏都从这一份渲染，
     * 不再各自缓存一份靠手动刷新同步
     */
    data class UiState(
        val nativeReady: Boolean = false,
        val serverRunning: Boolean = false,
        val port: Int = 3240,
        // deviceName -> busid，UI 展示 busid 用
        val boundDevices: Map<String, String> = emptyMap()
    )

    /** 一次性事件：SharedFlow 无重放，UI 不在时事件自然丢弃（正合预期） */
    sealed interface Event {
        /** 设备被拔出；wasBound 表示拔出前处于绑定状态，UI 据此决定是否提示 */
        data class DeviceDetached(val device: UsbDevice, val wasBound: Boolean) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // 服务内部协程：native 初始化、状态变化刷新通知、拔出清理。
    // 状态写入本身线程安全（MutableStateFlow.update 是 CAS 循环），
    // 用 Main.immediate 只是让通知更新落在主线程
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // native 操作的统一上下文：native 线程 + 不可取消。
    // 启停、绑定与登记、拔出清理都必须执行到底：中途取消会留下 native 已生效而
    // Kotlin 未登记的半成品（设备已在库里绑定，这里却既没记录还会被关掉连接），
    // 变成谁也清不掉的孤儿。需要放弃的时机统一由 onDestroy 的 release 兜底，
    // 不靠取消来打断；调用方（UI）被取消只影响它自己能否拿到结果
    private val serviceNativeContext = UsbIpNative.nativeDispatcher + NonCancellable

    private val binder = UsbBinder()

    // 保存活跃的USB连接。只在 nativeDispatcher 线程读写（与 native 调用同一临界区），
    // 对外的绑定状态经 _state 发布，所以这里用普通 map 即可
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    // onStartCommand 之前 notify 同 ID 通知会先于 startForeground 生效，语义混乱，
    // 这里等前台通知建立后再由状态驱动更新
    private var notificationStarted = false

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // 服务自注册的拔出监听：Activity 销毁（用户退出 UI）后服务仍在前台运行，
    // 拔出清理不能依赖 UI 层，否则 activeDevices 残留、连接无法清理。
    // 这是拔出清理的唯一路径，UI 只消费 Event 弹提示
    private val deviceDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbDevice ->
                // onReceive 在主线程，设备清理要切到 native 线程；起协程而非
                // runBlocking，避免阻塞主线程
                serviceScope.launch { handleDeviceDetached(usbDevice) }
            }
        }
    }

    init {
        // 状态变化的唯一出口：通知栏文案跟着 _state 走，不用在每个变更点手写刷新
        serviceScope.launch {
            state.collect { if (notificationStarted) updateNotification() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // native 初始化含库加载与 libusb 初始化，放 native 线程执行不阻塞主线程
        serviceScope.launch {
            val ready = UsbIpNative.init()
            _state.update { it.copy(nativeReady = ready) }
            if (!ready) {
                Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
            }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceDetachedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceDetachedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationStarted = true
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(deviceDetachedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        // 这里的取消不会与下面的清理打架：init() 若还没开始会被取消（g_initialized
        // 保持 false，release 跳过 libusb_exit）；已经开始的那个跑在 jniContext
        //（NonCancellable）上会跑完并置位，release 的 libusb_exit 正好配对
        serviceScope.cancel()
        // native 清理含 join 线程等耗时操作，在主线程 runBlocking 会卡 ANR，
        // 放到后台线程执行（进程退出时系统回收，不保证执行完）。
        // closeAllDevices 也放在 native 线程内：销毁期间 MainActivity 的协程
        // 可能仍在 nativeDispatcher 上执行 bindDevice，map 写必须同线程串行。
        // 闭包持有 this 是强引用：JVM 下 Service 对象在清理线程运行期间不会被
        // GC，不存在 C++ 那样的 use-after-free。
        // 清理线程可能要等正在执行的 native 调用（如 bindDevice 卡在 USB 传输上）
        // 结束，极端情况下等很久；不加超时：onDestroy 是终结路径，线程挂住最坏到
        // 进程退出由系统回收，而超时会让 release 根本不执行，清理更不完整。
        // release 先于 closeAllDevices：先让库停止使用 fd（服务器析构、设备释放），
        // 再关闭 UsbDeviceConnection，避免"libusb 仍在用已关闭 fd"的窗口
        Thread {
            // 外层再兜一层：清理异常逃到这个裸线程的默认异常处理器会终止进程，
            // 而 Service 被 stopSelf/系统回收并不等于进程退出（App 可能仍在前台）。
            // 终局清理尽力而为，失败只记日志
            try {
                UsbIpNative.runOnNativeThread {
                    // release 内部会先停服务器并释放 libusb，不用再单独判断服务是否运行；
                    // finally 保证 release 抛异常时连接也被关掉
                    try {
                        UsbIpNative.release()
                    } finally {
                        closeAllDevices()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native cleanup failed", t)
            }
        }.start()
    }

    suspend fun startServer(port: Int): Boolean {
        // 唯一调用点（MainActivity 启动按钮）有 isStarting 禁用 + 本判断双重保护，
        // 不会出现"native 已在跑而 Kotlin 不知情"的并发调用；将来若出现多调用方，
        // 应让 JNI 返回实际监听端口，而不是在这里用入参覆盖 state
        if (state.value.serverRunning) return true

        return withContext(serviceNativeContext) {
            val success = UsbIpNative.startServer(port)
            if (success) {
                _state.update { it.copy(serverRunning = true, port = port) }
            }
            success
        }
    }

    suspend fun stopServer() {
        withContext(serviceNativeContext) {
            UsbIpNative.stopServer()
            // 关设备也在 native 线程内，避免 UI 线程迭代/清空 map 与并发绑定冲突
            closeAllDevices()
            _state.update { it.copy(serverRunning = false, boundDevices = emptyMap()) }
        }
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // 整个方法体在 native 线程：设备表读写必须与 native 调用同临界区。
        // 上下文不可取消（见 serviceNativeContext）：绑定与登记必然一起完成或一起
        // 不发生，不存在"native 已绑定而 Kotlin 没登记"的孤儿；调用方被取消只影响
        // 它自己能否拿到结果
        return withContext(serviceNativeContext) {
            // 防御：同一设备已绑定则拒绝，否则覆盖 map 条目导致旧连接泄漏
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            // 成功登记后连接归 activeDevices 管，其余路径（含异常）统一由 finally 关闭：
            // 绑定失败或抛异常时没有登记，留着连接就是 fd 泄漏
            var registered = false
            try {
                // fd < 0 实际不会出现（openDevice 成功后必有 fd），防御在此：
                // 返回失败，连接由 finally 统一关闭
                val fd = connection.fileDescriptor
                if (fd < 0) {
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }

                when (val result = UsbIpNative.bindUsbDevice(fd, device.vendorId, device.productId)) {
                    is DeviceBindResult.Success -> {
                        activeDevices[device.deviceName] = DeviceInfo(connection, fd, result.busid)
                        // 登记成功即接管连接所有权（finally 据此决定关不关连接）；
                        // 紧跟着的 state 更新失败不回滚登记：连接已归设备表管，不能再关
                        registered = true
                        _state.update {
                            it.copy(boundDevices = it.boundDevices + (device.deviceName to result.busid))
                        }
                        Log.i(TAG, "Device bound: ${device.deviceName} -> ${result.busid}")
                        result
                    }
                    is DeviceBindResult.Failure -> result
                }
            } catch (t: CancellationException) {
                // 结构化并发规范：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "bindDevice failed unexpectedly: ${device.deviceName}", t)
                DeviceBindResult.Failure.UnknownError
            } finally {
                if (!registered) {
                    connection.close()
                }
            }
        }
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        // 同 bindDevice：挂 serviceNativeContext，cleanup 不会被调用方取消打断
        return withContext(serviceNativeContext) {
            val info = activeDevices[deviceName]
                ?: return@withContext DeviceUnbindResult.Failure.DeviceNotFound

            // native 侧已无此设备（如已被拔出清理）时 Kotlin 侧连接和状态同样要清，
            // 否则连接泄漏、UI 一直显示已绑定
            fun cleanup() {
                activeDevices.remove(deviceName)?.connection?.close()
                _state.update { it.copy(boundDevices = it.boundDevices - deviceName) }
            }

            // 异常时保持登记不动：设备可能仍在 native 侧绑定着，清掉 Kotlin 记录
            // 会让它变成谁也解不了的孤儿，保留登记调用方还能重试
            try {
                when (val result = UsbIpNative.unbindUsbDevice(info.fd)) {
                    DeviceUnbindResult.Success -> {
                        cleanup()
                        Log.i(TAG, "Device unbound: $deviceName")
                        result
                    }
                    // 服务器已不在时设备同样视为已消失，Kotlin 侧连接和状态照清
                    DeviceUnbindResult.Failure.DeviceNotFound,
                    DeviceUnbindResult.Failure.ServerNotRunning -> {
                        cleanup()
                        Log.w(TAG, "Device already gone in native: $deviceName")
                        result
                    }
                    DeviceUnbindResult.Failure.DeviceInUse -> result
                    else -> {
                        Log.e(TAG, "Unknown unbind error: $result for $deviceName")
                        result
                    }
                }
            } catch (t: CancellationException) {
                // 结构化并发规范：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "unbindDevice failed unexpectedly: $deviceName", t)
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * 设备拔出清理：整块在 native 线程执行，先通知 native 清理再关 Kotlin 连接
     * （notify_device_removed 同步移除设备或触发会话停止；物理拔出后 fd 已失效，
     * 且 native 的 libusb handle 持有独立 fd，close 互不影响）。
     * 只由 Service 自己的接收器调用，UI 不再参与清理
     */
    private suspend fun handleDeviceDetached(usbDevice: UsbDevice) {
        // 同 bindDevice/unbindDevice 用 serviceNativeContext：登记、native 通知、
        // 状态更新是一次清理事务，不被调用方取消打断。
        // 事件也在块内发出：块外是协程恢复点，Service 恰好在销毁时事件就发不出去
        withContext(serviceNativeContext) {
            val info = activeDevices.remove(usbDevice.deviceName)
            if (info == null) {
                _events.tryEmit(Event.DeviceDetached(usbDevice, false))
                return@withContext
            }
            // native 通知失败也要把 Kotlin 侧清理做完：设备已物理拔出，连接和状态
            // 必须一并清掉，否则该设备永远显示"已绑定"
            try {
                UsbIpNative.notifyDeviceRemoved(info.busid)
            } catch (t: CancellationException) {
                // 结构化并发规范：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "notifyDeviceRemoved failed: ${usbDevice.deviceName}", t)
            } finally {
                info.connection.close()
                _state.update { it.copy(boundDevices = it.boundDevices - usbDevice.deviceName) }
            }
            Log.i(TAG, "Device detached: ${usbDevice.deviceName}")
            _events.tryEmit(Event.DeviceDetached(usbDevice, true))
        }
    }

    // 只在 nativeDispatcher 线程调用
    private fun closeAllDevices() {
        activeDevices.values.forEach { it.connection.close() }
        activeDevices.clear()
        Log.i(TAG, "All devices closed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // 动态显示运行状态：服务器未运行时提示已停止，避免误导
            .setContentText(getString(if (state.value.serverRunning) R.string.server_running else R.string.server_stopped))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}

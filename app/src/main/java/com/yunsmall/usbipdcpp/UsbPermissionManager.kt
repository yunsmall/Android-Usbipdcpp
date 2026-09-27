package com.yunsmall.usbipdcpp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"
        const val ACTION_USB_PERMISSION = "com.yunsmall.usbipdcpp.USB_PERMISSION"
    }

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    // 统一锁对象：onReceive 与 requestPermission 各自 synchronized(this) 时
    // this 指向不同对象（Receiver 实例 vs Manager 实例），互斥完全不生效
    private val lock = Any()
    // 按 deviceName 存待处理请求：多设备并发请求互不覆盖，回调后立即移除
    private val pendingCallbacks = mutableMapOf<String, (UsbDevice, Boolean) -> Unit>()
    private var onDeviceAttached: (() -> Unit)? = null

    fun setOnDeviceAttachedListener(listener: (() -> Unit)?) {
        onDeviceAttached = listener
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    device?.let { usbDevice ->
                        Log.d(TAG, "USB permission result for ${usbDevice.deviceName}: $granted")
                        // 锁内只取出，锁外执行回调：回调可能再次发起权限请求，
                        // 锁内同步执行用户代码（锁不可重入）有死锁风险
                        val callback = synchronized(lock) {
                            // 取用后移除：回调闭包持有 Activity 引用，不清理会泄漏
                            pendingCallbacks.remove(usbDevice.deviceName)
                        }
                        callback?.invoke(usbDevice, granted)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    onDeviceAttached?.invoke()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { usbDevice ->
                        Log.d(TAG, "USB device detached: ${usbDevice.deviceName}")
                        // 拔出后权限结果广播不会返回，清掉对应 pending 回调，
                        // 否则条目残留会让该设备名后续无法再发起权限请求
                        synchronized(lock) {
                            pendingCallbacks.remove(usbDevice.deviceName)
                        }
                    }
                    // 设备物理拔出，设备列表必须刷新（解绑清理由 UsbService 自己的
                    // 接收器负责，与本管理器无关）
                    onDeviceAttached?.invoke()
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun getDeviceList(): Map<String, UsbDevice> {
        // hub 对 USB/IP 导出无意义（导出的是拓扑节点而非功能设备）。
        // 上游 AOSP 已在 UsbHostManager 里把 hub 从 UsbManager 剔除，这里再滤一道：
        // 定制 ROM 可能改掉该行为，界面不该出现这种设备
        return usbManager.deviceList.filterValues { !isHubDevice(it) }
    }

    // 对齐 AOSP UsbHostManager 的 hub 判定：设备描述符声明了 hub 类的直接算；
    // 描述符没给类（bDeviceClass=0）的从接口取，全部接口都是 hub 类才算，
    // 避免误伤带 hub 的复合设备
    private fun isHubDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        if (device.deviceClass != 0 || device.interfaceCount == 0) return false
        return (0 until device.interfaceCount).all {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * @return true 表示请求已受理（含已有权限直接回调的情况），
     *         false 表示同设备已有待处理请求、本次未受理
     */
    fun requestPermission(device: UsbDevice, callback: (UsbDevice, Boolean) -> Unit): Boolean {
        if (usbManager.hasPermission(device)) {
            callback(device, true)
            return true
        }

        // 锁内只存回调，锁外发起系统请求：避免持锁调用可能同步回调的外部代码。
        // 同一设备已有待处理请求时拒绝新的：重复点击系统只弹一次对话框，
        // 回调被覆盖会导致前一次请求的 UI 状态（如 busyDevices）无法清除
        synchronized(lock) {
            if (pendingCallbacks.containsKey(device.deviceName)) {
                return false
            }
            pendingCallbacks[device.deviceName] = callback
        }
        usbManager.requestPermission(device, permissionIntent)
        return true
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return if (usbManager.hasPermission(device)) {
            usbManager.openDevice(device)
        } else {
            null
        }
    }
}
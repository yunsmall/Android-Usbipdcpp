package com.yunsmall.usbipdcpp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null
    private var pendingDevice: UsbDevice? = null
    private var onDeviceAttached: (() -> Unit)? = null
    private var onDeviceDetached: ((UsbDevice) -> Unit)? = null

    fun setOnDeviceAttachedListener(listener: (() -> Unit)?) {
        onDeviceAttached = listener
    }

    fun setOnDeviceDetachedListener(listener: ((UsbDevice) -> Unit)?) {
        onDeviceDetached = listener
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        device?.let { usbDevice ->
                            Log.d(TAG, "USB permission result for ${usbDevice.deviceName}: $granted")
                            onPermissionResult?.invoke(usbDevice, granted)
                        }

                        pendingDevice = null
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
                        onDeviceDetached?.invoke(usbDevice)
                    }
                    // 同时刷新设备列表
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
        return usbManager.deviceList
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, callback: (UsbDevice, Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            callback(device, true)
            return
        }

        synchronized(this) {
            pendingDevice = device
            onPermissionResult = callback
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return if (usbManager.hasPermission(device)) {
            usbManager.openDevice(device)
        } else {
            null
        }
    }
}
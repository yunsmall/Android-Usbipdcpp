package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = UsbBinder()
    private val scope = CoroutineScope(Dispatchers.Default)

    // 保存活跃的USB连接
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    var serverRunning = false
        private set
    var port = 3240
        private set

    val boundDeviceNames: Set<String>
        get() = activeDevices.keys

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        UsbIpNative.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        UsbIpNative.runOnNativeThread {
            if (UsbIpNative.isServerRunning()) {
                UsbIpNative.stopServer()
            }
            UsbIpNative.release()
        }
        closeAllDevices()
        scope.cancel()
    }

    suspend fun startServer(port: Int): Boolean {
        if (serverRunning) return true

        return withContext(UsbIpNative.nativeDispatcher) {
            val success = UsbIpNative.startServer(port)
            if (success) {
                this@UsbService.port = port
                serverRunning = true
                updateNotification()
            }
            success
        }
    }

    suspend fun stopServer() {
        withContext(UsbIpNative.nativeDispatcher) {
            UsbIpNative.stopServer()
            closeAllDevices()
            serverRunning = false
        }
        updateNotification()
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        return withContext(UsbIpNative.nativeDispatcher) {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val fd = getFileDescriptorFromConnection(connection)
            if (fd < 0) {
                connection.close()
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val outBusid = arrayOfNulls<String>(1)
            val result = UsbIpNative.bindUsbDeviceNative(fd, device.vendorId, device.productId, outBusid)

            when (result) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    val busid = outBusid[0]!!
                    activeDevices[device.deviceName] = DeviceInfo(connection, fd, busid)
                    Log.i(TAG, "Device bound: ${device.deviceName} -> $busid")
                    DeviceBindResult.Success(busid)
                }
                else -> {
                    connection.close()
                    mapErrorCodeToResult(result)
                }
            }
        }
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        return withContext(UsbIpNative.nativeDispatcher) {
            val info = activeDevices[deviceName]
            if (info == null) {
                return@withContext DeviceUnbindResult.Failure.DeviceNotFound
            }

            val result = UsbIpNative.unbindUsbDeviceNative(info.fd)

            when (result) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    Log.i(TAG, "Device unbound: $deviceName")
                    DeviceUnbindResult.Success
                }
                UsbIpNative.ErrorCode.DEVICE_IN_USE -> {
                    DeviceUnbindResult.Failure.DeviceInUse
                }
                else -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    DeviceUnbindResult.Failure.DeviceNotFound
                }
            }
        }
    }

    fun handleDeviceDetached(deviceName: String): Boolean {
        val info = activeDevices[deviceName] ?: return false
        UsbIpNative.runOnNativeThread {
            UsbIpNative.notifyDeviceRemovedNative(info.busid)
        }
        activeDevices.remove(deviceName)?.connection?.close()
        Log.i(TAG, "Device detached: $deviceName")
        return true
    }

    private fun closeAllDevices() {
        activeDevices.values.forEach { it.connection.close() }
        activeDevices.clear()
        Log.i(TAG, "All devices closed")
    }

    private fun getFileDescriptorFromConnection(connection: UsbDeviceConnection): Int {
        return try {
            val method = connection.javaClass.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            method.invoke(connection) as Int
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file descriptor", e)
            -1
        }
    }

    private fun mapErrorCodeToResult(errorCode: Int): DeviceBindResult.Failure {
        return when (errorCode) {
            UsbIpNative.ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
            UsbIpNative.ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
            UsbIpNative.ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
            UsbIpNative.ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
            UsbIpNative.ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
            UsbIpNative.ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
            else -> DeviceBindResult.Failure.UnknownError
        }
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
            .setContentText(getString(R.string.server_running))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}

package com.yunsmall.usbipdcpp

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object UsbIpNative {
    private const val TAG = "UsbIpNative"

    // 单线程 Dispatcher，确保所有 LibusbServer 调用在同一线程
    val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // 保存活跃的USB连接，防止被GC导致fd失效
    // deviceName -> (connection, fd, busid)
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    init {
        System.loadLibrary("usbipdcpp_native")
    }

    // 错误码定义 - 必须与 JNI 层 usbipd_jni.cpp 中的 ErrorCode 命名空间保持一致
    object ErrorCode {
        const val SUCCESS = 0
        const val DEVICE_NOT_FOUND = 1
        const val DEVICE_IN_USE = 2
        const val DEVICE_OPEN_FAILED = 3
        const val GET_DESCRIPTOR_FAILED = 4
        const val GET_CONFIG_FAILED = 5
        const val CLAIM_INTERFACE_FAILED = 6
        const val UNKNOWN_ERROR = 99
    }

    external fun nativeInit(): Boolean
    external fun setLogCallback(callback: LogCallback)
    external fun bindUsbDeviceNative(fd: Int, vendorId: Int, productId: Int, outBusid: Array<String?>): Int
    external fun unbindUsbDeviceNative(fd: Int): Int
    external fun notifyDeviceRemovedNative(busid: String)
    external fun getBoundDevices(): Array<String>
    external fun startServer(port: Int): Boolean
    external fun stopServer()
    external fun isServerRunning(): Boolean
    external fun release()

    /**
     * 在 native 线程同步执行代码块
     */
    fun runOnNativeThread(block: () -> Unit) {
        runBlocking {
            withContext(nativeDispatcher) {
                block()
            }
        }
    }

    fun init(): Boolean {
        return try {
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            false
        }
    }

    fun openAndBindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        return try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device: ${device.deviceName}")
                return DeviceBindResult.Failure.DeviceOpenFailed
            }

            val fd = getFileDescriptorFromConnection(connection)
            if (fd < 0) {
                Log.e(TAG, "Invalid file descriptor")
                connection.close()
                return DeviceBindResult.Failure.DeviceOpenFailed
            }

            val outBusid = arrayOfNulls<String>(1)
            val errorCode = bindUsbDeviceNative(fd, device.vendorId, device.productId, outBusid)

            when (errorCode) {
                ErrorCode.SUCCESS -> {
                    val busid = outBusid[0]!!
                    activeDevices[device.deviceName] = DeviceInfo(connection, fd, busid)
                    Log.i(TAG, "Device bound: ${device.deviceName} -> $busid (fd=$fd)")
                    DeviceBindResult.Success(busid)
                }
                ErrorCode.DEVICE_NOT_FOUND -> {
                    connection.close()
                    DeviceBindResult.Failure.DeviceNotFound
                }
                ErrorCode.DEVICE_IN_USE -> {
                    connection.close()
                    DeviceBindResult.Failure.DeviceInUse
                }
                ErrorCode.DEVICE_OPEN_FAILED -> {
                    connection.close()
                    DeviceBindResult.Failure.DeviceOpenFailed
                }
                ErrorCode.GET_DESCRIPTOR_FAILED -> {
                    connection.close()
                    DeviceBindResult.Failure.GetDescriptorFailed
                }
                ErrorCode.GET_CONFIG_FAILED -> {
                    connection.close()
                    DeviceBindResult.Failure.GetConfigFailed
                }
                ErrorCode.CLAIM_INTERFACE_FAILED -> {
                    connection.close()
                    DeviceBindResult.Failure.ClaimInterfaceFailed
                }
                else -> {
                    connection.close()
                    DeviceBindResult.Failure.UnknownError
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening USB device", e)
            DeviceBindResult.Failure.UnknownError
        }
    }

    fun unbindDevice(deviceName: String): DeviceUnbindResult {
        val info = activeDevices[deviceName]
        if (info == null) {
            Log.w(TAG, "Device not found: $deviceName")
            return DeviceUnbindResult.Failure.DeviceNotFound
        }

        val errorCode = unbindUsbDeviceNative(info.fd)

        return when (errorCode) {
            ErrorCode.SUCCESS -> {
                activeDevices.remove(deviceName)?.connection?.close()
                Log.i(TAG, "Device unbound: $deviceName")
                DeviceUnbindResult.Success
            }
            ErrorCode.DEVICE_IN_USE -> {
                DeviceUnbindResult.Failure.DeviceInUse
            }
            ErrorCode.DEVICE_NOT_FOUND -> {
                activeDevices.remove(deviceName)?.connection?.close()
                DeviceUnbindResult.Failure.DeviceNotFound
            }
            else -> {
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    fun getBusidForDevice(deviceName: String): String? = activeDevices[deviceName]?.busid

    /**
     * 处理设备被物理拔出的情况
     * @param deviceName 设备名称
     * @return 是否是已绑定的设备
     */
    fun handleDeviceDetached(deviceName: String): Boolean {
        val info = activeDevices[deviceName]
        if (info == null) {
            Log.d(TAG, "Device not in active devices: $deviceName")
            return false
        }

        // 通知 native 层设备被拔出
        notifyDeviceRemovedNative(info.busid)

        // 清理 Kotlin 层状态
        activeDevices.remove(deviceName)?.connection?.close()
        Log.i(TAG, "Device detached and cleaned up: $deviceName")
        return true
    }

    fun closeDevice(deviceName: String) {
        activeDevices.remove(deviceName)?.connection?.close()
        Log.i(TAG, "Device closed: $deviceName")
    }

    fun closeAllDevices() {
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
}

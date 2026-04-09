package com.yunsmall.usbipdcpp

import android.content.Context
import com.yunsmall.usbipdcpp.R

/**
 * 设备解绑操作结果
 */
sealed class DeviceUnbindResult {
    object Success : DeviceUnbindResult()
    sealed class Failure : DeviceUnbindResult() {
        object DeviceNotFound : Failure()
        object DeviceInUse : Failure()
        object UnknownError : Failure()

        fun getMessage(context: Context): String = when (this) {
            DeviceNotFound -> context.getString(R.string.error_device_not_found)
            DeviceInUse -> context.getString(R.string.error_device_in_use)
            UnknownError -> context.getString(R.string.error_unknown)
        }
    }

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

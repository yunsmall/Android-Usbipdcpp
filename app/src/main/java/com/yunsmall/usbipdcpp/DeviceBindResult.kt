package com.yunsmall.usbipdcpp

import android.content.Context
import com.yunsmall.usbipdcpp.R

/**
 * 设备操作结果
 */
sealed class DeviceBindResult {
    data class Success(val busid: String) : DeviceBindResult()
    sealed class Failure : DeviceBindResult() {
        object DeviceNotFound : Failure()
        object DeviceInUse : Failure()
        object DeviceOpenFailed : Failure()
        object GetDescriptorFailed : Failure()
        object GetConfigFailed : Failure()
        object ClaimInterfaceFailed : Failure()
        object HubFiltered : Failure()
        object ServerNotRunning : Failure()
        object UnknownError : Failure()

        fun getMessage(context: Context): String = when (this) {
            DeviceNotFound -> context.getString(R.string.error_device_not_found)
            DeviceInUse -> context.getString(R.string.error_device_in_use)
            DeviceOpenFailed -> context.getString(R.string.error_device_open_failed)
            GetDescriptorFailed -> context.getString(R.string.error_get_descriptor_failed)
            GetConfigFailed -> context.getString(R.string.error_get_config_failed)
            ClaimInterfaceFailed -> context.getString(R.string.error_claim_interface_failed)
            HubFiltered -> context.getString(R.string.error_hub_filtered)
            ServerNotRunning -> context.getString(R.string.error_server_not_running)
            UnknownError -> context.getString(R.string.error_unknown)
        }
    }
}

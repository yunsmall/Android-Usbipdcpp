package com.yunsmall.usbipdcpp

interface LogCallback {
    fun onLog(level: Int, message: String)
}
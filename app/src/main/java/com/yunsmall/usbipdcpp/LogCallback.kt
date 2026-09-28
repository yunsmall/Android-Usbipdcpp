package com.yunsmall.usbipdcpp

/**
 * native 日志回调。onLog 由 native 日志线程同步调用，该线程要等它返回才继续处理
 * 后续日志：实现必须立即返回，重活自行切线程，否则会拖慢整条 native 日志通路
 */
interface LogCallback {
    fun onLog(level: Int, message: String)
}
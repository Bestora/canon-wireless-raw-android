package io.github.canonwirelessraw.ptp

object PtpNative {
    init { System.loadLibrary("ptpjni") }
    external fun create(): Long
    external fun destroy(rt: Long)
}

package io.github.canonwirelessraw.ptp

object PtpNative {
    init { System.loadLibrary("ptpjni") }

    external fun create(): Long
    external fun destroy(rt: Long)

    /** 0 = OK, sonst negativer Fehlercode. Führt aus: ptpip_connect + eigener
     *  Init-Command-Request mit guid (16 Bytes) + name, ptpip_connect_events + init_events. */
    external fun connect(rt: Long, ip: String, port: Int, guid: ByteArray, name: String): Int

    external fun openSession(rt: Long): Int
    external fun eosSetRemoteMode(rt: Long, mode: Int): Int
    external fun eosSetEventMode(rt: Long, mode: Int): Int

    external fun getStorageIds(rt: Long): IntArray?          // null bei Fehler
    external fun getObjectHandles(rt: Long, storageId: Int, format: Int, parent: Int): IntArray?

    /** [0]=filename, [1]=compressedSize (dezimal), [2]=dateCreated (PTP-String),
     *  [3]=objFormat (dezimal), [4]=parentObj (dezimal), [5]=assocType (dezimal) */
    external fun getObjectInfo(rt: Long, handle: Int): Array<String>?

    external fun getPartialObject(rt: Long, handle: Int, offset: Long, maxLen: Int): ByteArray?

    external fun cancel(rt: Long)        // setzt io_kill_switch
    external fun disconnect(rt: Long)    // close session + sockets
    external fun lastError(rt: Long): Int
}

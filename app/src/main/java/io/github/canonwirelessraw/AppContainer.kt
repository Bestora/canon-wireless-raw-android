package io.github.canonwirelessraw

import android.content.Context
import io.github.canonwirelessraw.ble.BleWaker
import io.github.canonwirelessraw.data.CameraRepository
import io.github.canonwirelessraw.data.MetaCache
import io.github.canonwirelessraw.data.Prefs
import io.github.canonwirelessraw.ptp.PtpClient
import io.github.canonwirelessraw.wifi.WifiConnector
import java.io.File

/**
 * App-wide singletons, wired once from [MainActivity.onCreate]. Task 12 replaces this with real
 * navigation/DI; for now the debug screen (Task 9) is the only consumer.
 */
object AppContainer {
    lateinit var prefs: Prefs
        private set
    lateinit var ptp: PtpClient
        private set
    lateinit var cache: MetaCache
        private set
    lateinit var repo: CameraRepository
        private set
    lateinit var wifi: WifiConnector
        private set
    lateinit var ble: BleWaker
        private set

    /** Idempotent: a second call (e.g. Activity re-creation) is a no-op. */
    fun init(context: Context) {
        if (::repo.isInitialized) return
        val appContext = context.applicationContext
        prefs = Prefs(appContext)
        ptp = PtpClient()
        cache = MetaCache(File(appContext.cacheDir, "meta"))
        repo = CameraRepository(ptp, cache, prefs)
        wifi = WifiConnector(appContext)
        ble = BleWaker(appContext)
    }
}

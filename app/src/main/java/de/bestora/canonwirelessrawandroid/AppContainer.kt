package de.bestora.canonwirelessrawandroid

import android.content.Context
import de.bestora.canonwirelessrawandroid.ble.BleWaker
import de.bestora.canonwirelessrawandroid.data.CameraRepository
import de.bestora.canonwirelessrawandroid.data.MetaCache
import de.bestora.canonwirelessrawandroid.data.Prefs
import de.bestora.canonwirelessrawandroid.ptp.PtpClient
import de.bestora.canonwirelessrawandroid.wifi.WifiConnector
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
        // Versioned cache dir: bump the suffix whenever thumbnail/rating extraction logic changes
        // so stale entries (e.g. corrupt thumbnails from the old marker-scan) are abandoned.
        cache = MetaCache(File(appContext.cacheDir, "meta-v3"))
        repo = CameraRepository(ptp, cache, prefs)
        wifi = WifiConnector(appContext)
        ble = BleWaker(appContext)
    }
}

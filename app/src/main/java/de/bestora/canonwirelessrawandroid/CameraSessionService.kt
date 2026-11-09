package de.bestora.canonwirelessrawandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

private const val CHANNEL = "camera_session"
private const val NOTIFICATION_ID = 1
private const val EXTRA_CAMERA = "camera"

/**
 * Keeps the app a foreground app while a camera session is open. Android drops a Wi-Fi network
 * requested via WifiNetworkSpecifier about 8 s after its app leaves the foreground (seen when
 * opening a RAW in Luminar); Canon's Camera Connect runs a foreground service for the same reason.
 */
class CameraSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Camera connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Connected to ${intent?.getStringExtra(EXTRA_CAMERA) ?: "camera"}")
            .setContentText("Keeps the camera connection while you use other apps")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context, camera: String) {
            // Refused from the background (a silent reconnect while in Luminar); the service started
            // with the session is still running then.
            runCatching {
                context.startForegroundService(Intent(context, CameraSessionService::class.java).putExtra(EXTRA_CAMERA, camera))
            }.onFailure { Log.w("CameraSessionService", "not started: $it") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraSessionService::class.java))
        }
    }
}

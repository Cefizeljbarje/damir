package com.damir.homecamviewer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a connection to every camera that has alerts on and posts a
 * notification, with the current picture, when one of them sees motion.
 * Works wherever the cameras are reachable: on the home Wi‑Fi or over Tailscale.
 */
class AlertService : Service() {

    private lateinit var store: CameraStore
    private val watchers = HashMap<String, EventWatcher>()
    private val lastAlertMs = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = CameraStore(this)
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cameras = store.all().filter { it.notify }
        // Started with startForegroundService(): enter the foreground even when about to stop.
        if (!enterForeground(cameras.size) || cameras.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        syncWatchers(cameras)
        return START_STICKY
    }

    override fun onDestroy() {
        watchers.values.forEach { it.stop() }
        watchers.clear()
        super.onDestroy()
    }

    /** Starts watchers for new cameras and restarts those whose address or login changed. */
    private fun syncWatchers(cameras: List<Camera>) {
        val wanted = cameras.associateBy { it.id }
        val iterator = watchers.entries.iterator()
        while (iterator.hasNext()) {
            val (id, watcher) = iterator.next()
            if (wanted[id] != watcher.camera) {
                watcher.stop()
                iterator.remove()
            }
        }
        for (camera in cameras) {
            if (camera.id !in watchers) {
                watchers[camera.id] = EventWatcher(camera, ::onMotion).also { it.start() }
            }
        }
    }

    /** Watcher thread. */
    private fun onMotion(camera: Camera, timeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - (lastAlertMs[camera.id] ?: 0L) < ALERT_COOLDOWN_MS) return
        lastAlertMs[camera.id] = now

        // The phone may be asleep; stay awake long enough to fetch the picture.
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeCamViewer::alert")
        wakeLock.acquire(20_000L)
        try {
            notifyMotion(camera, timeMs, CameraApi.snapshot(camera))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun notifyMotion(camera: Camera, timeMs: Long, picture: Bitmap?) {
        val open = PendingIntent.getActivity(
            this, camera.id.hashCode(),
            ViewerActivity.intentFor(this, camera)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = DateFormat.getTimeFormat(this).format(Date(timeMs))
        val builder = NotificationCompat.Builder(this, CHANNEL_MOTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.motion_title, camera.displayName))
            .setContentText(getString(R.string.motion_text, time))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(timeMs)
            .setShowWhen(true)
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(picture)
                        .bigLargeIcon(null as Bitmap?)
                )
        }
        // One notification per camera: a new alert replaces the previous one.
        val id = ALERT_ID_BASE + (camera.id.hashCode() and 0xFFFF)
        getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    private fun enterForeground(count: Int): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watching_title))
            .setContentText(resources.getQuantityString(R.plurals.watching_text, count, count))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not enter the foreground", e)
            false
        }
    }

    companion object {
        private const val TAG = "AlertService"
        private const val CHANNEL_WATCHING = "watching"
        private const val CHANNEL_MOTION = "motion"
        private const val NOTIF_ID = 1
        private const val ALERT_ID_BASE = 1000
        private const val ALERT_COOLDOWN_MS = 30_000L

        /** Runs the service while some camera has alerts on, and stops it otherwise. */
        fun sync(context: Context) {
            val intent = Intent(context, AlertService::class.java)
            try {
                if (CameraStore(context).all().any { it.notify }) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not start alerts", e)
            }
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCHING,
                    context.getString(R.string.channel_watching),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MOTION,
                    context.getString(R.string.channel_motion),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }
}

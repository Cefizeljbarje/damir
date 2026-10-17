package com.damir.homecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera, encodes frames to JPEG, publishes
 * them to [FrameBus], and runs the [MjpegServer]. A persistent notification
 * keeps it obvious that the camera is live.
 */
class CameraService : LifecycleService() {

    private lateinit var settings: Settings
    private lateinit var cameraExecutor: ExecutorService
    private var server: MjpegServer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastFrameMs = 0L
    private val minFrameIntervalMs = 66L // cap at ~15 fps to keep CPU/heat sane

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        startServer()
        startCamera()
        isRunning = true
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        server = MjpegServer(settings.port, settings.username, settings.password).also {
            try {
                it.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port ${settings.port}", e)
                Toast.makeText(this, "Port ${settings.port} is unavailable", Toast.LENGTH_LONG).show()
                server = null
                stopSelf()
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "camera provider unavailable", e)
                stopSelf()
                return@addListener
            }
            cameraProvider = provider

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameMs >= minFrameIntervalMs) {
                        lastFrameMs = now
                        val jpeg = YuvToJpeg.toJpeg(
                            image,
                            image.imageInfo.rotationDegrees,
                            settings.jpegQuality
                        )
                        FrameBus.publish(jpeg)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "frame processing failed", t)
                } finally {
                    image.close()
                }
            }

            val selector = CameraSelector.Builder()
                .requireLensFacing(settings.lensFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                Toast.makeText(this, "Could not open camera", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val url = streamUrl(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(url ?: getString(R.string.status_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeCam::stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomeCam::wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { cameraProvider?.unbindAll() }
        server?.stop()
        server = null
        FrameBus.clear()
        releaseLocks()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "homecam_camera"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.damir.homecam.action.STOP"

        /** True while the service is actively streaming (single-process flag). */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** The LAN URL the stream is reachable at, or null if offline. */
        fun streamUrl(context: Context): String? {
            val ip = NetworkUtils.localIpv4() ?: return null
            val port = Settings(context).port
            return "http://$ip:$port"
        }
    }
}

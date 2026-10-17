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
import android.os.Handler
import android.os.Looper
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
 * Foreground service that owns the camera and everything built on top of it:
 * the JPEG/MJPEG pipeline, motion detection, microphone streaming, and clip
 * recording. A persistent notification keeps it obvious the camera is live.
 */
class CameraService : LifecycleService() {

    private lateinit var settings: Settings
    private lateinit var cameraExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())

    private var server: MjpegServer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val motionDetector = MotionDetector()
    private val audioStreamer = AudioStreamer()
    @Volatile
    private var clipRecorder: ClipRecorder? = null
    private var recordingAvailable = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastFrameMs = 0L
    private val minFrameIntervalMs = 66L // cap at ~15 fps
    private var lastMotionNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannels()
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
        startAudioIfEnabled()
        startTicks()
        isRunning = true
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        server = MjpegServer(
            port = settings.port,
            username = settings.username,
            password = settings.password,
            recordingsDirProvider = { clipRecorder?.recordingsDir }
        ).also {
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

            analysis.setAnalyzer(cameraExecutor) { image -> onFrame(image) }

            val selector = CameraSelector.Builder()
                .requireLensFacing(settings.lensFacing)
                .build()

            val wantRecording = settings.recordingOn

            try {
                provider.unbindAll()
                if (wantRecording) {
                    val recorder = ClipRecorder(this)
                    val videoCapture = recorder.buildUseCase()
                    try {
                        provider.bindToLifecycle(this, selector, analysis, videoCapture)
                        clipRecorder = recorder
                        recordingAvailable = true
                    } catch (e: Exception) {
                        // Device can't do stream + record together: keep the stream.
                        Log.w(TAG, "bind with recording failed, streaming only", e)
                        provider.unbindAll()
                        provider.bindToLifecycle(this, selector, analysis)
                        clipRecorder = null
                        recordingAvailable = false
                    }
                } else {
                    provider.bindToLifecycle(this, selector, analysis)
                }

                if (recordingAvailable && settings.recordMode == Settings.RECORD_CONTINUOUS) {
                    clipRecorder?.startContinuous()
                }
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                Toast.makeText(this, "Could not open camera", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: androidx.camera.core.ImageProxy) {
        try {
            if (settings.motionEnabled) {
                val motion = motionDetector.process(image, settings.motionSensitivity)
                if (motion) main.post { handleMotion() }
            }
            val now = System.currentTimeMillis()
            if (now - lastFrameMs >= minFrameIntervalMs) {
                lastFrameMs = now
                val jpeg = YuvToJpeg.toJpeg(image, image.imageInfo.rotationDegrees, settings.jpegQuality)
                FrameBus.publish(jpeg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame processing failed", t)
        } finally {
            image.close()
        }
    }

    /** Runs on the main thread. */
    private fun handleMotion() {
        val now = System.currentTimeMillis()
        if (now - lastMotionNotifyMs >= MOTION_NOTIFY_COOLDOWN_MS) {
            lastMotionNotifyMs = now
            notifyMotion()
        }
        if (recordingAvailable && settings.recordMode == Settings.RECORD_MOTION) {
            clipRecorder?.onMotion()
        }
    }

    private fun startAudioIfEnabled() {
        val granted = ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (settings.audioEnabled && granted) {
            audioStreamer.start()
        }
    }

    private fun startTicks() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                if (recordingAvailable && settings.recordingOn) {
                    clipRecorder?.tick(settings.clipSeconds)
                }
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(getString(R.string.notif_title), streamUrl(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            val micGranted = ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (settings.audioEnabled && micGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String?): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text ?: getString(R.string.status_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun notifyMotion() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MOTION_ID)
            .setContentTitle(getString(R.string.notif_motion_title))
            .setContentText(getString(R.string.notif_motion_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_MOTION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val running = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val motion = NotificationChannel(
                CHANNEL_MOTION_ID,
                getString(R.string.notif_motion_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(running)
            manager.createNotificationChannel(motion)
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
        main.removeCallbacksAndMessages(null)
        runCatching { clipRecorder?.stop() }
        clipRecorder = null
        audioStreamer.stop()
        AudioBus.clear()
        motionDetector.reset()
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
        private const val CHANNEL_MOTION_ID = "homecam_motion"
        private const val NOTIF_ID = 1001
        private const val NOTIF_MOTION_ID = 1002
        private const val MOTION_NOTIFY_COOLDOWN_MS = 30_000L
        const val ACTION_STOP = "com.damir.homecam.action.STOP"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }

        fun streamUrl(context: Context): String? {
            val ip = NetworkUtils.localIpv4() ?: return null
            val port = Settings(context).port
            return "http://$ip:$port"
        }
    }
}

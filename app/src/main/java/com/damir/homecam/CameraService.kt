package com.damir.homecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns the camera and everything built on top of it:
 * the JPEG/MJPEG pipeline, motion detection, microphone streaming, and clip
 * recording. A persistent notification keeps it obvious the camera is live.
 */
class CameraService : LifecycleService(), CameraControl {

    private lateinit var settings: Settings
    private lateinit var cameraExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())

    private var server: MjpegServer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val motionDetector = MotionDetector()
    private val audioStreamer = AudioStreamer()
    @Volatile
    private var clipRecorder: ClipRecorder? = null
    @Volatile
    private var recordingAvailable = false

    private var videoCapture: VideoCapture<Recorder>? = null
    @Volatile
    private var deviceRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastFrameMs = 0L
    private val minFrameIntervalMs = 66L // cap at ~15 fps
    private var lastMotionNotifyMs = 0L
    private var lastMotionEventMs = 0L
    @Volatile
    private var lastMotionMs = 0L
    private var started = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        ensureChannels(this)
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> if (!started) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Started without a visible activity (after a reboot, an app update, or a
        // restart by the system): Android does not allow the microphone then, so
        // audio waits until the app is opened.
        val background = intent == null || intent.getBooleanExtra(EXTRA_BACKGROUND, false)

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!enterForeground(allowMic = !background)) {
            if (started) return START_STICKY // keep the running camera as it is
            notifyStartFailed(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!background) startAudioIfEnabled()
        if (started) return START_STICKY

        started = true
        isRunning = true
        startServer()
        startOrientationListener()
        startCamera()
        startTicks()
        return START_STICKY
    }

    /**
     * The service has no screen to follow, so in automatic mode the picture's
     * orientation comes from the accelerometer.
     */
    private fun startOrientationListener() {
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                sensorOrientation = orientation
                deviceRotation = rotationFor(orientation, deviceRotation)
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        }
    }

    /**
     * Clockwise turn on top of the upright-portrait picture: the manual setting,
     * or, in automatic mode, the phone's rotation from the sensor. The JPEG
     * pipeline applies it itself rather than through CameraX's target rotation,
     * which did not turn the stream on every phone.
     */
    private fun currentTurn(): Int {
        val manual = settings.rotation
        if (manual != Settings.ROTATION_AUTO) return manual
        val degrees = surfaceDegrees(deviceRotation)
        return if (isBackCamera()) (360 - degrees) % 360 else degrees
    }

    private fun isBackCamera() = settings.lensFacing == CameraSelector.LENS_FACING_BACK

    /** Recordings take their orientation from CameraX; it applies to the next clip. */
    private fun updateVideoRotation() {
        val capture = videoCapture ?: return
        val turn = currentTurn()
        val target = surfaceRotation(if (isBackCamera()) (360 - turn) % 360 else turn)
        if (capture.targetRotation != target) capture.targetRotation = target
    }

    private fun startServer() {
        if (server != null) return
        server = MjpegServer(
            port = settings.port,
            username = settings.username,
            password = settings.password,
            recordingsDirProvider = { clipRecorder?.recordingsDir },
            control = this
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
                // Fixed: frames come out upright for a portrait phone and
                // currentTurn() does the rest (the default would follow the
                // display, which can now be in landscape).
                .setTargetRotation(Surface.ROTATION_0)
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
                    val capture = recorder.buildUseCase()
                    try {
                        provider.bindToLifecycle(this, selector, analysis, capture)
                        clipRecorder = recorder
                        videoCapture = capture
                        recordingAvailable = true
                    } catch (e: Exception) {
                        // Device can't do stream + record together: keep the stream.
                        Log.w(TAG, "bind with recording failed, streaming only", e)
                        provider.unbindAll()
                        provider.bindToLifecycle(this, selector, analysis)
                        clipRecorder = null
                        videoCapture = null
                        recordingAvailable = false
                    }
                } else {
                    provider.bindToLifecycle(this, selector, analysis)
                }

                updateVideoRotation()
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
            } else {
                motionDetector.reset() // no stale comparison when it is turned back on
            }
            val now = System.currentTimeMillis()
            if (now - lastFrameMs >= minFrameIntervalMs) {
                lastFrameMs = now
                val turn = currentTurn()
                val rotation = (image.imageInfo.rotationDegrees + turn) % 360
                val jpeg = YuvToJpeg.toJpeg(image, rotation, settings.jpegQuality)
                appliedTurn = turn
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
        lastMotionMs = now
        if (now - lastMotionEventMs >= MOTION_EVENT_INTERVAL_MS) {
            lastMotionEventMs = now
            MotionEvents.publish(now) // viewers' push notifications
        }
        if (now - lastMotionNotifyMs >= MOTION_NOTIFY_COOLDOWN_MS) {
            lastMotionNotifyMs = now
            notifyMotion()
        }
        if (recordingAvailable && settings.recordMode == Settings.RECORD_MOTION) {
            clipRecorder?.onMotion()
        }
    }

    // CameraControl: called by MjpegServer on its client threads.

    override fun statusJson(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return JSONObject()
            .put("name", Build.MODEL)
            .put("motionEnabled", settings.motionEnabled)
            .put("motionSensitivity", settings.motionSensitivity)
            .put("recordMode", settings.recordMode)
            .put("recordingAvailable", recordingAvailable)
            .put("recording", clipRecorder?.isRecording == true)
            .put("audio", audioStreamer.isActive)
            .put("rotation", settings.rotation)
            .put("sensorOrientation", sensorOrientation)
            .put("lastMotion", lastMotionMs)
            .put("battery", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            .put("charging", plugged)
            .toString()
    }

    override fun applySettings(fields: Map<String, String>) {
        fields["motionEnabled"]?.let { settings.motionEnabled = it == "true" }
        fields["motionSensitivity"]?.toIntOrNull()?.let { settings.motionSensitivity = it }
        fields["rotation"]?.toIntOrNull()?.let { settings.rotation = it }
        val mode = fields["recordMode"]
        if (mode != null && mode in Settings.RECORD_MODES && mode != settings.recordMode) {
            settings.recordMode = mode
            main.post { rebindCamera() } // recording needs a different set of use cases
        }
    }

    /** Main thread: applies a new recording mode by binding the camera again. */
    private fun rebindCamera() {
        if (!isRunning) return
        runCatching { clipRecorder?.stop() }
        clipRecorder = null
        videoCapture = null
        recordingAvailable = false
        startCamera()
    }

    private fun micAllowed(): Boolean = settings.audioEnabled &&
        ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun startAudioIfEnabled() {
        if (micAllowed()) audioStreamer.start()
    }

    private fun startTicks() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                updateVideoRotation()
                if (recordingAvailable && settings.recordingOn) {
                    clipRecorder?.tick(settings.clipSeconds)
                }
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }

    /** Returns false when Android refuses to run the camera in the foreground right now. */
    private fun enterForeground(allowMic: Boolean): Boolean {
        val notification = buildNotification(getString(R.string.notif_title), streamUrl(this))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (allowMic && micAllowed()) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not enter the foreground", e)
            false
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
        orientationListener?.disable()
        orientationListener = null
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
        private const val CHANNEL_ALERT_ID = "homecam_alerts"
        private const val NOTIF_ID = 1001
        private const val NOTIF_MOTION_ID = 1002
        private const val NOTIF_ALERT_ID = 1003
        private const val MOTION_NOTIFY_COOLDOWN_MS = 30_000L
        private const val MOTION_EVENT_INTERVAL_MS = 10_000L
        const val NO_SENSOR_DATA = Int.MIN_VALUE

        /** Last angle from the orientation sensor (0..359), -1 lying flat, or [NO_SENSOR_DATA]. */
        @Volatile
        var sensorOrientation = NO_SENSOR_DATA
            private set

        /** Clockwise turn applied to the last streamed frame. */
        @Volatile
        var appliedTurn = 0
            private set

        private fun surfaceDegrees(rotation: Int) = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        private fun surfaceRotation(degrees: Int) = when (degrees) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        private const val EXTRA_BACKGROUND = "background"
        const val ACTION_STOP = "com.damir.homecam.action.STOP"
        const val ACTION_REFRESH = "com.damir.homecam.action.REFRESH"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
        }

        /**
         * Maps the accelerometer angle (0..359, clockwise) to a Surface rotation.
         * Within 15° of a 45° boundary the current rotation is kept, so a phone
         * resting at an angle doesn't flip back and forth; lying flat keeps it too.
         */
        private fun rotationFor(orientation: Int, current: Int): Int {
            if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return current
            val quadrant = ((orientation + 45) / 90) % 4
            var offset = abs(orientation - quadrant * 90)
            if (offset > 180) offset = 360 - offset
            if (offset > 30) return current
            return when (quadrant) {
                1 -> Surface.ROTATION_270
                2 -> Surface.ROTATION_180
                3 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }

        /** Start from a broadcast receiver, with no visible activity. */
        fun startInBackground(context: Context) {
            val intent = Intent(context, CameraService::class.java).putExtra(EXTRA_BACKGROUND, true)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Lets a running service turn on audio now that the app is visible. */
        fun refresh(context: Context) {
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MOTION_ID,
                    context.getString(R.string.notif_motion_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    context.getString(R.string.notif_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        /** Tells the user an automatic start was blocked; tapping it starts the camera. */
        fun notifyStartFailed(context: Context) {
            ensureChannels(context)
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
                .setContentTitle(context.getString(R.string.notif_start_failed_title))
                .setContentText(context.getString(R.string.notif_start_failed_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, notification)
        }

        fun streamUrl(context: Context): String? {
            val ip = NetworkUtils.localIpv4() ?: return null
            val port = Settings(context).port
            return "http://$ip:$port"
        }

        /** The address to watch from anywhere, when the phone is on Tailscale. */
        fun tailscaleUrl(context: Context): String? {
            val ip = NetworkUtils.tailscaleIpv4() ?: return null
            val port = Settings(context).port
            return "http://$ip:$port"
        }
    }
}

package com.damir.homecamviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.damir.homecamviewer.databinding.ActivityControlBinding
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors

/**
 * A camera's settings, changed on the camera itself, plus this phone's own
 * choice of whether to get motion alerts from it. Each change is sent right away.
 */
class CameraControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlBinding
    private lateinit var store: CameraStore
    private lateinit var camera: Camera
    private val io = Executors.newSingleThreadExecutor()

    // Alerts need the notification permission; then ask to keep running in the background.
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestBatteryExemption() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CameraStore(this)
        camera = store.byId(intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run {
            finish()
            return
        }

        binding.title.text = camera.displayName
        binding.notifySwitch.isChecked = camera.notify
        binding.notifySwitch.setOnClickListener { setAlerts(binding.notifySwitch.isChecked) }

        // Click and touch listeners fire only for the user, not when show() fills in values.
        binding.motionSwitch.setOnClickListener {
            send("motionEnabled", binding.motionSwitch.isChecked.toString())
        }
        binding.sensitivitySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                send("motionSensitivity", slider.value.toInt().toString())
            }
        })
        mapOf(
            binding.rotAuto to -1,
            binding.rot0 to 0,
            binding.rot90 to 90,
            binding.rot180 to 180,
            binding.rot270 to 270
        ).forEach { (button, value) ->
            button.setOnClickListener { send("rotation", value.toString()) }
        }
        mapOf(
            binding.recordOff to "off",
            binding.recordMotion to "motion",
            binding.recordContinuous to "continuous"
        ).forEach { (button, value) ->
            button.setOnClickListener { send("recordMode", value) }
        }

        setControlsEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        binding.statusText.text = getString(R.string.status_checking)
        io.execute {
            val status = runCatching { CameraApi.status(camera) }.getOrNull()
            runOnUiThread { show(status) }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun send(field: String, value: String) {
        setControlsEnabled(false)
        io.execute {
            val status = runCatching { CameraApi.changeSettings(camera, mapOf(field to value)) }.getOrNull()
            runOnUiThread { show(status) }
        }
    }

    /** Fills the controls from the camera's state; null means it could not be reached. */
    private fun show(status: CameraStatus?) {
        if (isFinishing || isDestroyed) return
        if (status == null) {
            binding.statusText.text = getString(R.string.camera_unreachable)
            setControlsEnabled(false)
            return
        }
        binding.statusText.text = status.details(this)
        binding.motionSwitch.isChecked = status.motionEnabled
        binding.sensitivitySlider.value = status.motionSensitivity.coerceIn(1, 100).toFloat()
        binding.rotationGroup.check(
            when (status.rotation) {
                0 -> R.id.rot0
                90 -> R.id.rot90
                180 -> R.id.rot180
                270 -> R.id.rot270
                else -> R.id.rotAuto
            }
        )
        binding.recordGroup.check(
            when (status.recordMode) {
                "motion" -> R.id.recordMotion
                "continuous" -> R.id.recordContinuous
                else -> R.id.recordOff
            }
        )
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.motionSwitch.isEnabled = enabled
        binding.sensitivitySlider.isEnabled = enabled
        listOf(
            binding.rotAuto, binding.rot0, binding.rot90, binding.rot180, binding.rot270,
            binding.recordOff, binding.recordMotion, binding.recordContinuous
        ).forEach { it.isEnabled = enabled }
    }

    private fun setAlerts(on: Boolean) {
        camera = camera.copy(notify = on)
        store.update(camera)
        AlertService.sync(this)
        if (!on) return
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBatteryExemption()
        }
    }

    @SuppressLint("BatteryLife") // alerts must keep their connection while the phone sleeps
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val request = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }
    }

    companion object {
        const val EXTRA_ID = "camera_id"

        fun start(context: Context, camera: Camera) {
            context.startActivity(
                Intent(context, CameraControlActivity::class.java).putExtra(EXTRA_ID, camera.id)
            )
        }
    }
}

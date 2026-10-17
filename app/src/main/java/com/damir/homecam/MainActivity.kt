package com.damir.homecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.damir.homecam.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    private val ui = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private var previewRunning = false
    private var lastPreviewSeq = -1L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] ?: hasCamera()
        if (cameraGranted) {
            startStreaming()
        } else {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        bindSettingsToFields()

        binding.startBtn.setOnClickListener { onStartClicked() }
        binding.stopBtn.setOnClickListener {
            CameraService.stop(this)
            ui.postDelayed({ updateUi() }, 300)
        }
    }

    private fun bindSettingsToFields() {
        binding.portField.setText(settings.port.toString())
        binding.userField.setText(settings.username)
        binding.passField.setText(settings.password)
        binding.qualityField.setText(settings.jpegQuality.toString())
        binding.frontSwitch.isChecked = settings.useFrontCamera
        binding.audioSwitch.isChecked = settings.audioEnabled
        binding.motionSwitch.isChecked = settings.motionEnabled
        binding.sensitivitySlider.value = settings.motionSensitivity.coerceIn(1, 100).toFloat()
        binding.clipField.setText(settings.clipSeconds.toString())
        binding.recordGroup.check(
            when (settings.recordMode) {
                Settings.RECORD_MOTION -> R.id.recordMotion
                Settings.RECORD_CONTINUOUS -> R.id.recordContinuous
                else -> R.id.recordOff
            }
        )
    }

    private fun persistSettings() {
        settings.port = binding.portField.text?.toString()?.toIntOrNull() ?: Settings.DEFAULT_PORT
        settings.username = binding.userField.text?.toString().orEmpty()
        settings.password = binding.passField.text?.toString().orEmpty()
        settings.jpegQuality = binding.qualityField.text?.toString()?.toIntOrNull() ?: Settings.DEFAULT_QUALITY
        settings.useFrontCamera = binding.frontSwitch.isChecked
        settings.audioEnabled = binding.audioSwitch.isChecked
        settings.motionEnabled = binding.motionSwitch.isChecked
        settings.motionSensitivity = binding.sensitivitySlider.value.toInt()
        settings.clipSeconds = binding.clipField.text?.toString()?.toIntOrNull() ?: Settings.DEFAULT_CLIP_SECONDS
        settings.recordMode = when (binding.recordGroup.checkedRadioButtonId) {
            R.id.recordMotion -> Settings.RECORD_MOTION
            R.id.recordContinuous -> Settings.RECORD_CONTINUOUS
            else -> Settings.RECORD_OFF
        }
        // Reflect any clamping back into the fields.
        bindSettingsToFields()
    }

    private fun onStartClicked() {
        persistSettings()
        val needed = buildList {
            if (!hasCamera()) add(Manifest.permission.CAMERA)
            if (binding.audioSwitch.isChecked &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            startStreaming()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startStreaming() {
        if (!hasCamera()) {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            return
        }
        CameraService.start(this)
        ui.postDelayed({ updateUi() }, 400)
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun updateUi() {
        val running = CameraService.isRunning
        binding.startBtn.isEnabled = !running
        binding.stopBtn.isEnabled = running
        setSettingsEnabled(!running)

        if (running) {
            binding.status.text = getString(R.string.status_running)
            val url = CameraService.streamUrl(this)
            if (url != null) {
                binding.urlHint.visibility = View.VISIBLE
                binding.url.visibility = View.VISIBLE
                binding.url.text = url
            } else {
                binding.urlHint.visibility = View.VISIBLE
                binding.url.visibility = View.VISIBLE
                binding.url.text = getString(R.string.no_network)
            }
        } else {
            binding.status.text = getString(R.string.status_stopped)
            binding.urlHint.visibility = View.GONE
            binding.url.visibility = View.GONE
            binding.preview.setImageDrawable(null)
        }
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        binding.portField.isEnabled = enabled
        binding.userField.isEnabled = enabled
        binding.passField.isEnabled = enabled
        binding.qualityField.isEnabled = enabled
        binding.frontSwitch.isEnabled = enabled
        binding.audioSwitch.isEnabled = enabled
        binding.motionSwitch.isEnabled = enabled
        binding.sensitivitySlider.isEnabled = enabled
        binding.clipField.isEnabled = enabled
        binding.recordOff.isEnabled = enabled
        binding.recordMotion.isEnabled = enabled
        binding.recordContinuous.isEnabled = enabled
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        startPreviewLoop()
    }

    override fun onPause() {
        super.onPause()
        previewRunning = false
    }

    override fun onDestroy() {
        previewRunning = false
        decodeExecutor.shutdown()
        super.onDestroy()
    }

    /** Mirrors the streamed frames into the on-screen preview, in process. */
    private fun startPreviewLoop() {
        if (previewRunning) return
        previewRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!previewRunning) return
                if (CameraService.isRunning) {
                    val frame = FrameBus.snapshot()
                    if (frame != null && frame.seq != lastPreviewSeq) {
                        lastPreviewSeq = frame.seq
                        val bytes = frame.bytes
                        decodeExecutor.execute {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            val bmp = runCatching {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            }.getOrNull()
                            if (bmp != null && previewRunning) {
                                ui.post { binding.preview.setImageBitmap(bmp) }
                            }
                        }
                    }
                }
                ui.postDelayed(this, 120)
            }
        }
        ui.post(tick)
    }
}

package com.damir.homecamviewer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.damir.homecamviewer.databinding.ActivityViewerBinding

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var camera: Camera

    private var reader: MjpegReader? = null
    private var audioPlayer: AudioPlayer? = null
    private var events: EventWatcher? = null
    private var audioOn = false
    private val hideBadge = Runnable { binding.motionBadge.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = CameraStore(this).byId(intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run {
            finish()
            return
        }
        title = camera.displayName

        binding.audioBtn.setOnClickListener { toggleAudio() }
        binding.recordingsBtn.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(camera.recordingsUrl)))
            }
        }
        binding.controlsBtn.setOnClickListener { CameraControlActivity.start(this, camera) }
    }

    override fun onStart() {
        super.onStart()
        startVideo()
        if (audioOn) startAudio()
        events = EventWatcher(camera) { _, _ -> runOnUiThread { showMotion() } }.also { it.start() }
    }

    override fun onStop() {
        super.onStop()
        stopVideo()
        stopAudio()
        events?.stop()
        events = null
    }

    private fun showMotion() {
        binding.motionBadge.visibility = View.VISIBLE
        binding.motionBadge.removeCallbacks(hideBadge)
        binding.motionBadge.postDelayed(hideBadge, BADGE_MS)
    }

    private fun startVideo() {
        showStatus(getString(R.string.connecting))
        reader?.stop()
        reader = MjpegReader(
            url = camera.videoUrl,
            authHeader = camera.authHeader(),
            onFrame = { bytes ->
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bmp != null) {
                    runOnUiThread {
                        binding.image.setImageBitmap(bmp)
                        binding.status.visibility = View.GONE
                    }
                }
            },
            onError = { runOnUiThread { showStatus(getString(R.string.connection_failed)) } }
        ).also { it.start() }
    }

    private fun stopVideo() {
        reader?.stop()
        reader = null
    }

    private fun toggleAudio() {
        if (audioOn) {
            audioOn = false
            stopAudio()
            binding.audioBtn.text = getString(R.string.audio_off)
        } else {
            audioOn = true
            startAudio()
            binding.audioBtn.text = getString(R.string.audio_on)
        }
    }

    private fun startAudio() {
        audioPlayer?.stop()
        audioPlayer = AudioPlayer(
            url = camera.audioUrl,
            authHeader = camera.authHeader(),
            onError = { /* audio is best-effort; ignore failures */ }
        ).also { it.start() }
    }

    private fun stopAudio() {
        audioPlayer?.stop()
        audioPlayer = null
    }

    private fun showStatus(text: String) {
        binding.status.text = text
        binding.status.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_ID = "camera_id"
        private const val BADGE_MS = 8_000L

        fun intentFor(context: Context, camera: Camera): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_ID, camera.id)
    }
}

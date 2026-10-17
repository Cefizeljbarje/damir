package com.damir.homecamviewer

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

    private var videoUrl: String = ""
    private var audioUrl: String = ""
    private var recordingsUrl: String = ""
    private var auth: String? = null

    private var reader: MjpegReader? = null
    private var audioPlayer: AudioPlayer? = null
    private var audioOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        title = intent.getStringExtra(EXTRA_NAME).orEmpty()
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
        recordingsUrl = intent.getStringExtra(EXTRA_RECORDINGS_URL).orEmpty()
        auth = intent.getStringExtra(EXTRA_AUTH)

        binding.audioBtn.setOnClickListener { toggleAudio() }
        binding.recordingsBtn.setOnClickListener {
            if (recordingsUrl.isNotEmpty()) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(recordingsUrl)))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startVideo()
        if (audioOn) startAudio()
    }

    override fun onStop() {
        super.onStop()
        stopVideo()
        stopAudio()
    }

    private fun startVideo() {
        showStatus(getString(R.string.connecting))
        reader?.stop()
        reader = MjpegReader(
            url = videoUrl,
            authHeader = auth,
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
        if (audioUrl.isEmpty()) return
        audioPlayer?.stop()
        audioPlayer = AudioPlayer(
            url = audioUrl,
            authHeader = auth,
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
        const val EXTRA_NAME = "name"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_RECORDINGS_URL = "recordings_url"
        const val EXTRA_AUTH = "auth"
    }
}

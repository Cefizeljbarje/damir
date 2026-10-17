package com.damir.homecamviewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.damir.homecamviewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CameraStore
    private var cameras: List<Camera> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CameraStore(this)

        binding.addBtn.setOnClickListener {
            startActivity(Intent(this, AddCameraActivity::class.java).putExtra(EXTRA_INDEX, -1))
        }

        binding.cameraList.setOnItemClickListener { _, _, position, _ ->
            openViewer(cameras[position])
        }

        binding.cameraList.setOnItemLongClickListener { _, _, position, _ ->
            showItemMenu(position)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        cameras = store.all()
        binding.empty.visibility = if (cameras.isEmpty()) View.VISIBLE else View.GONE
        binding.cameraList.adapter = object : ArrayAdapter<Camera>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, cameras
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val cam = cameras[position]
                view.findViewById<TextView>(android.R.id.text1).text = cam.name.ifEmpty { cam.baseUrl }
                view.findViewById<TextView>(android.R.id.text2).text = cam.baseUrl
                return view
            }
        }
    }

    private fun openViewer(cam: Camera) {
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_NAME, cam.name)
            .putExtra(ViewerActivity.EXTRA_VIDEO_URL, cam.videoUrl)
            .putExtra(ViewerActivity.EXTRA_AUDIO_URL, cam.audioUrl)
            .putExtra(ViewerActivity.EXTRA_RECORDINGS_URL, cam.recordingsUrl)
            .putExtra(ViewerActivity.EXTRA_AUTH, cam.authHeader())
        startActivity(intent)
    }

    private fun showItemMenu(position: Int) {
        val cam = cameras[position]
        AlertDialog.Builder(this)
            .setTitle(cam.name.ifEmpty { cam.baseUrl })
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, AddCameraActivity::class.java).putExtra(EXTRA_INDEX, position)
                    )
                    1 -> {
                        store.removeAt(position)
                        refresh()
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}

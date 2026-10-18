package com.damir.homecamviewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.damir.homecamviewer.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CameraStore
    private var cameras: List<Camera> = emptyList()

    /** Camera id -> its status; null means unreachable, no entry means not checked yet. */
    private val statuses = HashMap<String, CameraStatus?>()
    private val io = Executors.newFixedThreadPool(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CameraStore(this)

        binding.addBtn.setOnClickListener {
            startActivity(Intent(this, AddCameraActivity::class.java).putExtra(EXTRA_INDEX, -1))
        }

        binding.cameraList.setOnItemClickListener { _, _, position, _ ->
            startActivity(ViewerActivity.intentFor(this, cameras[position]))
        }

        binding.cameraList.setOnItemLongClickListener { _, _, position, _ ->
            showItemMenu(position)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkStatuses()
        AlertService.sync(this)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        cameras = store.all()
        binding.empty.visibility = if (cameras.isEmpty()) View.VISIBLE else View.GONE
        binding.cameraList.adapter = object : ArrayAdapter<Camera>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, cameras
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val cam = cameras[position]
                view.findViewById<TextView>(android.R.id.text1).text = cam.displayName
                view.findViewById<TextView>(android.R.id.text2).text = describe(cam)
                return view
            }
        }
    }

    private fun describe(cam: Camera): String {
        val state = when {
            !statuses.containsKey(cam.id) -> getString(R.string.status_checking)
            else -> statuses[cam.id]?.shortSummary(this) ?: getString(R.string.status_offline)
        }
        val alerts = if (cam.notify) " · " + getString(R.string.status_alerts_on) else ""
        return cam.baseUrl + "\n" + state + alerts
    }

    private fun checkStatuses() {
        for (camera in cameras) {
            io.execute {
                val status = runCatching { CameraApi.status(camera) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    statuses[camera.id] = status
                    (binding.cameraList.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun showItemMenu(position: Int) {
        val cam = cameras[position]
        val items = arrayOf(
            getString(R.string.camera_settings),
            getString(R.string.edit),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(cam.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> CameraControlActivity.start(this, cam)
                    1 -> startActivity(
                        Intent(this, AddCameraActivity::class.java).putExtra(EXTRA_INDEX, position)
                    )
                    2 -> {
                        store.removeAt(position)
                        refresh()
                        AlertService.sync(this)
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}

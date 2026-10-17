package com.damir.homecamviewer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.damir.homecamviewer.databinding.ActivityAddBinding
import java.util.UUID

class AddCameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddBinding
    private lateinit var store: CameraStore
    private var index: Int = -1
    private var existingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CameraStore(this)

        index = intent.getIntExtra(MainActivity.EXTRA_INDEX, -1)
        if (index >= 0) {
            title = getString(R.string.edit_camera)
            store.get(index)?.let { cam ->
                existingId = cam.id
                binding.nameField.setText(cam.name)
                binding.addressField.setText(cam.baseUrl)
                binding.userField.setText(cam.username)
                binding.passField.setText(cam.password)
            }
        } else {
            title = getString(R.string.add_camera)
        }

        binding.saveBtn.setOnClickListener { save() }
        binding.cancelBtn.setOnClickListener { finish() }
    }

    private fun save() {
        val url = Camera.normalizeUrl(binding.addressField.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_LONG).show()
            return
        }
        val name = binding.nameField.text?.toString()?.trim().orEmpty()
        val camera = Camera(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            baseUrl = url,
            username = binding.userField.text?.toString()?.trim().orEmpty(),
            password = binding.passField.text?.toString().orEmpty()
        )
        if (index >= 0) store.update(index, camera) else store.add(camera)
        finish()
    }
}

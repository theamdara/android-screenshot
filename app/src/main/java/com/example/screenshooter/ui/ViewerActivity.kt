package com.example.screenshooter.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.screenshooter.databinding.ActivityViewerBinding

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
    }

    private lateinit var binding: ActivityViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr != null) {
            val uri = Uri.parse(uriStr)
            binding.ivFull.setImageURI(uri)
            // Best-effort title from the last path segment.
            binding.toolbar.title = uri.lastPathSegment ?: "Screenshot"
        } else {
            finish()
        }
    }
}

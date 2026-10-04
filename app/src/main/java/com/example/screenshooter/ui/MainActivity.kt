package com.example.screenshooter.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.screenshooter.R
import com.example.screenshooter.ScreenshotStore
import com.example.screenshooter.databinding.ActivityMainBinding
import com.example.screenshooter.overlay.CaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScreenshotAdapter

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permission to capture screen was denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* not fatal; the service still runs */ }

    // Only relevant on Android 9 (API 28); MediaStore needs no storage permission on 10+.
    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            results.values.any { !it }
        ) {
            Toast.makeText(this, "Storage permission is needed to save & view screenshots", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ScreenshotAdapter(
            onClick = { uri -> openViewer(uri) },
            onShare = { uri -> shareUri(uri) },
            onDelete = { uri -> deleteShot(uri) }
        )
        binding.recyclerGallery.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerGallery.adapter = adapter

        binding.btnCaptureMode.setOnClickListener { onToggleCapture() }
    }

    override fun onResume() {
        super.onResume()
        refreshGallery()
        binding.btnCaptureMode.setText(
            if (isCaptureRunning()) R.string.stop_capture else R.string.start_capture
        )
    }

    private fun onToggleCapture() {
        // If capture mode is already running, the button acts as "Stop".
        if (isCaptureRunning()) {
            stopCaptureService()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.grant_overlay_permission, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Android 9 needs explicit read+write storage permission for the public album.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val missing = listOf(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) requestStorage.launch(missing.toTypedArray())
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        requestProjection.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.btnCaptureMode.setText(R.string.stop_capture)
    }

    private fun stopCaptureService() {
        stopService(Intent(this, CaptureService::class.java))
        binding.btnCaptureMode.setText(R.string.start_capture)
    }

    /** True if the capture foreground service is currently running. */
    private fun isCaptureRunning(): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (s in am.getRunningServices(50)) {
                if (s.service?.className == CaptureService::class.java.simpleName && s.foreground) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshGallery() {
        val shots = ScreenshotStore.list(this)
        adapter.submit(shots)
        binding.tvEmpty.visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_URI, uri.toString())
        })
    }

    private fun shareUri(uri: Uri) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share)))
    }

    private fun deleteShot(uri: Uri) {
        ScreenshotStore.delete(this, uri)
        refreshGallery()
    }
}

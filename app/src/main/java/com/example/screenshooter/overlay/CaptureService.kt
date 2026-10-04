package com.example.screenshooter.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.example.screenshooter.R
import com.example.screenshooter.ScreenshotStore
import com.example.screenshooter.ui.MainActivity

/**
 * Foreground service that:
 *  - keeps the MediaProjection session alive (required for screen capture),
 *  - shows a draggable floating button, and
 *  - captures one frame via an ImageReader-backed VirtualDisplay when the button is tapped.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenshooter.ACTION_START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "capture_mode"
    }

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var capturing = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Continuously drains the ImageReader so its small buffer pool never fills up.
     * Without this, acquireLatestImage() throws once maxImages buffers are held.
     */
    @Volatile private var drainThread: Thread? = null
    @Volatile private var draining = false

    private fun startDraining() {
        if (drainThread?.isAlive == true) return
        draining = true
        drainThread = Thread {
            while (draining) {
                val reader = imageReader ?: break
                try {
                    val img = reader.acquireLatestImage()
                    if (img != null) {
                        // Just release to free the buffer; we only keep frames on demand.
                        img.close()
                    } else {
                        Thread.sleep(50)
                    }
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }
        }.also { it.start() }
    }

    private fun stopDraining() {
        draining = false
        drainThread?.let {
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        drainThread = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    Toast.makeText(this, "MediaProjection data missing", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Idempotent: if a previous capture session is still around (e.g. the user
                // tapped Start again), tear it down first so we don't leak a stale
                // MediaProjection/VirtualDisplay that would produce no frames.
                if (mediaProjection != null) {
                    android.util.Log.w("CaptureService", "re-init: tearing down existing projection")
                    cleanupProjection()
                }
                ensureChannel()
                startForegroundCompat()
                initProjection(resultCode, data)
                showFloatingButton()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        // On Q+ the mediaProjection type is declared in the manifest; on P (minSdk) a plain
        // foreground start is enough since FGS types did not exist yet.
        val notification = buildNotification()
        startForeground(NOTIF_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_channel_desc)
            }
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data) ?: return
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupProjection()
                stopSelf()
            }
        }, null)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenshotAppVD", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        android.util.Log.i("CaptureService", "projection initialized ${width}x${height}@${dpi}")
        startDraining()
    }

    // ---------- Floating button ----------

    private fun showFloatingButton() {
        if (floatingButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val button = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(96, 96)
            background = resources.getDrawable(R.drawable.bg_floating_button, theme)
            isClickable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_capture)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(20, 20, 20, 20)
        }
        button.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            96, 96, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }

        // Draggable + tappable
        var downX = 0f; var downY = 0f
        var initialX = 0; var initialY = 0
        var moved = false
        button.setOnTouchListener { _, event ->
            android.util.Log.d("CaptureService", "touch action=${event.action} raw=(${event.rawX},${event.rawY})")
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialX = params.x; initialY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(button, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    android.util.Log.d("CaptureService", "touch UP moved=$moved")
                    if (!moved) captureScreenshot()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(button, params)
            floatingButton = button
            android.util.Log.i("CaptureService", "floating button added at (${params.x},${params.y})")
        } catch (e: Exception) {
            android.util.Log.e("CaptureService", "failed to add floating button", e)
        }
    }

    // ---------- Capture ----------

    private fun captureScreenshot() {
        if (capturing) return
        capturing = true
        val reader = imageReader
        if (reader == null || mediaProjection == null) {
            android.util.Log.e("CaptureService", "capture aborted: reader=${reader != null} projection=${mediaProjection != null}")
            Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
            capturing = false
            return
        }
        // Run off the main thread so we can retry/wait for a frame without jank.
        Thread {
            try {
                val image = acquireFrameWithRetry(reader)
                    ?: throw IllegalStateException("no frame available after retries")

                // Build the bitmap from the captured frame.
                val bmp = bitmapFromImage(image)
                image.close()

                // Save to the public Pictures/Screenshots album via MediaStore.
                val name = ScreenshotStore.timestampName()
                val uri = ScreenshotStore.save(this, name) { contentUri ->
                    contentResolver.openOutputStream(contentUri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                }

                if (uri != null) {
                    android.util.Log.i("CaptureService", "saved to $uri")
                    mainHandler.post { Toast.makeText(this, getString(R.string.saved_toast), Toast.LENGTH_SHORT).show() }
                } else {
                    throw IllegalStateException("MediaStore save returned null")
                }
            } catch (e: Exception) {
                android.util.Log.e("CaptureService", "capture failed", e)
                mainHandler.post { Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show() }
            } finally {
                capturing = false
            }
        }.start()
    }

    /** Converts a captured Image into a Bitmap (handles row/pixel strides safely). */
    private fun bitmapFromImage(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer as java.nio.ByteBuffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        android.util.Log.i(
            "CaptureService",
            "plane geo: w=$width h=$height rowStride=$rowStride pixelStride=$pixelStride " +
                "buf.capacity=${buffer.capacity()} buf.limit=${buffer.limit()}"
        )

        val avail = minOf(buffer.remaining(), buffer.capacity())
        val raw = ByteArray(avail)
        val savedPos = buffer.position()
        buffer.position(0)
        buffer.get(raw, 0, avail)
        buffer.position(savedPos)

        val pixels = IntArray(width * height)
        if (pixelStride == 4 && rowStride >= width * 4) {
            var outIdx = 0
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    val p = rowStart + col * 4
                    if (p + 3 < raw.size) {
                        pixels[outIdx++] = (raw[p].toInt() and 0xFF) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            ((raw[p + 2].toInt() and 0xFF) shl 16) or
                            ((raw[p + 3].toInt() and 0xFF) shl 24)
                    } else {
                        pixels[outIdx++] = 0
                    }
                }
            }
        } else {
            android.util.Log.w("CaptureService", "generic stride path pixel=$pixelStride row=$rowStride")
            var outIdx = 0
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    val p = rowStart + col * pixelStride
                    if (p + 3 < raw.size) {
                        val b0 = raw[p].toInt() and 0xFF
                        val b1 = raw[p + 1].toInt() and 0xFF
                        val b2 = raw[p + 2].toInt() and 0xFF
                        val b3 = raw[p + 3].toInt() and 0xFF
                        pixels[outIdx++] = (b3 shl 24) or (b0 shl 16) or (b1 shl 8) or b2
                    } else {
                        pixels[outIdx++] = 0
                    }
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Tries to get a frame from the reader. The virtual display can take a moment to
     * produce its first buffer, so retry briefly before giving up.
     */
    private fun acquireFrameWithRetry(reader: ImageReader): Image? {
        for (attempt in 1..60) { // up to ~6s; the virtual display can take a few seconds
            try {
                val img = reader.acquireLatestImage()
                if (img != null) return img
            } catch (e: Exception) {
                android.util.Log.w("CaptureService", "acquire attempt $attempt failed", e)
            }
            Thread.sleep(100)
        }
        return null
    }

    // ---------- Cleanup ----------

    private fun cleanupProjection() {
        stopDraining()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        floatingButton = null
    }

    override fun onDestroy() {
        removeFloatingButton()
        cleanupProjection()
        super.onDestroy()
    }
}

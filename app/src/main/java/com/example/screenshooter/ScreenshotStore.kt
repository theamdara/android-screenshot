package com.example.screenshooter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves screenshots to the public Pictures/Screenshots album via MediaStore so they
 * show up in the system gallery, and lists them back out for the in-app gallery.
 */
object ScreenshotStore {

    // Environment.DIRECTORY_SCREENSHOTS only exists on API 29+, so reference the
    // literal path segment instead to stay compatible with Android 9 (API 28).
    private const val SCREENSHOTS_DIR = "Screenshots"

    data class Shot(val uri: Uri, val displayName: String, val dateAdded: Long)

    fun timestampName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "Screenshot_${sdf.format(Date())}.png"
    }

    /**
     * Inserts a screenshot into MediaStore and writes the bitmap bytes to it.
     * Returns the content Uri of the saved image, or null on failure.
     */
    fun save(context: Context, displayName: String, writeBytes: (Uri) -> Boolean): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SCREENSHOTS_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // Pre-Q: place directly in the Screenshots directory.
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SCREENSHOTS_DIR
                )
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(dir, displayName).absolutePath)
            }
        }

        val uri: Uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        val ok = try {
            writeBytes(uri)
        } catch (e: Exception) {
            android.util.Log.e("ScreenshotStore", "write failed", e)
            false
        }

        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }

        // Mark no longer pending so it becomes visible to other apps (Q+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    /** All screenshots in the Screenshots album, newest first. */
    fun list(context: Context): List<Shot> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        // On Q+ we can filter by RELATIVE_PATH; on older versions that column doesn't
        // exist, so query all images (we only ever save into the Screenshots album anyway).
        val selection: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            null
        }
        val selectionArgs: Array<String>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$SCREENSHOTS_DIR%")
        } else {
            null
        }

        val shots = mutableListOf<Shot>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                shots.add(Shot(uri, c.getString(nameCol) ?: "screenshot", c.getLong(dateCol)))
            }
        }
        return shots
    }

    /** Deletes a screenshot by its content Uri. */
    fun delete(context: Context, uri: Uri): Boolean {
        val n = context.contentResolver.delete(uri, null, null)
        return n > 0
    }
}

package cn.rainnotify.app.auth

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.util.UUID

/** Tracks only the one image created explicitly by this App's save button. */
class QrGallery(private val context: Context) {
    private val preferences = context.getSharedPreferences("login_qr_gallery", Context.MODE_PRIVATE)

    fun removeOwnedCopy(): Boolean {
        val saved = preferences.getString("owned_uri", null) ?: return true
        return try {
            val uri = Uri.parse(saved)
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
            context.contentResolver.delete(uri, null, null)
            preferences.edit().remove("owned_uri").commit()
        } catch (_: Exception) { false }
    }

    fun save(bitmap: Bitmap) {
        if (!removeOwnedCopy()) throw RainFailure(FailureReason.STORAGE, "qr_cleanup")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RainNotify-login-" + UUID.randomUUID() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RainNotify")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw RainFailure(FailureReason.STORAGE, "qr_save")
        try {
            if (!preferences.edit().putString("owned_uri", uri.toString()).commit()) {
                throw RainFailure(FailureReason.STORAGE, "qr_save")
            }
            resolver.openOutputStream(uri)?.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw RainFailure(FailureReason.STORAGE, "qr_save")
                }
            } ?: throw RainFailure(FailureReason.STORAGE, "qr_save")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            preferences.edit().remove("owned_uri").commit()
            throw RainFailure(FailureReason.STORAGE, "qr_save")
        }
    }
}

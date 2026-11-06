package cn.rainnotify.app.answer

import android.graphics.BitmapFactory
import android.os.SystemClock
import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionImages

/** Bounded comparison against exactly the image files supplied to the solver. */
object AnswerImages {
    fun compare(prepared: PreparedQuestion, current: QuestionContent, timeoutMs: Long, run: RunControl): ImageComparison {
        if (prepared.images.size != current.assets.size) return ImageComparison.DIFFERENT
        val images = QuestionImages()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var unavailable = false
        run.onCancel { images.cancel() }
        try {
            for ((index, asset) in current.assets.withIndex()) {
                run.check()
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return ImageComparison.UNAVAILABLE
                val comparison = try {
                    compareBytes(prepared.images[index].readBytes(), images.fetch(asset.url, remaining))
                } catch (_: Exception) { ImageComparison.UNAVAILABLE }
                if (comparison == ImageComparison.DIFFERENT) return comparison
                if (comparison == ImageComparison.UNAVAILABLE) unavailable = true
            }
            return if (unavailable) ImageComparison.UNAVAILABLE else ImageComparison.MATCH
        } finally { run.onCancel(null); images.cancel() }
    }

    internal fun compareBytes(before: ByteArray, after: ByteArray): ImageComparison {
        if (before.contentEquals(after)) return ImageComparison.MATCH
        // Different encodings/metadata can represent identical pixels. Animated formats require all frames.
        if (listOf(before, after).any { it.size >= 3 && String(it, 0, 3, Charsets.US_ASCII) == "GIF" })
            return ImageComparison.UNAVAILABLE
        for (bytes in listOf(before, after)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 4_194_304) return ImageComparison.UNAVAILABLE
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val first = BitmapFactory.decodeByteArray(before, 0, before.size, options) ?: return ImageComparison.UNAVAILABLE
        try {
            val second = BitmapFactory.decodeByteArray(after, 0, after.size, options) ?: return ImageComparison.UNAVAILABLE
            try {
                if (first.width != second.width || first.height != second.height) return ImageComparison.DIFFERENT
                val left = IntArray(first.width)
                val right = IntArray(first.width)
                for (row in 0 until first.height) {
                    first.getPixels(left, 0, first.width, 0, row, first.width, 1)
                    second.getPixels(right, 0, second.width, 0, row, second.width, 1)
                    if (!left.contentEquals(right)) return ImageComparison.DIFFERENT
                }
                return ImageComparison.MATCH
            } finally { second.recycle() }
        } finally { first.recycle() }
    }
}

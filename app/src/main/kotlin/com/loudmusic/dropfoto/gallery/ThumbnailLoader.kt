package com.loudmusic.dropfoto.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import com.loudmusic.dropfoto.ptpip.ByteArraySink
import com.loudmusic.dropfoto.ptpip.OperationCode
import com.loudmusic.dropfoto.ptpip.PtpCamera
import com.loudmusic.dropfoto.ptpip.PtpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads thumbnails from memory, then disk, then the camera (GetThumb), rotated upright using the
 * EXIF orientation read from the first 8 KB of the file. Disk entries are keyed by camera + file
 * name + size, so they stay valid across reconnects and work while offline.
 */
class ThumbnailLoader(context: Context, private val camera: StateFlow<PtpCamera?>, private val serial: StateFlow<String?>) {
    private val dir = File(context.cacheDir, "thumbs")
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(file: CardFile): Bitmap? = memory.get(key(file))

    suspend fun thumbnail(file: CardFile): Bitmap? {
        val key = key(file)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val stored = ROTATIONS.map { File(dir, "$key.r$it.jpg") to it }.firstOrNull { it.first.exists() }
            val (bytes, rotation) = if (stored != null) {
                stored.first.readBytes() to stored.second
            } else {
                val cam = camera.value ?: return@withContext null
                try {
                    val thumb = cam.getThumb(file.handle)
                    val rotation = readRotation(cam, file)
                    dir.mkdirs()
                    File(dir, "$key.r$rotation.jpg").writeBytes(thumb)
                    thumb to rotation
                } catch (_: PtpException) {
                    return@withContext null
                }
            }
            decode(bytes, rotation)?.also { memory.put(key, it) }
        }
    }

    /** A bigger preview for the viewer: Nikon's large thumbnail when available. */
    suspend fun preview(file: CardFile): Bitmap? = withContext(Dispatchers.IO) {
        val cam = camera.value ?: return@withContext null
        try {
            val bytes = cam.getLargeThumb(file.handle) ?: cam.getThumb(file.handle)
            decode(bytes, readRotation(cam, file), maxSide = 2048)
        } catch (_: PtpException) {
            null
        }
    }

    private suspend fun readRotation(cam: PtpCamera, file: CardFile): Int {
        if (!cam.deviceInfo.supports(OperationCode.GET_PARTIAL_OBJECT)) return 0
        val sink = ByteArraySink()
        cam.getPartialObject(file.handle, 0, minOf(ExifOrientation.HEAD_BYTES.toLong(), file.size).toInt(), sink)
        return ExifOrientation.rotationDegrees(sink.toByteArray()) ?: 0
    }

    private companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)
    }

    private fun key(file: CardFile) = "${serial.value ?: "camera"}_${file.filename}_${file.size}".filter { it.isLetterOrDigit() || it == '_' }

    private fun decode(bytes: ByteArray, rotation: Int, maxSide: Int = 0): Bitmap? {
        val options = BitmapFactory.Options()
        if (maxSide > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            options.inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

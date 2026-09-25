package com.loudmusic.nikonsync.cli

import com.loudmusic.nikonsync.ptpip.ObjectFormat
import com.loudmusic.nikonsync.ptpip.fake.FakeObject
import com.loudmusic.nikonsync.ptpip.formatPtpDateTime
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO

/** Builds a fake card from real files, so the app can be developed against your own photos. */
internal object FakeCards {
    fun fromDirectory(dir: File): List<FakeObject> {
        require(dir.isDirectory) { "${dir.path} is not a folder" }
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }
        val objects = mutableListOf(
            FakeObject(handle = 1, parent = 0, filename = "DCIM", format = ObjectFormat.ASSOCIATION),
            FakeObject(handle = 2, parent = 1, filename = "100D5500", format = ObjectFormat.ASSOCIATION),
        )
        val jpegThumbs = mutableMapOf<String, ByteArray?>()
        fun thumbFor(f: File): ByteArray? = when (f.extension.uppercase()) {
            "JPG", "JPEG" -> jpegThumbs.getOrPut(f.nameWithoutExtension) { makeThumb(f) }
            else -> files.firstOrNull { it.nameWithoutExtension == f.nameWithoutExtension && it.extension.uppercase() in setOf("JPG", "JPEG") }
                ?.let { jpegThumbs.getOrPut(it.nameWithoutExtension) { makeThumb(it) } }
        }
        files.forEachIndexed { i, f ->
            val format = when (f.extension.uppercase()) {
                "JPG", "JPEG" -> ObjectFormat.EXIF_JPEG
                "MOV" -> ObjectFormat.QUICKTIME
                else -> ObjectFormat.UNDEFINED
            }
            val modified = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault())
            objects += FakeObject(
                handle = 0x100 + i,
                parent = 2,
                filename = f.name,
                format = format,
                data = f.readBytes(),
                thumb = thumbFor(f),
                captureDate = formatPtpDateTime(modified),
            )
        }
        return objects
    }

    private fun makeThumb(file: File): ByteArray? = runCatching {
        val source = ImageIO.read(file) ?: return null
        val scale = minOf(160.0 / source.width, 120.0 / source.height)
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val thumb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        thumb.createGraphics().apply {
            drawImage(source.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }
        ByteArrayOutputStream().also { ImageIO.write(thumb, "jpg", it) }.toByteArray()
    }.getOrNull()
}

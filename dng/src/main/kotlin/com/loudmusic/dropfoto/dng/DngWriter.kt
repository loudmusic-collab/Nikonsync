package com.loudmusic.dropfoto.dng

import java.io.File
import java.io.RandomAccessFile

/** Colour data a DNG needs that the NEF itself doesn't carry in standard form. */
data class CameraProfile(
    /** DNG ColorMatrix1 (XYZ D65 -> camera), times 10000, row-major. */
    val colorMatrixD65: IntArray,
    val uniqueModel: String,
)

/** Cameras DropFoto can convert. Matrices are Adobe's D65 matrices as shipped in LibRaw. */
object CameraProfiles {
    private val profiles = mapOf(
        "NIKON D5500" to CameraProfile(
            intArrayOf(8821, -2938, -785, -4178, 12142, 2287, -824, 1651, 6860),
            "Nikon D5500",
        ),
    )

    fun forModel(model: String): CameraProfile? = profiles[model.trim().uppercase()]

    val supportedModels: Set<String> get() = profiles.keys
}

class UnsupportedCameraException(model: String) :
    Exception("DNG conversion isn't available for $model yet")

/**
 * Converts a Nikon NEF to a DNG 1.4 file: the raw CFA data, decoded and losslessly re-compressed
 * (tiled lossless JPEG), with black/white levels, as-shot white balance, the camera's colour matrix,
 * the default crop, orientation and the main EXIF fields. The result opens in Snapseed, Lightroom and
 * any other DNG-capable editor.
 */
object NefToDng {
    private const val TILE = 256

    fun convert(nefBytes: ByteArray, output: File, software: String = "DropFoto") {
        val nef = NefFile(nefBytes)
        val profile = CameraProfiles.forModel(nef.model) ?: throw UnsupportedCameraException(nef.model)
        val decoder = NikonDecoder(nef)
        val width = nef.width
        val height = nef.height
        val tilesAcross = (width + TILE - 1) / TILE
        val tilesDown = (height + TILE - 1) / TILE
        val paddedWidth = tilesAcross * TILE
        val tileOffsets = LongArray(tilesAcross * tilesDown)
        val tileCounts = LongArray(tilesAcross * tilesDown)

        output.delete()
        RandomAccessFile(output, "rw").use { file ->
            file.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0)) // IFD0 offset patched later
            val band = IntArray(TILE * paddedWidth)
            var bandRows = 0
            var tileRow = 0

            fun flushBand() {
                // Pad short bands (bottom edge) by repeating the last row.
                for (r in bandRows until TILE) System.arraycopy(band, (bandRows - 1) * paddedWidth, band, r * paddedWidth, paddedWidth)
                for (tx in 0 until tilesAcross) {
                    val bytes = LosslessJpeg.encode(band, tx * TILE, paddedWidth, TILE, TILE)
                    val index = tileRow * tilesAcross + tx
                    tileOffsets[index] = file.filePointer
                    tileCounts[index] = bytes.size.toLong()
                    file.write(bytes)
                    if (file.filePointer % 2 != 0L) file.write(0)
                }
                tileRow++
                bandRows = 0
            }

            decoder.decode { _, values ->
                val rowStart = bandRows * paddedWidth
                System.arraycopy(values, 0, band, rowStart, width)
                // Pad the right edge by repeating the last same-colour pair.
                for (x in width until paddedWidth) band[rowStart + x] = band[rowStart + x - 2]
                bandRows++
                if (bandRows == TILE) flushBand()
            }
            if (bandRows > 0) flushBand()

            val exifOffset = writeIfd(file, exifEntries(nef))
            val wb = nef.whiteBalanceRB ?: (1.0 to 1.0)
            val black = nef.blackLevels.let { if (it.size >= 4) it else IntArray(4) { i -> it.getOrElse(i) { 0 } } }
            val crop = if (width > 16 && height > 16) 8 else 0
            val entries = mutableListOf(
                Tag.long(254, 0),
                Tag.long(256, width.toLong()),
                Tag.long(257, height.toLong()),
                Tag.short(258, 16),
                Tag.short(259, 7),
                Tag.short(262, 32803),
                Tag.ascii(271, nef.make),
                Tag.ascii(272, nef.model),
                Tag.short(274, nef.orientation),
                Tag.short(277, 1),
                Tag.short(284, 1),
                Tag.ascii(305, software),
                Tag.long(322, TILE.toLong()),
                Tag.long(323, TILE.toLong()),
                Tag.longs(324, tileOffsets),
                Tag.longs(325, tileCounts),
                Tag.shorts(33421, intArrayOf(2, 2)),
                Tag.bytes(33422, nef.cfaPattern.copyOf(4)),
                Tag.long(34665, exifOffset),
                Tag.bytes(50706, byteArrayOf(1, 4, 0, 0)),
                Tag.bytes(50707, byteArrayOf(1, 1, 0, 0)),
                Tag.ascii(50708, profile.uniqueModel),
                Tag.shorts(50713, intArrayOf(2, 2)),
                Tag.longs(50714, LongArray(4) { black[it].toLong() }),
                Tag.long(50717, decoder.whiteLevel.toLong()),
                Tag.longs(50719, longArrayOf(crop.toLong(), crop.toLong())),
                Tag.longs(50720, longArrayOf((width - 2 * crop).toLong(), (height - 2 * crop).toLong())),
                Tag.srationals(50721, profile.colorMatrixD65.map { it.toLong() to 10000L }),
                Tag.rationals(50728, listOf(neutral(wb.first), 1_000_000L to 1_000_000L, neutral(wb.second))),
                Tag.short(50778, 21), // D65
            )
            nef.ifd0[306]?.let { entries += Tag.raw(306, it.type, it.count, it.bytes()) } // DateTime
            val ifd0 = writeIfd(file, entries)
            file.seek(4)
            file.write(le32(ifd0))
        }
    }

    /** White balance multiplier -> AsShotNeutral component (its reciprocal). */
    private fun neutral(multiplier: Double): Pair<Long, Long> =
        if (multiplier <= 0.0) 1_000_000L to 1_000_000L else 1_000_000L to Math.round(multiplier * 1_000_000)

    /** EXIF fields worth keeping, copied byte-for-byte (NEF and DNG are both little-endian here). */
    private fun exifEntries(nef: NefFile): List<Tag> {
        val exif = nef.exif ?: return listOf(Tag.raw(36864, 7, 4, "0230".toByteArray()))
        val keep = listOf(
            33434, 33437, 34850, 34855, 36864, 36867, 36868, 37380, 37381, 37383, 37384, 37385, 37386,
            37521, 37522, 41986, 41987, 41989, 41990, 42034, 42035, 42036,
        )
        val sameOrder = nef.tiff.littleEndian
        return keep.mapNotNull { tag ->
            val e = exif[tag] ?: return@mapNotNull null
            if (!sameOrder && e.type !in setOf(1, 2, 6, 7)) return@mapNotNull null // only byte-order-free types
            Tag.raw(tag, e.type, e.count, e.bytes())
        }
    }

    /** Writes an IFD (entries sorted by tag) at the end of the file; returns its offset. */
    private fun writeIfd(file: RandomAccessFile, entries: List<Tag>): Long {
        val sorted = entries.sortedBy { it.tag }
        file.seek(file.length())
        if (file.filePointer % 2 != 0L) file.write(0)
        val start = file.filePointer
        var dataPos = start + 2 + sorted.size * 12L + 4
        val head = java.io.ByteArrayOutputStream()
        val tail = java.io.ByteArrayOutputStream()
        head.write(le16(sorted.size))
        for (t in sorted) {
            head.write(le16(t.tag))
            head.write(le16(t.type))
            head.write(le32(t.count.toLong()))
            if (t.value.size <= 4) {
                head.write(t.value.copyOf(4))
            } else {
                head.write(le32(dataPos))
                tail.write(t.value)
                if (t.value.size % 2 != 0) tail.write(0)
                dataPos = start + 2 + sorted.size * 12L + 4 + tail.size()
            }
        }
        head.write(le32(0))
        file.write(head.toByteArray())
        file.write(tail.toByteArray())
        return start
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private class Tag(val tag: Int, val type: Int, val count: Int, val value: ByteArray) {
        companion object {
            fun raw(tag: Int, type: Int, count: Int, value: ByteArray) = Tag(tag, type, count, value)
            fun short(tag: Int, v: Int) = Tag(tag, 3, 1, le16(v))
            fun shorts(tag: Int, v: IntArray) = Tag(tag, 3, v.size, v.flatMap { le16(it).toList() }.toByteArray())
            fun long(tag: Int, v: Long) = Tag(tag, 4, 1, le32(v))
            fun longs(tag: Int, v: LongArray) = Tag(tag, 4, v.size, v.flatMap { le32(it).toList() }.toByteArray())
            fun bytes(tag: Int, v: ByteArray) = Tag(tag, 1, v.size, v)
            fun ascii(tag: Int, s: String): Tag {
                val b = s.toByteArray(Charsets.ISO_8859_1) + 0
                return Tag(tag, 2, b.size, b)
            }
            fun rationals(tag: Int, v: List<Pair<Long, Long>>) =
                Tag(tag, 5, v.size, v.flatMap { (n, d) -> (le32(n) + le32(d)).toList() }.toByteArray())
            fun srationals(tag: Int, v: List<Pair<Long, Long>>) =
                Tag(tag, 10, v.size, v.flatMap { (n, d) -> (le32(n) + le32(d)).toList() }.toByteArray())
        }
    }
}

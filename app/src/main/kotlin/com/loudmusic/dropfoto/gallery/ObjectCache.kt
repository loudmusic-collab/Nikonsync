package com.loudmusic.dropfoto.gallery

import com.loudmusic.dropfoto.ptpip.ObjectInfo
import com.loudmusic.dropfoto.ptpip.PtpException
import com.loudmusic.dropfoto.ptpip.PtpReader
import com.loudmusic.dropfoto.ptpip.PtpWriter
import java.io.File

/**
 * Remembers each card's ObjectInfo records on disk, per camera, so reconnecting doesn't mean
 * reading every file's details again (about 120 ms each over the D5500's Wi-Fi).
 *
 * Format: "DFC1", u32 count, then per object u32 handle, u32 length, ObjectInfo dataset.
 */
class ObjectCache(private val dir: File) {
    fun load(cameraSerial: String): Map<Int, ObjectInfo> {
        val file = fileFor(cameraSerial)
        if (!file.exists()) return emptyMap()
        return try {
            val r = PtpReader(file.readBytes())
            if (r.u32() != MAGIC) return emptyMap()
            val count = r.u32()
            buildMap(count) {
                repeat(count) {
                    val handle = r.u32()
                    val bytes = r.bytes(r.u32())
                    put(handle, ObjectInfo.parse(bytes))
                }
            }
        } catch (_: PtpException) {
            emptyMap() // corrupt cache: just start over
        }
    }

    fun save(cameraSerial: String, objects: Map<Int, ObjectInfo>) {
        val w = PtpWriter().u32(MAGIC).u32(objects.size)
        for ((handle, info) in objects) {
            val bytes = info.encode()
            w.u32(handle).u32(bytes.size).bytes(bytes)
        }
        dir.mkdirs()
        val target = fileFor(cameraSerial)
        val temp = File(dir, target.name + ".tmp")
        temp.writeBytes(w.toByteArray())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun fileFor(serial: String) = File(dir, "card-" + serial.filter { it.isLetterOrDigit() }.ifEmpty { "unknown" } + ".bin")

    private companion object {
        const val MAGIC = 0x31434644 // "DFC1" little-endian
    }
}

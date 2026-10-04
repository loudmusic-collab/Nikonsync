package com.loudmusic.dropfoto.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.loudmusic.dropfoto.gallery.CardFile
import com.loudmusic.dropfoto.dng.NefToDng
import com.loudmusic.dropfoto.dng.UnsupportedCameraException
import com.loudmusic.dropfoto.gallery.FileKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.time.ZoneId

/**
 * Saves downloads into the phone's photo library with MediaStore: photos (JPEG and NEF) in
 * Pictures/DropFoto, movies in Movies/DropFoto. No storage permission is needed for files the app
 * creates itself.
 *
 * "Already downloaded" is tracked by card identity (name + size) → MediaStore item, and checked
 * against what's still in the library, so deleting a photo on the phone makes it downloadable
 * again, and a renamed copy ("DSC_0001 (1).JPG") still counts as saved.
 */
/** How downloaded RAW files are saved. DNG opens in Snapseed and most editors; NEF is the camera's original. */
enum class RawFormat(val label: String) { DNG("DNG"), NEF("NEF"), BOTH("NEF + DNG") }

class MediaStorePhotoStore(
    private val context: Context,
    private val log: (String) -> Unit = {},
    private val rawFormat: () -> RawFormat = { RawFormat.DNG },
) : PhotoStore {
    private val records = File(context.filesDir, "saved.tsv")
    private val partialDir = File(context.filesDir, "partial")
    private val known = HashMap<String, String>() // card key -> content URI

    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    override val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    /** Re-reads which saved files still exist in the library. Call off the main thread. */
    fun refresh() {
        synchronized(known) {
            if (known.isEmpty() && records.exists()) {
                records.readLines().forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) known[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            val present = HashSet<String>()
            val namesAndSizes = HashSet<String>()
            for ((uri, folder) in COLLECTIONS) {
                runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                        arrayOf(folder),
                        null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            present += Uri.withAppendedPath(uri, c.getLong(0).toString()).toString()
                            namesAndSizes += "${c.getString(1)}|${c.getLong(2)}"
                        }
                    }
                }
            }
            known.values.retainAll(present)
            _saved.value = known.keys + namesAndSizes
            persist()
        }
    }

    override fun partialFile(file: CardFile): File = File(partialDir, "${file.filename}_${file.size}.part")

    override fun publish(file: CardFile, completed: File) {
        var primary: Uri? = null
        if (file.kind == FileKind.RAW && rawFormat() != RawFormat.NEF) {
            primary = publishDng(file, completed)
        }
        if (primary == null || rawFormat() == RawFormat.BOTH) {
            val (collection, folder, mime) = target(file)
            val uri = save(file.filename, mime, collection, folder, file, completed)
            if (primary == null) primary = uri
        }
        completed.delete()
        synchronized(known) {
            known[file.key] = primary.toString()
            persist()
        }
        _saved.update { it + file.key }
    }

    /** Converts a downloaded NEF to DNG and saves it. Returns null (keeping the NEF) if conversion isn't possible. */
    private fun publishDng(file: CardFile, nef: File): Uri? {
        val dng = File(context.cacheDir, file.filename.substringBeforeLast('.') + ".DNG")
        return try {
            val started = System.nanoTime()
            NefToDng.convert(nef.readBytes(), dng)
            log("Converted ${file.filename} to DNG (%.1f MB) in %.1f s".format(dng.length() / 1e6, (System.nanoTime() - started) / 1e9))
            save(dng.name, "image/x-adobe-dng", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PICTURES_DIR, file, dng)
        } catch (e: UnsupportedCameraException) {
            log("${e.message}; saving the NEF instead")
            null
        } catch (_: OutOfMemoryError) {
            log("Not enough memory to convert ${file.filename} to DNG; saving the NEF instead")
            null
        } catch (e: Exception) {
            if (e is IOException) throw e
            log("Couldn't convert ${file.filename} to DNG (${e.message}); saving the NEF instead")
            null
        } finally {
            dng.delete()
        }
    }

    /** Copies [source] into MediaStore as [displayName]. RAW types refused by Images go to Download/DropFoto. */
    private fun save(displayName: String, mime: String, collection: Uri, folder: String, file: CardFile, source: File): Uri {
        val resolver = context.contentResolver
        val uri = try {
            insert(collection, folder, mime, displayName, file)
        } catch (e: IllegalArgumentException) {
            if (file.kind != FileKind.RAW) throw IOException(e.message, e)
            log("Photos library refused $displayName; saving it in Download/DropFoto instead")
            insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, DOWNLOADS_DIR, mime, displayName, file)
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Couldn't open $displayName for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return uri
    }

    private fun insert(collection: Uri, folder: String, mime: String, displayName: String, file: CardFile): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            file.captured?.let {
                put(MediaStore.MediaColumns.DATE_TAKEN, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("The photo library didn't accept $displayName")
    }

    private fun target(file: CardFile): Triple<Uri, String, String> = when (file.kind) {
        FileKind.JPEG -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PICTURES_DIR, "image/jpeg")
        FileKind.RAW -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PICTURES_DIR, "image/x-nikon-nef")
        FileKind.VIDEO -> Triple(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MOVIES_DIR,
            if (file.extension == "MP4") "video/mp4" else "video/quicktime",
        )
        FileKind.OTHER -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, DOWNLOADS_DIR, "application/octet-stream")
    }

    private fun persist() {
        records.writeText(known.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    private companion object {
        const val PICTURES_DIR = "Pictures/DropFoto/"
        const val MOVIES_DIR = "Movies/DropFoto/"
        const val DOWNLOADS_DIR = "Download/DropFoto/"
        val COLLECTIONS = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to PICTURES_DIR,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MOVIES_DIR,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to DOWNLOADS_DIR,
        )
    }
}

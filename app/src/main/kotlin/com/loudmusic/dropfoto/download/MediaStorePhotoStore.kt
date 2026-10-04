package com.loudmusic.dropfoto.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.loudmusic.dropfoto.gallery.CardFile
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
class MediaStorePhotoStore(
    private val context: Context,
    private val log: (String) -> Unit = {},
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
        val resolver = context.contentResolver
        val (collection, folder, mime) = target(file)
        val uri = try {
            insert(collection, folder, mime, file)
        } catch (e: IllegalArgumentException) {
            if (file.kind != FileKind.RAW) throw IOException(e.message, e)
            // Some phones refuse RAW types in the Images collection: fall back to Downloads.
            log("Photos library refused ${file.filename}; saving it in Download/DropFoto instead")
            insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, DOWNLOADS_DIR, mime, file)
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                completed.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Couldn't open ${file.filename} for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        completed.delete()
        synchronized(known) {
            known[file.key] = uri.toString()
            persist()
        }
        _saved.update { it + file.key }
    }

    private fun insert(collection: Uri, folder: String, mime: String, file: CardFile): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            file.captured?.let {
                put(MediaStore.MediaColumns.DATE_TAKEN, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("The photo library didn't accept ${file.filename}")
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

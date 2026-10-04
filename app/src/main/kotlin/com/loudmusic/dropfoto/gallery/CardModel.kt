package com.loudmusic.dropfoto.gallery

import com.loudmusic.dropfoto.ptpip.ObjectInfo
import java.time.LocalDate
import java.time.LocalDateTime

enum class FileKind { JPEG, RAW, VIDEO, OTHER }

/** One file on the camera's card. [handle] can change between sessions; name + size + folder can't. */
data class CardFile(
    val handle: Int,
    val storageId: Int,
    val parent: Int,
    val filename: String,
    val size: Long,
    val format: Int,
    val captured: LocalDateTime?,
) {
    val extension: String get() = filename.substringAfterLast('.', "").uppercase()

    val kind: FileKind
        get() = when (extension) {
            "JPG", "JPEG" -> FileKind.JPEG
            "NEF", "NRW" -> FileKind.RAW
            "MOV", "MP4", "AVI" -> FileKind.VIDEO
            else -> FileKind.OTHER
        }

    /** Identifies the file across sessions and on the phone. */
    val key: String get() = "$filename|$size"

    companion object {
        fun from(handle: Int, info: ObjectInfo) = CardFile(
            handle = handle,
            storageId = info.storageId,
            parent = info.parent,
            filename = info.filename,
            size = info.compressedSize,
            format = info.format,
            captured = info.captureDateTime,
        )
    }
}

/** What to download for each selected shot. */
enum class DownloadChoice(val label: String) {
    JPEG("JPEG"),
    RAW("RAW"),
    BOTH("RAW + JPEG"),
}

/**
 * One photo as the photographer thinks of it: a RAW+JPEG pair, a lone JPEG or RAW, or a movie.
 */
data class Shot(
    val jpeg: CardFile?,
    val raw: CardFile?,
    val video: CardFile? = null,
) {
    /** The file whose thumbnail represents the shot (JPEG thumbnails are what the camera shows too). */
    val primary: CardFile get() = jpeg ?: raw ?: video!!

    val files: List<CardFile> get() = listOfNotNull(jpeg, raw, video)

    val name: String get() = primary.filename.substringBeforeLast('.')

    /** Unique on the card (folder + file name), for list keys and selection. */
    val id: String get() = "${primary.storageId}/${primary.parent}/${primary.filename}"

    val captured: LocalDateTime? get() = files.mapNotNull { it.captured }.maxOrNull()

    val day: LocalDate? get() = captured?.toLocalDate()

    val label: String
        get() = when {
            video != null -> "MOV"
            jpeg != null && raw != null -> "RAW+JPG"
            raw != null -> "RAW"
            else -> "JPG"
        }

    /** The files a download choice covers. Movies always come along; a choice never yields nothing. */
    fun filesFor(choice: DownloadChoice): List<CardFile> {
        if (video != null) return listOf(video)
        return when (choice) {
            DownloadChoice.JPEG -> listOfNotNull(jpeg ?: raw)
            DownloadChoice.RAW -> listOfNotNull(raw ?: jpeg)
            DownloadChoice.BOTH -> listOfNotNull(jpeg, raw)
        }
    }
}

/**
 * Groups card files into shots: DSC_1234.JPG and DSC_1234.NEF in the same folder become one shot.
 * Newest first, by capture time and then name.
 */
fun groupShots(files: Collection<CardFile>): List<Shot> {
    val shots = files
        .groupBy { Triple(it.storageId, it.parent, it.filename.substringBeforeLast('.').uppercase()) }
        .values
        .flatMap { group ->
            val videos = group.filter { it.kind == FileKind.VIDEO }.map { Shot(jpeg = null, raw = null, video = it) }
            val jpeg = group.firstOrNull { it.kind == FileKind.JPEG }
            val raw = group.firstOrNull { it.kind == FileKind.RAW }
            val stills = if (jpeg != null || raw != null) listOf(Shot(jpeg, raw)) else emptyList()
            stills + videos
        }
    return shots.sortedWith(
        compareByDescending<Shot> { it.captured ?: LocalDateTime.MIN }.thenByDescending { it.name },
    )
}

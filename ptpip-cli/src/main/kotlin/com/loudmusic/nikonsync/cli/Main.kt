package com.loudmusic.nikonsync.cli

import com.loudmusic.nikonsync.ptpip.CameraFile
import com.loudmusic.nikonsync.ptpip.EventCode
import com.loudmusic.nikonsync.ptpip.OperationCode
import com.loudmusic.nikonsync.ptpip.PacketLogger
import com.loudmusic.nikonsync.ptpip.PtpCamera
import com.loudmusic.nikonsync.ptpip.PtpConnectionLostException
import com.loudmusic.nikonsync.ptpip.PtpException
import com.loudmusic.nikonsync.ptpip.PtpInitFailedException
import com.loudmusic.nikonsync.ptpip.PtpIpConfig
import com.loudmusic.nikonsync.ptpip.TextPacketLogger
import com.loudmusic.nikonsync.ptpip.fake.FakeCamera
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

private const val USAGE = """
nikonsync-cli: talk to a Nikon camera over Wi-Fi (PTP/IP)

Usage: nikonsync-cli [options] <command> [args]

Options:
  --host <ip>        Camera address (default 192.168.1.1)
  --port <n>         Camera port (default 15740)
  --trace <file>     Write every packet to <file> (send this file when reporting problems)

Commands:
  info                        Camera model, firmware, supported operations and cards
  list                        Every file on the card
  thumb <handle> [--out f]    Save one thumbnail (handle from 'list')
  get <name|handle>... [--out dir]
                              Download files; resumes interrupted downloads
  get-all [--out dir] [--jpeg-only]
                              Download everything not already in dir (JPEGs first)
  soak [--minutes n]          Stay connected, check the link every 10 s and report drops
  fake-server [--port n] [--bind addr] [--dir folder] [--drop-after-mb n]
                              Run a simulated camera (serves files from folder if given;
                              optionally cuts the connection once after n MB, to test resume)
"""

private class Args(raw: List<String>) {
    val options = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            when {
                a in BOOLEAN_FLAGS -> flags += a
                a.startsWith("--") -> {
                    options[a] = raw.getOrNull(i + 1) ?: fail("$a needs a value")
                    i++
                }
                else -> positional += a
            }
            i++
        }
    }

    fun opt(name: String): String? = options[name]
    fun int(name: String, default: Int) = opt(name)?.toIntOrNull() ?: default

    companion object {
        val BOOLEAN_FLAGS = setOf("--jpeg-only", "--help")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(2)
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun now() = LocalTime.now().format(clock)

fun main(argv: Array<String>) {
    val args = Args(argv.toList())
    val command = args.positional.firstOrNull()
    if (command == null || "--help" in args.flags) {
        println(USAGE.trimIndent())
        return
    }
    val rest = args.positional.drop(1)
    val trace = args.opt("--trace")?.let { PrintWriter(File(it).bufferedWriter(), true) }
    val logger = trace?.let { TextPacketLogger(it) }

    val exit = runBlocking {
        try {
            when (command) {
                "info" -> withCamera(args, logger) { info(it) }
                "list" -> withCamera(args, logger) { list(it) }
                "thumb" -> withCamera(args, logger) { thumb(it, rest, args) }
                "get" -> get(args, logger, rest)
                "get-all" -> getAll(args, logger)
                "soak" -> soak(args, logger)
                "fake-server" -> fakeServer(args)
                else -> fail("unknown command '$command'. Run with --help.")
            }
            0
        } catch (e: PtpInitFailedException) {
            System.err.println("error: ${e.message}. Is another phone or app connected to the camera?")
            1
        } catch (e: PtpException) {
            System.err.println("error: ${e.message}")
            1
        } finally {
            trace?.close()
        }
    }
    exitProcess(exit)
}

private fun config(args: Args, logger: PacketLogger?) = PtpIpConfig(
    host = args.opt("--host") ?: PtpIpConfig.DEFAULT_HOST,
    port = args.int("--port", PtpIpConfig.DEFAULT_PORT),
    guid = cliGuid(),
    friendlyName = "NikonSync CLI",
    logger = logger,
)

/** Stable per-machine GUID so the camera sees the same client every time. */
private fun cliGuid(): ByteArray {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
    val uuid = UUID.nameUUIDFromBytes("nikonsync-cli@$host".toByteArray())
    return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}

private suspend fun <T> withCamera(args: Args, logger: PacketLogger?, block: suspend (PtpCamera) -> T): T {
    val config = config(args, logger)
    System.err.println("Connecting to ${config.host}:${config.port}...")
    val camera = PtpCamera.connect(config)
    System.err.println("Connected to ${camera.deviceInfo.manufacturer} ${camera.deviceInfo.model}")
    try {
        return block(camera)
    } finally {
        camera.disconnect()
    }
}

private suspend fun info(camera: PtpCamera) {
    val d = camera.deviceInfo
    println("Manufacturer : ${d.manufacturer}")
    println("Model        : ${d.model}")
    println("Firmware     : ${d.deviceVersion}")
    println("Serial       : ${d.serialNumber}")
    println("Camera name  : ${camera.connection.cameraName}")
    println("PTP version  : ${d.standardVersion}, vendor extension 0x%08X v%d \"%s\"".format(
        d.vendorExtensionId, d.vendorExtensionVersion, d.vendorExtensionDesc,
    ))
    println("Operations   :")
    d.operationsSupported.sorted().chunked(4).forEach { row ->
        println("  " + row.joinToString("  ") { "0x%04X %-22s".format(it, OperationCode.name(it).take(22)) })
    }
    println("Events       : " + d.eventsSupported.sorted().joinToString { EventCode.name(it) })
    println("Key features :")
    println("  Partial download (resume) : ${yesNo(d.supports(OperationCode.GET_PARTIAL_OBJECT))}")
    println("  Nikon large preview       : ${yesNo(d.supports(OperationCode.NIKON_GET_LARGE_THUMB))}")
    println("  Nikon 64-bit partial read : ${yesNo(d.supports(OperationCode.NIKON_GET_PARTIAL_OBJECT_EX))}")
    println("  Probe answered            : ${yesNo(camera.probe())}")
    val storages = camera.getStorageIds()
    println("Storage IDs  : " + storages.joinToString { "0x%08X".format(it) })
    for (id in camera.getAvailableStorageIds()) {
        val s = camera.getStorageInfo(id)
        println("  0x%08X \"%s\" %s: %s free of %s".format(
            id, s.volumeLabel, s.description, formatSize(s.freeSpaceBytes), formatSize(s.maxCapacity),
        ))
    }
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private suspend fun list(camera: PtpCamera) {
    val started = System.nanoTime()
    var count = 0
    var total = 0L
    println("%-10s  %-14s  %10s  %-19s  %s".format("HANDLE", "NAME", "SIZE", "CAPTURED", "FORMAT"))
    camera.files().collect { f ->
        count++
        total += f.size
        println("0x%08X  %-14s  %10s  %-19s  0x%04X".format(
            f.handle, f.filename, formatSize(f.size), f.info.captureDateTime?.toString()?.replace('T', ' ') ?: "?", f.info.format,
        ))
    }
    val secs = (System.nanoTime() - started) / 1e9
    System.err.println("%d files, %s, listed in %.1f s".format(count, formatSize(total), secs))
}

private suspend fun thumb(camera: PtpCamera, rest: List<String>, args: Args) {
    val handle = parseHandle(rest.firstOrNull() ?: fail("thumb needs a handle"))
        ?: fail("'${rest.first()}' isn't a handle; use the hex value from 'list'")
    val info = camera.getObjectInfo(handle)
    val bytes = camera.getThumb(handle)
    val out = File(args.opt("--out") ?: "${info.filename.substringBeforeLast('.')}_thumb.jpg")
    out.writeBytes(bytes)
    println("Saved ${bytes.size} bytes to ${out.path}")
}

private fun parseHandle(s: String): Int? =
    if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLongOrNull(16)?.toInt() else null

private suspend fun get(args: Args, logger: PacketLogger?, targets: List<String>) {
    if (targets.isEmpty()) fail("get needs at least one file name or handle")
    downloadWithReconnect(args, logger) { files ->
        targets.map { t ->
            val handle = parseHandle(t)
            files.firstOrNull { if (handle != null) it.handle == handle else it.filename.equals(t, ignoreCase = true) }
                ?: fail("no file matching '$t' on the card")
        }
    }
}

private suspend fun getAll(args: Args, logger: PacketLogger?) {
    val jpegOnly = "--jpeg-only" in args.flags
    downloadWithReconnect(args, logger) { files ->
        files
            .filter { !jpegOnly || it.info.extension == "JPG" }
            // JPEGs first so shareable photos arrive before the big RAW files.
            .sortedWith(compareBy<CameraFile> { if (it.info.extension == "JPG") 0 else 1 }.thenBy { it.filename })
    }
}

/**
 * Downloads the selected files, reconnecting and resuming if the link drops.
 * Handles can change between sessions, so the selection is re-resolved by name after each reconnect.
 */
private suspend fun downloadWithReconnect(
    args: Args,
    logger: PacketLogger?,
    select: (List<CameraFile>) -> List<CameraFile>,
) {
    val outDir = File(args.opt("--out") ?: "downloads").apply { mkdirs() }
    val done = mutableSetOf<String>()
    var failures = 0
    var totalBytes = 0L
    val started = System.nanoTime()
    while (true) {
        try {
            withCamera(args, logger) { camera ->
                System.err.println("Reading file list...")
                val all = camera.files().toList()
                val selected = select(all).filter { it.filename !in done }
                for ((index, f) in selected.withIndex()) {
                    val target = File(outDir, f.filename)
                    if (target.exists() && target.length() == f.size) {
                        println("[${index + 1}/${selected.size}] ${f.filename}: already downloaded")
                        done += f.filename
                        continue
                    }
                    val part = File(outDir, f.filename + ".part")
                    val resumeFrom = if (part.exists() && part.length() <= f.size) part.length() else 0L
                    if (resumeFrom == 0L) part.delete()
                    val label = "[${index + 1}/${selected.size}] ${f.filename}"
                    val t0 = System.nanoTime()
                    FileOutputStream(part, true).use { out ->
                        camera.download(f.handle, f.size, out, startOffset = resumeFrom) { bytes, total ->
                            printProgress(label, bytes, total, resumeFrom, t0)
                        }
                    }
                    System.err.println()
                    if (!part.renameTo(target)) throw PtpException("couldn't rename ${part.path}")
                    totalBytes += f.size - resumeFrom
                    done += f.filename
                    failures = 0
                }
            }
            val secs = (System.nanoTime() - started) / 1e9
            println("Done: ${done.size} files, ${formatSize(totalBytes)} in %.0f s (%.2f MB/s)".format(secs, totalBytes / 1e6 / secs))
            return
        } catch (e: PtpConnectionLostException) {
            failures++
            System.err.println()
            System.err.println("${now()} connection lost: ${e.message}")
            if (failures > 5) throw e
            val wait = 2_000L shl (failures - 1)
            System.err.println("${now()} reconnecting in ${wait / 1000} s (attempt $failures of 5)...")
            delay(wait)
        } catch (e: PtpInitFailedException) {
            failures++
            if (failures > 5) throw e
            System.err.println("${now()} camera still busy with the previous connection, waiting...")
            delay(2_000L shl (failures - 1))
        }
    }
}

private fun printProgress(label: String, bytes: Long, total: Long, resumedFrom: Long, t0: Long) {
    val pct = if (total > 0) bytes * 100 / total else 100
    val secs = (System.nanoTime() - t0) / 1e9
    val rate = if (secs > 0.2) (bytes - resumedFrom) / 1e6 / secs else 0.0
    System.err.print("\r%s %3d%%  %s / %s  %.2f MB/s   ".format(label, pct, formatSize(bytes), formatSize(total), rate))
}

/**
 * Stability test: stays connected, checks both channels every 10 s and reconnects when the link drops.
 * Mirrors what the app's keep-alive will do.
 */
private suspend fun soak(args: Args, logger: PacketLogger?) {
    val minutes = args.int("--minutes", 30)
    val deadline = System.currentTimeMillis() + minutes * 60_000L
    var drops = 0
    var checks = 0
    var probeFailures = 0
    println("Soak test for $minutes minutes. Leave the camera alone; press Ctrl+C to stop early.")
    while (System.currentTimeMillis() < deadline) {
        val camera = try {
            PtpCamera.connect(config(args, logger))
        } catch (e: PtpException) {
            println("${now()} connect failed: ${e.message}; retrying in 5 s")
            delay(5_000)
            continue
        }
        println("${now()} connected to ${camera.deviceInfo.model}")
        coroutineScope {
            val events = launch { camera.events.collect { println("${now()} event: $it") } }
            try {
                while (System.currentTimeMillis() < deadline) {
                    val t0 = System.nanoTime()
                    val probed = camera.probe()
                    val t1 = System.nanoTime()
                    camera.getStorageIds()
                    val t2 = System.nanoTime()
                    checks++
                    if (!probed) probeFailures++
                    println("%s ok  probe %s  command %d ms".format(
                        now(), if (probed) "%d ms".format((t1 - t0) / 1_000_000) else "no answer", (t2 - t1) / 1_000_000,
                    ))
                    // Wait for the next check, but wake up at once if the link drops in between.
                    val closedBy = withTimeoutOrNull(10_000) { camera.connection.awaitClose() }
                    if (closedBy != null || !camera.isOpen) throw PtpConnectionLostException(closedBy?.message ?: "closed")
                }
            } catch (e: PtpConnectionLostException) {
                drops++
                println("${now()} DROPPED: ${e.message}")
            } finally {
                events.cancel()
                if (camera.isOpen) camera.disconnect() else camera.close()
            }
        }
    }
    println("Summary: $checks checks, $drops drops, $probeFailures unanswered probes")
}

private suspend fun fakeServer(args: Args) {
    val dir = args.opt("--dir")?.let(::File)
    val objects = if (dir != null) FakeCards.fromDirectory(dir) else FakeCamera.sampleCard(pairs = 20)
    val bind = InetAddress.getByName(args.opt("--bind") ?: "127.0.0.1")
    val fake = FakeCamera(objects, port = args.int("--port", PtpIpConfig.DEFAULT_PORT), bindAddress = bind).start()
    args.opt("--drop-after-mb")?.toLongOrNull()?.let { fake.dropConnectionAfterObjectBytes(it shl 20) }
    println("Fake ${fake.deviceInfo.model} listening on ${fake.host}:${fake.port} with ${objects.count { !it.isFolder }} files. Ctrl+C to stop.")
    while (true) delay(60_000)
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

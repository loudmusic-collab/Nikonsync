package com.loudmusic.dropfoto.gallery

import com.loudmusic.dropfoto.ptpip.ObjectFormat
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardModelTest {
    private var nextHandle = 1
    private fun file(name: String, size: Long = 100, parent: Int = 2, minute: Int = 0) =
        CardFile(nextHandle++, 0x10001, parent, name, size, ObjectFormat.UNDEFINED, LocalDateTime.of(2026, 10, 4, 12, minute))

    @Test
    fun `pairs RAW and JPEG with the same name in the same folder`() {
        val shots = groupShots(
            listOf(
                file("DSC_0001.JPG", minute = 1), file("DSC_0001.NEF", minute = 1),
                file("DSC_0002.JPG", minute = 2),
                file("DSC_0003.NEF", minute = 3),
                file("DSC_0001.JPG", parent = 9, minute = 0), // same name, other folder
                file("DSC_0004.MOV", minute = 4),
            ),
        )
        assertEquals(listOf("MOV", "RAW", "JPG", "RAW+JPG", "JPG"), shots.map { it.label })
        assertEquals("DSC_0004", shots.first().name)
    }

    @Test
    fun `download choices pick the right files and never come back empty`() {
        val pair = groupShots(listOf(file("A.JPG", 5), file("A.NEF", 30))).single()
        assertEquals(listOf("A.JPG"), pair.filesFor(DownloadChoice.JPEG).map { it.filename })
        assertEquals(listOf("A.NEF"), pair.filesFor(DownloadChoice.RAW).map { it.filename })
        assertEquals(listOf("A.JPG", "A.NEF"), pair.filesFor(DownloadChoice.BOTH).map { it.filename })

        val rawOnly = groupShots(listOf(file("B.NEF"))).single()
        assertEquals(listOf("B.NEF"), rawOnly.filesFor(DownloadChoice.JPEG).map { it.filename })
        val jpegOnly = groupShots(listOf(file("C.JPG"))).single()
        assertEquals(listOf("C.JPG"), jpegOnly.filesFor(DownloadChoice.RAW).map { it.filename })
        val movie = groupShots(listOf(file("D.MOV"))).single()
        assertEquals(listOf("D.MOV"), movie.filesFor(DownloadChoice.JPEG).map { it.filename })
        assertNull(movie.jpeg)
    }
}

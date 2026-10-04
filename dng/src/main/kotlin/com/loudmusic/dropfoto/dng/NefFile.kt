package com.loudmusic.dropfoto.dng

/** What the converter needs from a Nikon NEF. */
class NefFile(val data: ByteArray) {
    val tiff: TiffReader
    val ifd0: Ifd
    val exif: Ifd?
    val raw: Ifd
    val make: String
    val model: String
    val orientation: Int
    val width: Int
    val height: Int
    val bitsPerSample: Int
    val rawOffset: Int
    val rawLength: Int
    val cfaPattern: ByteArray
    /** Nikon maker note reader (its own TIFF header; offsets relative to it). */
    val makerNote: TiffReader?
    val makerNoteIfd: Ifd?

    init {
        val (r, ifd0Offset) = TiffReader.open(data)
        tiff = r
        ifd0 = r.ifd(ifd0Offset)
        make = ifd0[TAG_MAKE]?.string() ?: ""
        model = ifd0[TAG_MODEL]?.string() ?: ""
        orientation = ifd0[TAG_ORIENTATION]?.int()?.toInt() ?: 1
        exif = ifd0[TAG_EXIF_IFD]?.let { r.ifd(it.int()) }

        val subIfds = ifd0[TAG_SUB_IFDS]?.ints()?.map { r.ifd(it) }.orEmpty()
        raw = subIfds.firstOrNull { it[TAG_COMPRESSION]?.int() == NIKON_COMPRESSED || it[TAG_PHOTOMETRIC]?.int() == CFA_PHOTOMETRIC }
            ?: throw IllegalArgumentException("No RAW image found in this NEF")
        width = raw[TAG_WIDTH]!!.int().toInt()
        height = raw[TAG_HEIGHT]!!.int().toInt()
        bitsPerSample = raw[TAG_BITS]!!.int().toInt()
        rawOffset = raw[TAG_STRIP_OFFSETS]!!.int().toInt()
        rawLength = raw[TAG_STRIP_BYTES]!!.int().toInt()
        cfaPattern = raw[TAG_CFA_PATTERN]?.bytes() ?: byteArrayOf(0, 1, 1, 2)

        // Nikon type 3 maker note: "Nikon\0" + version (4 bytes), then a TIFF header at +10.
        val mn = exif?.get(TAG_MAKER_NOTE)
        if (mn != null && String(data, mn.valuePos, 5, Charsets.ISO_8859_1) == "Nikon") {
            val (mr, mIfd) = TiffReader.open(data, mn.valuePos + 10)
            makerNote = mr
            makerNoteIfd = mr.ifd(mIfd)
        } else {
            makerNote = null
            makerNoteIfd = null
        }
    }

    val isNikonCompressed: Boolean get() = raw[TAG_COMPRESSION]?.int() == NIKON_COMPRESSED

    /** Black level per CFA position (maker note 0x003D), or 0 if the camera doesn't record it. */
    val blackLevels: IntArray
        get() = makerNoteIfd?.get(MN_BLACK_LEVEL)?.ints()?.map { it.toInt() }?.toIntArray() ?: IntArray(4)

    /** As-shot white balance multipliers (red, blue) relative to green, from maker note 0x000C. */
    val whiteBalanceRB: Pair<Double, Double>?
        get() = makerNoteIfd?.get(MN_WB_RB_LEVELS)?.let { it.double(0) to it.double(1) }

    /** Nikon's decompression/linearization data (maker note 0x0096), with its byte order. */
    val linearization: Entry? get() = makerNoteIfd?.get(MN_LINEARIZATION)

    companion object {
        const val TAG_WIDTH = 256
        const val TAG_HEIGHT = 257
        const val TAG_BITS = 258
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC = 262
        const val TAG_MAKE = 271
        const val TAG_MODEL = 272
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_ORIENTATION = 274
        const val TAG_STRIP_BYTES = 279
        const val TAG_SUB_IFDS = 330
        const val TAG_CFA_PATTERN = 33422
        const val TAG_EXIF_IFD = 34665
        const val TAG_MAKER_NOTE = 37500
        const val NIKON_COMPRESSED = 34713L
        const val CFA_PHOTOMETRIC = 32803L
        const val MN_WB_RB_LEVELS = 0x000C
        const val MN_BLACK_LEVEL = 0x003D
        const val MN_LINEARIZATION = 0x0096
    }
}

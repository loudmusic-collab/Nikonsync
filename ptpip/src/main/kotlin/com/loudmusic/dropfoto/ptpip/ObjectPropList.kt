package com.loudmusic.dropfoto.ptpip

/**
 * MTP ObjectPropList dataset: `u32 count`, then per element `u32 handle, u16 property, u16 type, value`.
 * Used to read names, sizes and dates for a whole card in one request instead of one
 * GetObjectInfo per file.
 */
public object ObjectPropList {
    /** One property value: [Long] for integers, [String] for strings, [List] for arrays. */
    public data class Element(val handle: Int, val property: Int, val value: Any)

    public fun parse(data: ByteArray): List<Element> {
        val r = PtpReader(data)
        val count = r.u32AsLong()
        if (count > data.size / 8) throw PtpProtocolException("ObjectPropList claims $count elements in ${data.size} bytes")
        return List(count.toInt()) {
            val handle = r.u32()
            val property = r.u16()
            val type = r.u16()
            Element(handle, property, readValue(r, type))
        }
    }

    public fun encode(elements: List<Element>, types: Map<Int, Int>): ByteArray {
        val w = PtpWriter().u32(elements.size)
        for (e in elements) {
            val type = types[e.property] ?: if (e.value is String) DataType.STRING else DataType.UINT32
            w.u32(e.handle).u16(e.property).u16(type)
            when (type) {
                DataType.STRING -> w.ptpString(e.value as String)
                DataType.UINT8, DataType.INT8 -> w.u8((e.value as Long).toInt())
                DataType.UINT16, DataType.INT16 -> w.u16((e.value as Long).toInt())
                DataType.UINT32, DataType.INT32 -> w.u32((e.value as Long).toInt())
                DataType.UINT64, DataType.INT64 -> w.u64(e.value as Long)
                else -> throw IllegalArgumentException("Can't encode type ${hex16(type)}")
            }
        }
        return w.toByteArray()
    }

    /**
     * Turns a property list into ObjectInfo records with the fields DropFoto uses (storage, format,
     * size, parent, name, dates). Objects missing a file name are dropped.
     */
    public fun toObjectInfos(elements: List<Element>): Map<Int, ObjectInfo> {
        val byHandle = LinkedHashMap<Int, MutableMap<Int, Any>>()
        for (e in elements) byHandle.getOrPut(e.handle) { HashMap() }[e.property] = e.value
        return byHandle.mapNotNull { (handle, props) ->
            val name = props[ObjectPropCode.OBJECT_FILE_NAME] as? String ?: return@mapNotNull null
            val created = props[ObjectPropCode.DATE_CREATED] as? String ?: ""
            val modified = props[ObjectPropCode.DATE_MODIFIED] as? String ?: ""
            handle to ObjectInfo(
                storageId = (props[ObjectPropCode.STORAGE_ID] as? Long)?.toInt() ?: 0,
                format = (props[ObjectPropCode.OBJECT_FORMAT] as? Long)?.toInt() ?: ObjectFormat.UNDEFINED,
                protectionStatus = 0,
                compressedSize = props[ObjectPropCode.OBJECT_SIZE] as? Long ?: 0,
                thumbFormat = 0, thumbCompressedSize = 0, thumbWidth = 0, thumbHeight = 0,
                imageWidth = 0, imageHeight = 0, imageBitDepth = 0,
                parent = (props[ObjectPropCode.PARENT_OBJECT] as? Long)?.toInt() ?: 0,
                associationType = 0, associationDesc = 0, sequenceNumber = 0,
                filename = name,
                captureDate = created.ifEmpty { modified },
                modificationDate = modified,
                keywords = "",
            )
        }.toMap()
    }

    private fun readValue(r: PtpReader, type: Int): Any = when {
        type == DataType.STRING -> r.ptpString()
        type and DataType.ARRAY_FLAG != 0 -> {
            val n = r.u32AsLong()
            if (n > r.remaining) throw PtpProtocolException("Array of $n elements exceeds remaining ${r.remaining} bytes")
            List(n.toInt()) { readValue(r, type and DataType.ARRAY_FLAG.inv()) }
        }
        else -> when (type) {
            DataType.INT8 -> r.u8().toByte().toLong()
            DataType.UINT8 -> r.u8().toLong()
            DataType.INT16 -> r.u16().toShort().toLong()
            DataType.UINT16 -> r.u16().toLong()
            DataType.INT32 -> r.u32().toLong()
            DataType.UINT32 -> r.u32AsLong()
            DataType.INT64, DataType.UINT64 -> r.u64()
            DataType.INT128, DataType.UINT128 -> {
                val low = r.u64()
                r.u64()
                low
            }
            else -> throw PtpProtocolException("Unknown property data type ${hex16(type)}")
        }
    }
}

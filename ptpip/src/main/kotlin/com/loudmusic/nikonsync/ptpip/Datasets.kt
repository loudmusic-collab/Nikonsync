package com.loudmusic.nikonsync.ptpip

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** PTP DeviceInfo dataset (returned by GetDeviceInfo). */
public data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: List<Int>,
    val eventsSupported: List<Int>,
    val devicePropertiesSupported: List<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    public fun supports(operationCode: Int): Boolean = operationCode in operationsSupported

    public fun encode(): ByteArray = PtpWriter()
        .u16(standardVersion)
        .u32(vendorExtensionId)
        .u16(vendorExtensionVersion)
        .ptpString(vendorExtensionDesc)
        .u16(functionalMode)
        .u16Array(operationsSupported)
        .u16Array(eventsSupported)
        .u16Array(devicePropertiesSupported)
        .u16Array(captureFormats)
        .u16Array(imageFormats)
        .ptpString(manufacturer)
        .ptpString(model)
        .ptpString(deviceVersion)
        .ptpString(serialNumber)
        .toByteArray()

    public companion object {
        public const val VENDOR_NIKON: Int = 0x0000000A

        public fun parse(data: ByteArray): DeviceInfo {
            val r = PtpReader(data)
            return DeviceInfo(
                standardVersion = r.u16(),
                vendorExtensionId = r.u32(),
                vendorExtensionVersion = r.u16(),
                vendorExtensionDesc = r.ptpString(),
                functionalMode = r.u16(),
                operationsSupported = r.u16Array(),
                eventsSupported = r.u16Array(),
                devicePropertiesSupported = r.u16Array(),
                captureFormats = r.u16Array(),
                imageFormats = r.u16Array(),
                manufacturer = r.ptpStringOrEmpty(),
                model = r.ptpStringOrEmpty(),
                deviceVersion = r.ptpStringOrEmpty(),
                serialNumber = r.ptpStringOrEmpty(),
            )
        }
    }
}

/** PTP StorageInfo dataset (one memory card). */
public data class StorageInfo(
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpaceBytes: Long,
    val freeSpaceImages: Long,
    val description: String,
    val volumeLabel: String,
) {
    public fun encode(): ByteArray = PtpWriter()
        .u16(storageType)
        .u16(filesystemType)
        .u16(accessCapability)
        .u64(maxCapacity)
        .u64(freeSpaceBytes)
        .u32(freeSpaceImages.toInt())
        .ptpString(description)
        .ptpString(volumeLabel)
        .toByteArray()

    public companion object {
        public fun parse(data: ByteArray): StorageInfo {
            val r = PtpReader(data)
            return StorageInfo(
                storageType = r.u16(),
                filesystemType = r.u16(),
                accessCapability = r.u16(),
                maxCapacity = r.u64(),
                freeSpaceBytes = r.u64(),
                freeSpaceImages = r.u32AsLong(),
                description = r.ptpStringOrEmpty(),
                volumeLabel = r.ptpStringOrEmpty(),
            )
        }
    }
}

/** PTP ObjectInfo dataset: metadata for one file or folder on the card. */
public data class ObjectInfo(
    val storageId: Int,
    val format: Int,
    val protectionStatus: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageBitDepth: Int,
    val parent: Int,
    val associationType: Int,
    val associationDesc: Int,
    val sequenceNumber: Int,
    val filename: String,
    val captureDate: String,
    val modificationDate: String,
    val keywords: String,
) {
    val isFolder: Boolean get() = format == ObjectFormat.ASSOCIATION

    val extension: String get() = filename.substringAfterLast('.', "").uppercase()

    /** Capture date in the camera's local time, or null if the camera didn't report a parseable one. */
    val captureDateTime: LocalDateTime? get() = parsePtpDateTime(captureDate)

    public fun encode(): ByteArray = PtpWriter()
        .u32(storageId)
        .u16(format)
        .u16(protectionStatus)
        .u32(compressedSize.toInt())
        .u16(thumbFormat)
        .u32(thumbCompressedSize.toInt())
        .u32(thumbWidth)
        .u32(thumbHeight)
        .u32(imageWidth)
        .u32(imageHeight)
        .u32(imageBitDepth)
        .u32(parent)
        .u16(associationType)
        .u32(associationDesc)
        .u32(sequenceNumber)
        .ptpString(filename)
        .ptpString(captureDate)
        .ptpString(modificationDate)
        .ptpString(keywords)
        .toByteArray()

    public companion object {
        public fun parse(data: ByteArray): ObjectInfo {
            val r = PtpReader(data)
            return ObjectInfo(
                storageId = r.u32(),
                format = r.u16(),
                protectionStatus = r.u16(),
                compressedSize = r.u32AsLong(),
                thumbFormat = r.u16(),
                thumbCompressedSize = r.u32AsLong(),
                thumbWidth = r.u32(),
                thumbHeight = r.u32(),
                imageWidth = r.u32(),
                imageHeight = r.u32(),
                imageBitDepth = r.u32(),
                parent = r.u32(),
                associationType = r.u16(),
                associationDesc = r.u32(),
                sequenceNumber = r.u32(),
                filename = r.ptpString(),
                captureDate = r.ptpStringOrEmpty(),
                modificationDate = r.ptpStringOrEmpty(),
                keywords = r.ptpStringOrEmpty(),
            )
        }
    }
}

private val PTP_DATE_FORMATS = listOf(
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.S"),
)

/**
 * Parses a PTP DateTime string ("YYYYMMDDThhmmss" with optional ".s" and a "Z" or "+hhmm" suffix).
 * The time-zone suffix is ignored: cameras report local time.
 */
public fun parsePtpDateTime(value: String): LocalDateTime? {
    if (value.length < 15) return null
    val core = value.substring(0, if (value.length >= 17 && value[15] == '.') 17 else 15)
    for (f in PTP_DATE_FORMATS) {
        try {
            return LocalDateTime.parse(core, f)
        } catch (_: DateTimeParseException) {
            // try the next pattern
        }
    }
    return null
}

public fun formatPtpDateTime(value: LocalDateTime): String = value.format(PTP_DATE_FORMATS[0])

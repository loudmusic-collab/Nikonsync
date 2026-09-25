package com.loudmusic.nikonsync.ptpip

/** PTP/IP packet types (ISO 15740 Annex / CIPA DC-005). */
public object PacketType {
    public const val INIT_COMMAND_REQUEST: Int = 1
    public const val INIT_COMMAND_ACK: Int = 2
    public const val INIT_EVENT_REQUEST: Int = 3
    public const val INIT_EVENT_ACK: Int = 4
    public const val INIT_FAIL: Int = 5
    public const val OPERATION_REQUEST: Int = 6
    public const val OPERATION_RESPONSE: Int = 7
    public const val EVENT: Int = 8
    public const val START_DATA: Int = 9
    public const val DATA: Int = 10
    public const val CANCEL: Int = 11
    public const val END_DATA: Int = 12
    public const val PROBE_REQUEST: Int = 13
    public const val PROBE_RESPONSE: Int = 14

    public fun name(type: Int): String = when (type) {
        INIT_COMMAND_REQUEST -> "InitCommandRequest"
        INIT_COMMAND_ACK -> "InitCommandAck"
        INIT_EVENT_REQUEST -> "InitEventRequest"
        INIT_EVENT_ACK -> "InitEventAck"
        INIT_FAIL -> "InitFail"
        OPERATION_REQUEST -> "OperationRequest"
        OPERATION_RESPONSE -> "OperationResponse"
        EVENT -> "Event"
        START_DATA -> "StartData"
        DATA -> "Data"
        CANCEL -> "Cancel"
        END_DATA -> "EndData"
        PROBE_REQUEST -> "ProbeRequest"
        PROBE_RESPONSE -> "ProbeResponse"
        else -> "Unknown(0x${type.toString(16)})"
    }
}

/** Data phase indicator carried in an Operation Request. */
public object DataPhase {
    /** No data phase, or data flows camera -> phone. */
    public const val NONE_OR_IN: Int = 1
    /** Data flows phone -> camera. */
    public const val OUT: Int = 2
}

public object OperationCode {
    public const val GET_DEVICE_INFO: Int = 0x1001
    public const val OPEN_SESSION: Int = 0x1002
    public const val CLOSE_SESSION: Int = 0x1003
    public const val GET_STORAGE_IDS: Int = 0x1004
    public const val GET_STORAGE_INFO: Int = 0x1005
    public const val GET_NUM_OBJECTS: Int = 0x1006
    public const val GET_OBJECT_HANDLES: Int = 0x1007
    public const val GET_OBJECT_INFO: Int = 0x1008
    public const val GET_OBJECT: Int = 0x1009
    public const val GET_THUMB: Int = 0x100A
    public const val INITIATE_CAPTURE: Int = 0x100E
    public const val GET_DEVICE_PROP_DESC: Int = 0x1014
    public const val GET_DEVICE_PROP_VALUE: Int = 0x1015
    public const val GET_PARTIAL_OBJECT: Int = 0x101B

    // Nikon vendor extensions. Always feature-detect via DeviceInfo.operationsSupported.
    public const val NIKON_GET_LARGE_THUMB: Int = 0x90C4
    public const val NIKON_GET_PARTIAL_OBJECT_EX: Int = 0x9431

    public fun name(code: Int): String = when (code) {
        GET_DEVICE_INFO -> "GetDeviceInfo"
        OPEN_SESSION -> "OpenSession"
        CLOSE_SESSION -> "CloseSession"
        GET_STORAGE_IDS -> "GetStorageIDs"
        GET_STORAGE_INFO -> "GetStorageInfo"
        GET_NUM_OBJECTS -> "GetNumObjects"
        GET_OBJECT_HANDLES -> "GetObjectHandles"
        GET_OBJECT_INFO -> "GetObjectInfo"
        GET_OBJECT -> "GetObject"
        GET_THUMB -> "GetThumb"
        INITIATE_CAPTURE -> "InitiateCapture"
        GET_DEVICE_PROP_DESC -> "GetDevicePropDesc"
        GET_DEVICE_PROP_VALUE -> "GetDevicePropValue"
        GET_PARTIAL_OBJECT -> "GetPartialObject"
        NIKON_GET_LARGE_THUMB -> "Nikon.GetLargeThumb"
        NIKON_GET_PARTIAL_OBJECT_EX -> "Nikon.GetPartialObjectEx"
        else -> hex16(code)
    }
}

public object ResponseCode {
    public const val OK: Int = 0x2001
    public const val GENERAL_ERROR: Int = 0x2002
    public const val SESSION_NOT_OPEN: Int = 0x2003
    public const val INVALID_TRANSACTION_ID: Int = 0x2004
    public const val OPERATION_NOT_SUPPORTED: Int = 0x2005
    public const val PARAMETER_NOT_SUPPORTED: Int = 0x2006
    public const val INCOMPLETE_TRANSFER: Int = 0x2007
    public const val INVALID_STORAGE_ID: Int = 0x2008
    public const val INVALID_OBJECT_HANDLE: Int = 0x2009
    public const val STORE_NOT_AVAILABLE: Int = 0x2013
    public const val NO_THUMBNAIL_PRESENT: Int = 0x2010
    public const val DEVICE_BUSY: Int = 0x2019
    public const val INVALID_PARAMETER: Int = 0x201D
    public const val SESSION_ALREADY_OPEN: Int = 0x201E
    public const val TRANSACTION_CANCELLED: Int = 0x201F

    public fun name(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        SESSION_NOT_OPEN -> "SessionNotOpen"
        INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        INVALID_STORAGE_ID -> "InvalidStorageID"
        INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        STORE_NOT_AVAILABLE -> "StoreNotAvailable"
        NO_THUMBNAIL_PRESENT -> "NoThumbnailPresent"
        DEVICE_BUSY -> "DeviceBusy"
        INVALID_PARAMETER -> "InvalidParameter"
        SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        TRANSACTION_CANCELLED -> "TransactionCancelled"
        else -> hex16(code)
    }
}

public object EventCode {
    public const val OBJECT_ADDED: Int = 0x4002
    public const val OBJECT_REMOVED: Int = 0x4003
    public const val STORE_ADDED: Int = 0x4004
    public const val STORE_REMOVED: Int = 0x4005
    public const val DEVICE_PROP_CHANGED: Int = 0x4006
    public const val DEVICE_INFO_CHANGED: Int = 0x4008
    public const val STORAGE_INFO_CHANGED: Int = 0x400C
    public const val CAPTURE_COMPLETE: Int = 0x400D

    public fun name(code: Int): String = when (code) {
        OBJECT_ADDED -> "ObjectAdded"
        OBJECT_REMOVED -> "ObjectRemoved"
        STORE_ADDED -> "StoreAdded"
        STORE_REMOVED -> "StoreRemoved"
        DEVICE_PROP_CHANGED -> "DevicePropChanged"
        DEVICE_INFO_CHANGED -> "DeviceInfoChanged"
        STORAGE_INFO_CHANGED -> "StorageInfoChanged"
        CAPTURE_COMPLETE -> "CaptureComplete"
        else -> hex16(code)
    }
}

public object ObjectFormat {
    public const val UNDEFINED: Int = 0x3000
    public const val ASSOCIATION: Int = 0x3001
    public const val SCRIPT: Int = 0x3002
    public const val TEXT: Int = 0x3004
    public const val AVI: Int = 0x300A
    public const val MPEG: Int = 0x300B
    public const val QUICKTIME: Int = 0x300D
    public const val UNDEFINED_IMAGE: Int = 0x3800
    public const val EXIF_JPEG: Int = 0x3801
    public const val TIFF: Int = 0x380D
}

/** Special parameter values used by GetObjectHandles / GetNumObjects. */
public object ObjectQuery {
    public const val ALL_STORAGES: Int = -1 // 0xFFFFFFFF
    public const val ANY_FORMAT: Int = 0
    public const val ANY_PARENT: Int = 0
    public const val ROOT_ONLY: Int = -1 // 0xFFFFFFFF
}

internal fun hex16(value: Int): String = "0x%04X".format(value and 0xFFFF)

internal fun hex32(value: Int): String = "0x%08X".format(value)

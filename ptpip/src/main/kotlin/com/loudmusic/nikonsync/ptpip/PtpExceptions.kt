package com.loudmusic.nikonsync.ptpip

import java.io.IOException

/** Base class for everything this library throws. */
public open class PtpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The peer sent bytes that don't follow the PTP/IP protocol. The connection is closed. */
public class PtpProtocolException(message: String, cause: Throwable? = null) : PtpException(message, cause)

/** The camera refused the PTP/IP handshake (Init Fail packet). */
public class PtpInitFailedException(public val reason: Int) :
    PtpException("Camera refused the connection (InitFail reason ${hex32(reason)})")

/** The TCP connection to the camera is gone. Reconnect to continue. */
public class PtpConnectionLostException(message: String, cause: Throwable? = null) : PtpException(message, cause)

/** The camera answered an operation with a non-OK response code. The connection stays usable. */
public class PtpResponseException(
    public val operationCode: Int,
    public val responseCode: Int,
) : PtpException(
    "${OperationCode.name(operationCode)} failed: ${ResponseCode.name(responseCode)}",
)

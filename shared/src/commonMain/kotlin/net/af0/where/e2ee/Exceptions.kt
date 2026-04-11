package net.af0.where.e2ee

/** Base class for all Where-specific exceptions. */
open class WhereException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Base class for network-related errors. */
open class NetworkException(message: String, cause: Throwable? = null) : WhereException(message, cause)

/** Thrown when a connection to the server cannot be established. */
class ConnectException(message: String, cause: Throwable? = null) : NetworkException(message, cause)

/** Thrown when a network request times out. */
class TimeoutException(message: String, cause: Throwable? = null) : NetworkException(message, cause)

/** Thrown when the server returns an error response. */
class ServerException(val statusCode: Int, message: String) : NetworkException("Server error $statusCode: $message")

/** Base class for cryptographic and protocol validation errors. */
open class CryptoException(message: String, cause: Throwable? = null) : WhereException(message, cause)

/** Thrown when decryption fails (e.g., bad MAC, invalid padding). */
class DecryptionException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** Thrown when authentication fails (e.g., bad signature or key confirmation). */
class AuthenticationException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** Thrown when a protocol-level validation fails (e.g., seq replay, huge gap). */
class ProtocolException(message: String) : CryptoException(message)

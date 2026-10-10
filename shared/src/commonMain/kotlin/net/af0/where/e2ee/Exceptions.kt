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

/** Thrown when a protocol version mismatch occurs (#194). */
class ProtocolVersionException(message: String) : CryptoException(message)

/** Thrown when a protocol-level validation fails (e.g., seq replay, huge gap). */
open class ProtocolException(message: String) : CryptoException(message)

/** Thrown when a message is rejected as a duplicate (replay). */
class ReplayException(message: String) : ProtocolException(message)

/** Thrown when a ratchet gap is too large to process (§8.3.1). */
class ProtocolGapException(message: String) : ProtocolException(message)

/** Thrown when a scanned invite is past its `expires_at`. */
class InviteExpiredException() : WhereException("This invite has expired")

/** Thrown when trying to pair with yourself. */
class SelfPairingException() : WhereException("Cannot pair with yourself")

/**
 * Thrown when the header authenticates but the message cannot be delivered: the body
 * fails AEAD (e.g. corruption), or it authenticates but its plaintext is malformed
 * (bad padding, undecodable). Carries the advanced [SessionState], which the caller
 * MUST persist so a corrupted frame leaves the same state as a dropped one (§8.3.1(4)).
 * Not a [DecryptionException]: callers must handle it separately.
 */
class DecryptionExceptionWithState(
    val newState: SessionState,
    cause: Throwable,
) : WhereException(cause.message ?: "payload decryption failed", cause)

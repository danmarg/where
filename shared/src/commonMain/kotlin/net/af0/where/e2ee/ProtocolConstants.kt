package net.af0.where.e2ee

/**
 * Every domain-separation string in the protocol. They are wire-visible constants: changing one
 * breaks interop with existing clients and sessions.
 *
 * Labels of the same [use] must be distinct and prefix-free (LabelTest), since some are followed by
 * variable input (e.g. the routing-token info is label || sender || recipient). Across uses a prefix
 * is harmless, and one exists: [CONFIRM] is a prefix of [CONFIRM_KEY], but one is HMAC input under a
 * derived key and the other HKDF info, so they never meet in the same function.
 */
internal enum class Label(
    private val value: String,
    val use: Use,
) {
    RATCHET_STEP("Where-v1-RatchetStep", Use.HKDF_INFO),
    MSG_NONCE("Where-v1-MsgNonce", Use.HKDF_INFO),
    KEY_EXCHANGE("Where-v1-KeyExchange", Use.HKDF_INFO),
    ROUTING_TOKEN("Where-v1-RoutingToken", Use.HKDF_INFO),
    CONFIRM_KEY("Where-v1-ConfirmKey", Use.HKDF_INFO),
    SUGGESTED_NAME("Where-v1-SuggestedName", Use.HKDF_INFO),
    SAFETY_NUMBER("Where-v1-SafetyNumber", Use.HKDF_INFO),
    DISCOVERY("Where-v1-Discovery", Use.HKDF_INFO),

    /** Prefix of the key-confirmation HMAC input. */
    CONFIRM("Where-v1-Confirm", Use.HMAC_PREFIX),

    /** Prefix of a message body's AEAD AAD. */
    MESSAGE_AAD("Where-v1-Message", Use.AAD_PREFIX),
    ;

    enum class Use { HKDF_INFO, HMAC_PREFIX, AAD_PREFIX }

    /** A fresh copy of the label's UTF-8 bytes. */
    val bytes: ByteArray get() = value.encodeToByteArray()
}

// A legitimate DH ratchet produces at most 1 token rotation per message batch.
// We allow up to 5 follows per poll to handle back-to-back ratchet epochs arriving
// in the same server poll window (e.g. keepalive + location in rapid succession).
// SECURITY NOTE: The follow condition requires a successful AEAD authentication,
// so an adversary without the current chain key cannot force token advancement.
internal const val MAX_TOKEN_FOLLOWS_PER_POLL = 5

// Total maximum messages to drain in a single poll call to catch up with backlogs.
internal const val MAX_MESSAGES_PER_POLL = 500

// After this many consecutive polls where header-parse failures blocked the ACK,
// force-ACK the batch to break a permanent livelock. The dropped messages are
// accepted as lost; the session may need re-pairing if they contained DH keys.
// At a 30-second poll interval, 5 retries = ~2.5 minutes before force-ACK.
internal const val MAX_SILENT_DROP_RETRIES = 5

// Bound on the skipped-message-key cache. Sized to comfortably absorb a full
// MAX_MESSAGES_PER_POLL backlog (500) with headroom; peer-influenceable but
// bounded at ~60 bytes/entry ≈ 60 KB worst case.
internal const val MAX_SKIPPED_KEYS = 1000
internal const val MAX_KEY_AGE_MS = 604_800_000L // 7 days in milliseconds

const val PROTOCOL_VERSION = 1

/** How long an invite (QR or link) is valid for, from creation. Advertised to scanners as `expires_at`. */
const val INVITE_LIFETIME_SECONDS = 48 * 3600L

/**
 * Clock-skew slack on an invite's expiry: scanners accept up to this long after `expires_at` by
 * their own clock. The inviter keeps the invite twice this long past `expires_at` by its clock, so
 * a scanner whose clock lags the inviter's by up to this much never accepts a dropped invite.
 */
internal const val INVITE_EXPIRY_GRACE_SECONDS = 300L

/** A rename keeps the shown invite only if it has at least this long left; otherwise a fresh one is minted. */
internal const val INVITE_RENAME_MIN_REMAINING_SECONDS = 3600L
const val SUPPORTED_MAX_VERSION = 1
internal const val PADDING_SIZE = 512

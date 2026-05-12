package net.af0.where.e2ee

internal const val INFO_RATCHET_STEP = "Where-v1-RatchetStep"
internal const val INFO_MSG_NONCE = "Where-v1-MsgNonce"
internal const val INFO_KEY_EXCHANGE = "Where-v1-KeyExchange"
internal const val INFO_SESSION = "Where-v1-Session"
internal const val INFO_ROUTING_TOKEN = "Where-v1-RoutingToken"
internal const val INFO_CONFIRM = "Where-v1-ConfirmKey"
internal const val INFO_HEADER_KEY = "Where-v1-HeaderKey"

// A legitimate DH ratchet produces at most 1 token rotation per message batch.
// We allow 2 follows per poll cycle to handle the case where a message sent
// before and after a ratchet both arrive in the same server poll window.
// SECURITY NOTE: The follow condition requires a successful AEAD authentication,
// so an adversary without the current chain key cannot force token advancement.
internal const val MAX_TOKEN_FOLLOWS_PER_POLL = 20
internal const val MAX_DIAGNOSTIC_EVENTS = 30

// After this many consecutive polls where header-parse failures blocked the ACK,
// force-ACK the batch to break a permanent livelock. The dropped messages are
// accepted as lost; the session may need re-pairing if they contained DH keys.
internal const val MAX_SILENT_DROP_RETRIES = 50
internal const val MAX_OUTBOX_PARK_RETRIES = 20
internal const val MAX_GAP = 10000
internal const val MAX_SKIPPED_KEYS = 10000
internal const val MAX_SKIPPED_EPOCHS = 10
internal const val MAX_KEY_AGE_MS = 604_800_000L // 7 days in milliseconds
internal const val PENDING_TRANSITION_TIMEOUT_MS = MAX_KEY_AGE_MS // 7 days
internal const val MAX_SEEN_DH_PUBS = 50

const val PROTOCOL_VERSION = 1
const val SUPPORTED_MAX_VERSION = 1
internal const val AAD_PREFIX = "Where-v1-Message"
internal const val PADDING_SIZE = 512

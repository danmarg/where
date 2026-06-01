package net.af0.where.e2ee

internal const val INFO_RATCHET_STEP = "Where-v1-RatchetStep"
internal const val INFO_MSG_NONCE = "Where-v1-MsgNonce"
internal const val INFO_KEY_EXCHANGE = "Where-v1-KeyExchange"
internal const val INFO_ROUTING_TOKEN = "Where-v1-RoutingToken"
internal const val INFO_CONFIRM = "Where-v1-ConfirmKey"

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
internal const val MAX_GAP = 10000
// Bound on the skipped-message-key cache. Sized to comfortably absorb a full
// MAX_MESSAGES_PER_POLL backlog (500) with headroom; peer-influenceable but
// bounded at ~60 bytes/entry ≈ 60 KB worst case.
internal const val MAX_SKIPPED_KEYS = 1000
internal const val MAX_KEY_AGE_MS = 604_800_000L // 7 days in milliseconds

const val PROTOCOL_VERSION = 1
const val SUPPORTED_MAX_VERSION = 1
internal const val AAD_PREFIX = "Where-v1-Message"
internal const val PADDING_SIZE = 512

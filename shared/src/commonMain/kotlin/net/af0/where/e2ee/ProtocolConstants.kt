package net.af0.where.e2ee

/**
 * Domain-separation info strings used across all HKDF calls in the protocol.
 * Centralised here so every call site imports from one place (issue #13).
 */
internal const val INFO_RATCHET_STEP = "Where-v1-RatchetStep"
internal const val INFO_MSG_STEP = "Where-v1-MsgStep"
internal const val INFO_KEY_EXCHANGE = "Where-v1-KeyExchange"
internal const val INFO_SESSION = "Where-v1-Session"
internal const val INFO_ROUTING_TOKEN = "Where-v1-RoutingToken"
internal const val INFO_CONFIRM_KEY = "Where-v1-Confirm-Key"

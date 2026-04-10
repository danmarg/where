package net.af0.where.e2ee

/**
 * Discovery token for the initial key-exchange rendezvous (§4.2 / danmarg/where#5).
 *
 * The token is derived from a fresh random 32-byte [secret] that is embedded in
 * the QR payload alongside EK_A.pub. Only someone who received the QR out-of-band
 * can derive this token — in particular the server and any observer who later sees
 * EK_A.pub in a network message cannot compute it (fixes danmarg/where#115).
 *
 * Flow:
 *   1. Alice generates a QR; her app polls /inbox/{discoveryToken} during the pending
 *      invite phase.
 *   2. Bob scans the QR; his app POSTs KeyExchangeInitMessage to /inbox/{discoveryToken}.
 *   3. Alice receives the init, calls aliceProcessInit, and switches to polling
 *      the pairwise T_AB_0 routing token for all subsequent messages.
 *
 * The token is single-use: once Alice has processed Bob's init it is discarded.
 * The [secret] is ephemeral (freshly generated per QR), so each invite produces a
 * unique, unlinkable discovery token.
 *
 * @param secret Fresh random 32-byte secret from QrPayload.discoverySecret.
 * @return 16-byte discovery token (opaque; hex-encode for use as a URL path segment).
 */
fun deriveDiscoveryToken(secret: ByteArray): ByteArray =
    hkdfSha256(
        ikm = secret,
        salt = ByteArray(32),
        info = "Where-v1-Discovery".encodeToByteArray(),
        length = 16,
    )

/** Convenience extension — compute the discovery token from an existing QrPayload. */
fun QrPayload.discoveryToken(): ByteArray = deriveDiscoveryToken(discoverySecret)

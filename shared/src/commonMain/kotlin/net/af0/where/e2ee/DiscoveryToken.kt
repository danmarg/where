package net.af0.where.e2ee

/**
 * Discovery token for the initial key-exchange rendezvous (§4.0 / danmarg/where#5).
 *
 * Both Alice and Bob derive this from EK_A.pub — which is already present in the
 * QrPayload — so neither party needs prior knowledge of the other.
 *
 * Flow:
 *   1. Alice generates a QR; her app polls /inbox/{discoveryToken} during the pending
 *      invite phase.
 *   2. Bob scans the QR; his app POSTs KeyExchangeInitMessage to /inbox/{discoveryToken}.
 *   3. Alice receives the init, calls aliceProcessInit, and switches to polling
 *      the pairwise T_AB_0 routing token for all subsequent messages.
 *
 * The token is single-use: once Alice has processed Bob's init it is discarded.
 * EK_A is ephemeral (freshly generated per QR), so each invite produces a unique,
 * unlinkable discovery token.
 *
 * @param ekPub Alice's ephemeral X25519 public key (32 bytes), taken from QrPayload.ekPub.
 * @return 16-byte discovery token (opaque; hex-encode for use as a URL path segment).
 */
fun deriveDiscoveryToken(ekPub: ByteArray): ByteArray =
    hkdfSha256(
        ikm = ekPub,
        salt = ByteArray(32),
        info = "Where-v1-Discovery".encodeToByteArray(),
        length = 16,
    )

/** Convenience extension — compute the discovery token from an existing QrPayload. */
fun QrPayload.discoveryToken(): ByteArray = deriveDiscoveryToken(ekPub)

package net.af0.where.e2ee

/**
 * Key exchange protocol (§4) — ephemeral-only single X25519.
 *
 * Alice generates a fresh EK_A per invite. Bob generates EK_B and computes:
 *   SK = X25519(EK_B.priv, EK_A.pub)
 * Alice computes the same SK:
 *   SK = X25519(EK_A.priv, EK_B.pub)
 *
 * Bob includes key_confirmation = HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
 * in KeyExchangeInit. Alice MUST verify this before accepting the session.
 */

object KeyExchange {
    private const val CONFIRM_PREFIX = "Where-v1-Confirm"

    /**
     * Alice: create the QR / invite-link payload.
     * Returns (QrPayload, EK_A.priv). The caller MUST zero EK_A.priv after aliceProcessInit.
     */
    fun aliceCreateQrPayload(suggestedName: String): Pair<QrPayload, ByteArray> {
        val ek = generateX25519KeyPair()
        val fp = sha256(ek.pub).copyOfRange(0, 8).toHex()
        val payload =
            QrPayload(
                ekPub = ek.pub.copyOf(),
                suggestedName = suggestedName,
                fingerprint = fp,
            )
        return payload to ek.priv
    }

    /**
     * Bob: single-term DH, derive SK, compute key_confirmation HMAC,
     * and return the KeyExchangeInit message plus the initial session state.
     */
    fun bobProcessQr(
        qr: QrPayload,
        suggestedName: String,
    ): Pair<KeyExchangeInitMessage, SessionState> {
        val ekB = generateX25519KeyPair()
        val sk = x25519(ekB.priv, qr.ekPub)

        val aliceFp = fingerprint(qr.ekPub)
        val bobFp = fingerprint(ekB.pub)

        // Bob derives his send token (token for Bob → Alice) and Alice's recv token
        val tokenBobToAlice = deriveRoutingToken(sk, epoch = 0, senderFp = bobFp, recipientFp = aliceFp)
        val tokenAliceToBob = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)

        val kBundle =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_BUNDLE_AUTH.encodeToByteArray(),
                length = 32,
            )
        val session =
            initSession(
                sk = sk,
                isAlice = false,
                myEkPriv = ekB.priv,
                myEkPub = ekB.pub,
                theirEkPub = qr.ekPub,
                sendToken = tokenBobToAlice,
                recvToken = tokenAliceToBob,
                aliceFp = aliceFp,
                bobFp = bobFp,
                kBundle = kBundle,
            )

        val keyConfirmation = buildKeyConfirmation(sk, qr.ekPub, ekB.pub)
        val msg =
            KeyExchangeInitMessage(
                token = tokenAliceToBob.copyOf(), // Bob sends to Alice's "AliceToBob" inbox for discovery
                ekPub = ekB.pub.copyOf(),
                keyConfirmation = keyConfirmation,
                suggestedName = suggestedName,
            )
        return msg to session
    }

    /**
     * Alice: verify Bob's key_confirmation, recompute SK, and derive the sender session state.
     * Alice MUST zero aliceEkPriv immediately after this call.
     * Throws IllegalArgumentException if key_confirmation fails.
     */
    fun aliceProcessInit(
        msg: KeyExchangeInitMessage,
        aliceEkPriv: ByteArray,
        aliceEkPub: ByteArray,
    ): SessionState {
        val sk = x25519(aliceEkPriv, msg.ekPub)

        // Verify key confirmation before proceeding.
        require(verifyKeyConfirmation(sk, aliceEkPub, msg.ekPub, msg.keyConfirmation)) {
            "KeyExchangeInit key_confirmation failed — aborting key exchange"
        }

        val aliceFp = fingerprint(aliceEkPub)
        val bobFp = fingerprint(msg.ekPub)
        // Alice derives her send token (token for Alice → Bob) and Bob's recv token
        val tokenAliceToBob = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)
        val tokenBobToAlice = deriveRoutingToken(sk, epoch = 0, senderFp = bobFp, recipientFp = aliceFp)

        val kBundle =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_BUNDLE_AUTH.encodeToByteArray(),
                length = 32,
            )
        return initSession(
            sk = sk,
            isAlice = true,
            myEkPriv = aliceEkPriv.copyOf(),
            myEkPub = aliceEkPub.copyOf(),
            theirEkPub = msg.ekPub,
            sendToken = tokenAliceToBob,
            recvToken = tokenBobToAlice,
            aliceFp = aliceFp,
            bobFp = bobFp,
            kBundle = kBundle,
        )
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Derive the initial session state from SK.
     * HKDF expands SK to 96 bytes: [root_key (32) || chain_key_0 (32) || chain_key_1 (32)].
     * chain_key_0 is Alice's send / Bob's recv; chain_key_1 is the reverse.
     */
    internal fun initSession(
        sk: ByteArray,
        isAlice: Boolean,
        myEkPriv: ByteArray,
        myEkPub: ByteArray,
        theirEkPub: ByteArray,
        sendToken: ByteArray,
        recvToken: ByteArray,
        aliceFp: ByteArray,
        bobFp: ByteArray,
        kBundle: ByteArray,
    ): SessionState {
        val expanded =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_SESSION.encodeToByteArray(),
                length = 96,
            )
        val chainKey0 = expanded.copyOfRange(32, 64)
        val chainKey1 = expanded.copyOfRange(64, 96)
        return SessionState(
            rootKey = expanded.copyOfRange(0, 32),
            sendChainKey = if (isAlice) chainKey0 else chainKey1,
            recvChainKey = if (isAlice) chainKey1 else chainKey0,
            sendToken = sendToken.copyOf(),
            recvToken = recvToken.copyOf(),
            sendSeq = 0L,
            recvSeq = 0L,
            epoch = 0,
            myEkPriv = myEkPriv.copyOf(),
            myEkPub = myEkPub.copyOf(),
            theirEkPub = theirEkPub.copyOf(),
            aliceFp = aliceFp.copyOf(),
            bobFp = bobFp.copyOf(),
            kBundle = kBundle.copyOf(),
        )
    }

    internal fun buildKeyConfirmation(
        sk: ByteArray,
        ekAPub: ByteArray,
        ekBPub: ByteArray,
    ): ByteArray = hmacSha256(sk, CONFIRM_PREFIX.encodeToByteArray() + ekAPub + ekBPub)

    internal fun verifyKeyConfirmation(
        sk: ByteArray,
        ekAPub: ByteArray,
        ekBPub: ByteArray,
        expected: ByteArray,
    ): Boolean {
        val computed = buildKeyConfirmation(sk, ekAPub, ekBPub)
        if (computed.size != expected.size) return false
        var diff = 0
        for (i in computed.indices) diff = diff or (computed[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}

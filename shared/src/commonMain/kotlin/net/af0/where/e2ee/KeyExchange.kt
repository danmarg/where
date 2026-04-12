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
                discoverySecret = randomBytes(32),
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

        val session =
            initSession(
                sk = sk,
                isAlice = false,
                myDhPriv = ekB.priv,
                myDhPub = ekB.pub,
                theirDhPub = qr.ekPub,
                aliceFp = aliceFp,
                bobFp = bobFp,
            )

        val keyConfirmation = buildKeyConfirmation(sk, qr.ekPub, ekB.pub)

        // Initial token Bob sends to Alice for discovery is T_AB_0.
        val tokenAliceToBob = deriveRoutingToken(sk, aliceFp, bobFp)

        ekB.priv.fill(0)
        sk.fill(0)

        val msg =
            KeyExchangeInitMessage(
                token = tokenAliceToBob,
                ekPub = ekB.pub.copyOf(),
                keyConfirmation = keyConfirmation,
                suggestedName = suggestedName,
            )
        return msg to session
    }

    /**
     * Alice: verify Bob's key_confirmation, recompute SK, and derive the sender session state.
     * Alice MUST zero aliceEkPriv immediately after this call.
     * Throws AuthenticationException if key_confirmation fails.
     */
    fun aliceProcessInit(
        msg: KeyExchangeInitMessage,
        aliceEkPriv: ByteArray,
        aliceEkPub: ByteArray,
    ): SessionState {
        val sk = x25519(aliceEkPriv, msg.ekPub)

        // Verify key confirmation before proceeding.
        if (!verifyKeyConfirmation(sk, aliceEkPub, msg.ekPub, msg.keyConfirmation)) {
            sk.fill(0)
            throw AuthenticationException("KeyExchangeInit key_confirmation failed — aborting key exchange")
        }

        val aliceFp = fingerprint(aliceEkPub)
        val bobFp = fingerprint(msg.ekPub)

        val session =
            initSession(
                sk = sk,
                isAlice = true,
                myDhPriv = aliceEkPriv,
                myDhPub = aliceEkPub.copyOf(),
                theirDhPub = msg.ekPub,
                aliceFp = aliceFp,
                bobFp = bobFp,
            )
        sk.fill(0)

        // Alice immediately performs her first DH ratchet step to generate a new key A2.
        // This ensures her first message triggers a ratchet on Bob's side.
        val newLocalDh = generateX25519KeyPair()
        val dhOut = x25519(newLocalDh.priv, session.remoteDhPub)
        val step = kdfRk(session.rootKey, dhOut)
        dhOut.fill(0)

        val newSendToken = deriveRoutingToken(step.newRootKey, aliceFp, bobFp)

        return session.copy(
            rootKey = step.newRootKey,
            sendChainKey = step.newChainKey,
            sendToken = newSendToken,
            sendSeq = 0L,
            localDhPriv = newLocalDh.priv,
            localDhPub = newLocalDh.pub,
            prevSendToken = session.sendToken.copyOf(),
            isSendTokenPending = true,
        )
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Derive the initial session state from SK.
     * Alice/Bob start with a symmetric chain derived from SK.
     */
    internal fun initSession(
        sk: ByteArray,
        isAlice: Boolean,
        myDhPriv: ByteArray,
        myDhPub: ByteArray,
        theirDhPub: ByteArray,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): SessionState {
        val expanded =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_KEY_EXCHANGE.encodeToByteArray(),
                length = 64,
            )
        val chainKey0 = expanded.copyOfRange(0, 32)
        val chainKey1 = expanded.copyOfRange(32, 64)

        // Alice sends on chain 0, Bob receives on chain 0.
        // Bob sends on chain 1, Alice receives on chain 1.
        val sendChainKey = if (isAlice) chainKey0 else chainKey1
        val recvChainKey = if (isAlice) chainKey1 else chainKey0

        // Send token: sender is me, recipient is peer.
        val sendToken =
            if (isAlice) {
                deriveRoutingToken(sk, aliceFp, bobFp)
            } else {
                deriveRoutingToken(sk, bobFp, aliceFp)
            }
        // Recv token: sender is peer, recipient is me.
        val recvToken =
            if (isAlice) {
                deriveRoutingToken(sk, bobFp, aliceFp)
            } else {
                deriveRoutingToken(sk, aliceFp, bobFp)
            }

        expanded.fill(0)

        return SessionState(
            rootKey = sk.copyOf(),
            sendChainKey = sendChainKey,
            recvChainKey = recvChainKey,
            sendToken = sendToken,
            recvToken = recvToken,
            sendSeq = 0L,
            recvSeq = 0L,
            localDhPriv = myDhPriv.copyOf(),
            localDhPub = myDhPub.copyOf(),
            remoteDhPub = theirDhPub.copyOf(),
            aliceEkPub = (if (isAlice) myDhPub else theirDhPub).copyOf(),
            bobEkPub = (if (isAlice) theirDhPub else myDhPub).copyOf(),
            aliceFp = aliceFp.copyOf(),
            bobFp = bobFp.copyOf(),
            prevSendToken = sendToken.copyOf(),
            isSendTokenPending = false,
            isAlice = isAlice,
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

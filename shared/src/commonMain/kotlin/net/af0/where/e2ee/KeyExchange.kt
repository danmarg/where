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
        val fp = qrFingerprint(ek.pub)
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
     * Bob: verify the QR fingerprint, perform single-term DH, derive SK, compute
     * key_confirmation HMAC, and return the KeyExchangeInit message plus the initial
     * session state.
     *
     * Throws AuthenticationException if the fingerprint or key_confirmation fails.
     */
    fun bobProcessQr(
        qr: QrPayload,
        suggestedName: String,
    ): Pair<KeyExchangeInitMessage, SessionState> {
        // VERIFY FINGERPRINT (#157)
        val expectedFp = qrFingerprint(qr.ekPub)
        if (expectedFp != qr.fingerprint) {
            throw AuthenticationException("QR code fingerprint mismatch — possible tampering or mis-scan")
        }

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

        // MEMORY HYGIENE NOTE (§5.5): Bob's initial ephemeral key (ekB.priv) is copied into
        // the session.localDhPriv buffer within initSession. We zero the local ephemeral
        // buffer here, but the copy in SessionState intentionally persists to enable
        // the first DH ratchet step when Alice responds.
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

        // VERIFY TOKEN (#168): Alice MUST verify that Bob computed the same initial routing token.
        val expectedToken = deriveRoutingToken(sk, aliceFp, bobFp)
        if (!expectedToken.contentEquals(msg.token)) {
            sk.fill(0)
            throw AuthenticationException("KeyExchangeInit token mismatch — possible tampering or protocol error")
        }

        val session =
            initSession(
                sk = sk,
                isAlice = true,
                myDhPriv = aliceEkPriv,
                myDhPub = aliceEkPub,
                theirDhPub = msg.ekPub,
                aliceFp = aliceFp,
                bobFp = bobFp,
            )
        sk.fill(0)

        // To break the Double Ratchet deadlock and ensure we don't just stay in the
        // bootstrap symmetric chain forever, Alice (the initiator) performs the FIRST
        // DH ratchet step immediately after receiving Bob's bootstrap key (B0).
        // This generates A1, which Alice will send in her first location message.
        // Bob will see A1 != A0 and ratchet his own side.
        val newLocalDh = generateX25519KeyPair()
        val dhOut = x25519(newLocalDh.priv, msg.ekPub)
        val rkStep =
            try {
                kdfRk(session.rootKey, dhOut)
            } finally {
                dhOut.fill(0)
            }

        // Tokens also rotate when the rootKey changes.
        val newSendToken = deriveRoutingToken(rkStep.newRootKey, aliceFp, bobFp)
        val nextAliceSession =
            session.copy(
                rootKey = rkStep.newRootKey.copyOf(),
                sendChainKey = rkStep.newChainKey.copyOf(),
                // CRITICAL: Alice MUST keep the Epoch 0 headerKey for receiving Bob's
                // initial messages (B0). She only advances HER send side to Epoch 1.
                headerKey = session.headerKey.copyOf(),
                sendHeaderKey = session.nextHeaderKey.copyOf(),
                nextHeaderKey = rkStep.newHeaderKey.copyOf(),
                sendToken = newSendToken,
                localDhPriv = newLocalDh.priv.copyOf(),
                localDhPub = newLocalDh.pub.copyOf(),
                prevSendToken = session.sendToken.copyOf(),
                isSendTokenPending = true,
                pn = session.sendSeq,
                pr = session.recvSeq,
            )

        // Memory Hygiene: Wipe the bootstrap session's keys now that they are superseded by Epoch 1.
        // nextAliceSession preserves recvChainKey (Epoch 0 receiver) until Bob ratchets.
        // We MUST NOT wipe recvChainKey here because nextAliceSession is a shallow copy of session!
        session.rootKey.fill(0)
        session.sendChainKey.fill(0)
        // session.recvChainKey is shared with nextAliceSession via shallow copy. DO NOT FILL(0).

        newLocalDh.priv.fill(0)
        rkStep.newRootKey.fill(0)
        rkStep.newChainKey.fill(0)
        return nextAliceSession
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
                length = 160,
            )
        val chainKey0 = expanded.copyOfRange(0, 32)
        val chainKey1 = expanded.copyOfRange(32, 64)
        val initialRootKey = expanded.copyOfRange(64, 96)
        val initialHeaderKey = expanded.copyOfRange(96, 128)
        val nextHeaderKey = expanded.copyOfRange(128, 160)

        // Alice sends on chain 0, Bob receives on chain 0.
        // Bob sends on chain 1, Alice receives on chain 1.
        val sendChainKey = if (isAlice) chainKey0 else chainKey1
        val recvChainKey = if (isAlice) chainKey1 else chainKey0

        // Initial tokens are also derived from SK.
        val sendToken =
            if (isAlice) {
                deriveRoutingToken(sk, aliceFp, bobFp)
            } else {
                deriveRoutingToken(sk, bobFp, aliceFp)
            }
        val recvToken =
            if (isAlice) {
                deriveRoutingToken(sk, bobFp, aliceFp)
            } else {
                deriveRoutingToken(sk, aliceFp, bobFp)
            }

        expanded.fill(0)

        return SessionState(
            rootKey = initialRootKey,
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
            pn = 0L,
            pr = 0L,
            headerKey = initialHeaderKey,
            sendHeaderKey = initialHeaderKey,
            nextHeaderKey = nextHeaderKey,
        )
    }

    internal fun buildKeyConfirmation(
        sk: ByteArray,
        ekAPub: ByteArray,
        ekBPub: ByteArray,
    ): ByteArray {
        val kConfirm =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_CONFIRM.encodeToByteArray(),
                length = 32,
            )
        try {
            return hmacSha256(kConfirm, CONFIRM_PREFIX.encodeToByteArray() + ekAPub + ekBPub)
        } finally {
            kConfirm.fill(0)
        }
    }

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

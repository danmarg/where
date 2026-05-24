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
        // VERIFY PROTOCOL VERSION (#194)
        if (qr.protocolVersion > SUPPORTED_MAX_VERSION) {
            throw ProtocolVersionException("Unsupported protocol version ${qr.protocolVersion}")
        }

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

        // Initial token Bob sends to Alice for discovery is T_AB_0 (Alice -> Bob).
        // Bob is the scanner, so this token uses (AliceFp, BobFp).
        val tokenAliceToBob = deriveRoutingToken(sk, aliceFp, bobFp)

        // Derive K_name and encrypt suggestedName
        val kName = hkdfSha256(
            ikm = sk,
            salt = null,
            info = "Where-v1-SuggestedName".encodeToByteArray(),
            length = 32
        )
        val nameNonce = randomBytes(12)
        val nameCt = aeadEncrypt(
            key = kName,
            nonce = nameNonce,
            plaintext = suggestedName.encodeToByteArray(),
            aad = qr.ekPub + ekB.pub
        )
        val encryptedName = nameNonce + nameCt
        kName.zeroize()

        // MEMORY HYGIENE NOTE (§5.5): Bob's initial ephemeral key (ekB.priv) is copied into
        // the session.localDhPriv buffer within initSession. We zero the local ephemeral
        // buffer here, but the copy in SessionState intentionally persists to enable
        // the first DH ratchet step when Alice responds.
        ekB.priv.zeroize()
        sk.zeroize()

        val msg =
            KeyExchangeInitMessage(
                token = tokenAliceToBob,
                ekPub = ekB.pub.copyOf(),
                keyConfirmation = keyConfirmation,
                encryptedName = encryptedName,
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
        // VERIFY PROTOCOL VERSION (#194)
        if (msg.protocolVersion > SUPPORTED_MAX_VERSION) {
            throw ProtocolVersionException("Unsupported protocol version ${msg.protocolVersion}")
        }

        val sk = x25519(aliceEkPriv, msg.ekPub)
        try {
            // Verify key confirmation before proceeding.
            if (!verifyKeyConfirmation(sk, aliceEkPub, msg.ekPub, msg.keyConfirmation)) {
                val actualFp = qrFingerprint(aliceEkPub)
                throw AuthenticationException("KeyExchangeInit key_confirmation failed (expectedAliceFp=$actualFp) — aborting key exchange")
            }

            // Derive K_name and decrypt/verify the suggested name to bind it cryptographically
            val kName = hkdfSha256(
                ikm = sk,
                salt = null,
                info = "Where-v1-SuggestedName".encodeToByteArray(),
                length = 32
            )
            try {
                if (msg.encryptedName.size < 28) { // 12-byte nonce + 16-byte tag
                    throw AuthenticationException("encryptedName payload is too short")
                }
                val nonce = msg.encryptedName.copyOfRange(0, 12)
                val ct = msg.encryptedName.copyOfRange(12, msg.encryptedName.size)
                aeadDecrypt(
                    key = kName,
                    nonce = nonce,
                    ciphertext = ct,
                    aad = aliceEkPub + msg.ekPub
                )
            } catch (e: Exception) {
                if (e is AuthenticationException) throw e
                throw AuthenticationException("Failed to decrypt encrypted_name — aborting key exchange: ${e.message}")
            } finally {
                kName.zeroize()
            }

            val aliceFp = fingerprint(aliceEkPub)
            val bobFp = fingerprint(msg.ekPub)

            // VERIFY TOKEN (#168): Alice MUST verify that Bob computed the same initial routing token.
            // Alice is the initiator, so her SEND token (Alice->Bob) uses (AliceFp, BobFp).
            val expectedToken = deriveRoutingToken(sk, aliceFp, bobFp)
            if (!expectedToken.contentEquals(msg.token)) {
                val expectedHex = expectedToken.toHex()
                val providedHex = msg.token.toHex()
                throw AuthenticationException(
                    "KeyExchangeInit token mismatch (expected=$expectedHex, provided=$providedHex) — possible tampering or protocol error",
                )
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
                    dhOut.zeroize()
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
                    recvToken = session.recvToken.copyOf(),
                    prevSendChainKey = session.sendChainKey.copyOf(),
                    prevSendHeaderKey = session.sendHeaderKey.copyOf(),
                    localDhPriv = newLocalDh.priv.copyOf(),
                    localDhPub = newLocalDh.pub.copyOf(),
                    prevLocalDhPub = session.localDhPub.copyOf(),
                    prevSendToken = session.sendToken,
                    pr = session.recvSeq,
                )

            // Memory Hygiene: Wipe the bootstrap session's keys now that they are superseded by Epoch 1.
            // nextAliceSession preserves recvChainKey (Epoch 0 receiver) until Bob ratchets.
            // We MUST NOT wipe recvChainKey here because nextAliceSession is a shallow copy of session!
            session.rootKey.zeroize()
            session.sendChainKey.zeroize()
            session.localDhPriv.zeroize()
            // session.recvChainKey is shared with nextAliceSession via shallow copy. DO NOT FILL(0).

            newLocalDh.priv.zeroize()
            rkStep.newRootKey.zeroize()
            rkStep.newChainKey.zeroize()
            return nextAliceSession
        } finally {
            sk.zeroize()
        }
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
                length = 192,
            )
        val chainKey0 = expanded.copyOfRange(0, 32)
        val chainKey1 = expanded.copyOfRange(32, 64)
        val initialRootKey = expanded.copyOfRange(64, 96)
        val headerKey0 = expanded.copyOfRange(96, 128)
        val headerKey1 = expanded.copyOfRange(128, 160)
        val nextHeaderKey = expanded.copyOfRange(160, 192)

        // Alice sends on chain 0, Bob receives on chain 0.
        // Bob sends on chain 1, Alice receives on chain 1.
        val sendChainKey = if (isAlice) chainKey0 else chainKey1
        val recvChainKey = if (isAlice) chainKey1 else chainKey0

        // Alice sends with headerKey0, Bob receives with headerKey0.
        // Bob sends with headerKey1, Alice receives with headerKey1.
        val sendHeaderKey = if (isAlice) headerKey0 else headerKey1
        val recvHeaderKey = if (isAlice) headerKey1 else headerKey0

        // Initial tokens are also derived from SK.
        val localFp = if (isAlice) aliceFp else bobFp
        val remoteFp = if (isAlice) bobFp else aliceFp

        val sendToken = deriveRoutingToken(sk, localFp, remoteFp)
        val recvToken = deriveRoutingToken(sk, remoteFp, localFp)

        expanded.zeroize()

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
            prevLocalDhPub = ByteArray(0), // No previous local DH at bootstrap
            remoteDhPub = theirDhPub.copyOf(),
            aliceEkPub = (if (isAlice) myDhPub else theirDhPub).copyOf(),
            bobEkPub = (if (isAlice) theirDhPub else myDhPub).copyOf(),
            aliceFp = aliceFp.copyOf(),
            bobFp = bobFp.copyOf(),
            localFp = (if (isAlice) aliceFp else bobFp).copyOf(),
            remoteFp = (if (isAlice) bobFp else aliceFp).copyOf(),
            prevSendToken = sendToken,
            prevSendChainKey = sendChainKey.copyOf(),
            prevSendHeaderKey = sendHeaderKey.copyOf(),
            isAlice = isAlice,
            pn = 0L,
            pr = 0L,
            headerKey = recvHeaderKey,
            sendHeaderKey = sendHeaderKey,
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
            kConfirm.zeroize()
        }
    }

    internal fun verifyKeyConfirmation(
        sk: ByteArray,
        ekAPub: ByteArray,
        ekBPub: ByteArray,
        expected: ByteArray,
    ): Boolean {
        val computed = buildKeyConfirmation(sk, ekAPub, ekBPub)
        var diff = computed.size xor expected.size
        val len = minOf(computed.size, expected.size)
        for (i in 0 until len) diff = diff or (computed[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}

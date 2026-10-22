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
    /**
     * X25519 returns an all-zero shared secret (rather than throwing) for canonical
     * low-order public keys on at least one supported platform (see
     * X25519LowOrderPointTest). An attacker-chosen low-order public key would otherwise
     * force the DH output to this known constant, independent of the caller's own
     * private key — collapsing confidentiality of that channel to "anyone can decrypt,"
     * not just the attacker who supplied the bad key. Per §4.3 this check is required
     * at bootstrap SK derivation AND at every later DH ratchet step, so this is
     * `internal` rather than `private`: Session.performDhRatchet and
     * E2eeManager.decryptSuggestedName also call it.
     */
    internal fun requireNonZeroSharedSecret(sk: SharedSecret) {
        if (sk.isAllZero()) {
            sk.zeroize()
            throw AuthenticationException("X25519 produced an all-zero shared secret, rejecting low-order public key")
        }
    }

    /**
     * Alice: create the QR / invite-link payload.
     * Returns (QrPayload, EK_A.priv). The caller MUST zero EK_A.priv after aliceProcessInit.
     */
    fun aliceCreateQrPayload(
        suggestedName: String,
        nowSeconds: Long = currentTimeSeconds(),
        rng: Rng = SystemRng,
    ): Pair<QrPayload, DhPrivateKey> {
        val ek = rng.x25519KeyPair()
        val payload =
            QrPayload(
                ekPub = InvitePublicKey(ek.pub.copyOf()),
                suggestedName = suggestedName,
                discoverySecret = DiscoverySecret.random(rng),
                expiresAt = nowSeconds + INVITE_LIFETIME_SECONDS,
            )
        return payload to DhPrivateKey(ek.priv)
    }

    /**
     * Bob: perform single-term DH, derive SK, compute key_confirmation HMAC, and
     * return the KeyExchangeInit message plus the initial session state.
     *
     * Throws AuthenticationException if key_confirmation fails.
     */
    fun bobProcessQr(
        qr: QrPayload,
        suggestedName: String,
        nowSeconds: Long = currentTimeSeconds(),
        rng: Rng = SystemRng,
    ): Pair<KeyExchangeInitMessage, SessionState> {
        // VERIFY PROTOCOL VERSION (#194)
        if (qr.protocolVersion > SUPPORTED_MAX_VERSION) {
            throw ProtocolVersionException("Unsupported protocol version ${qr.protocolVersion}")
        }
        if (qr.isExpired(nowSeconds)) throw InviteExpiredException()

        val alicePub = qr.ekPub.toDhPublicKey()
        val ekB = rng.x25519KeyPair()
        val bobPriv = DhPrivateKey(ekB.priv)
        val bobPub = DhPublicKey(ekB.pub)
        val sk =
            try {
                dh(bobPriv, alicePub).also { requireNonZeroSharedSecret(it) }
            } catch (e: AuthenticationException) {
                bobPriv.zeroize()
                throw e
            }

        val session =
            initSession(
                sk = sk,
                isAlice = false,
                myDhPriv = bobPriv,
                myDhPub = bobPub,
                theirDhPub = alicePub,
                aliceFp = fingerprint(alicePub),
                bobFp = fingerprint(bobPub),
            )

        val keyConfirmation = buildKeyConfirmation(sk, alicePub, bobPub)

        val encryptedName = encryptSuggestedName(sk, alicePub, bobPub, suggestedName, rng)

        // MEMORY HYGIENE NOTE (§5.5): Bob's initial ephemeral key (ekB.priv) is copied into
        // the session.localDhPriv buffer within initSession. We zero the local ephemeral
        // buffer here, but the copy in SessionState intentionally persists to enable
        // the first DH ratchet step when Alice responds.
        ekB.priv.zeroize()
        sk.zeroize()

        val msg =
            KeyExchangeInitMessage(
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
        aliceEkPriv: DhPrivateKey,
        aliceEkPub: DhPublicKey,
        rng: Rng = SystemRng,
    ): SessionState {
        // VERIFY PROTOCOL VERSION (#194)
        if (msg.protocolVersion > SUPPORTED_MAX_VERSION) {
            throw ProtocolVersionException("Unsupported protocol version ${msg.protocolVersion}")
        }

        val alicePub = aliceEkPub
        val bobPub = DhPublicKey.fromWire(msg.ekPub)
        val sk = dh(aliceEkPriv, bobPub)
        requireNonZeroSharedSecret(sk)
        try {
            // Verify key confirmation before proceeding.
            if (!verifyKeyConfirmation(sk, alicePub, bobPub, msg.keyConfirmation)) {
                throw AuthenticationException("KeyExchangeInit key_confirmation failed — aborting key exchange")
            }

            // Decrypt/verify the suggested name to bind it cryptographically
            try {
                decryptSuggestedName(sk, alicePub, bobPub, msg.encryptedName)
            } catch (e: Exception) {
                if (e is AuthenticationException) throw e
                throw AuthenticationException("Failed to decrypt encrypted_name — aborting key exchange: ${e.message}")
            }

            val session =
                initSession(
                    sk = sk,
                    isAlice = true,
                    myDhPriv = aliceEkPriv,
                    myDhPub = alicePub,
                    theirDhPub = bobPub,
                    aliceFp = fingerprint(alicePub),
                    bobFp = fingerprint(bobPub),
                )

            // To break the Double Ratchet deadlock and ensure we don't just stay in the
            // bootstrap symmetric chain forever, Alice (the initiator) performs the FIRST
            // DH ratchet step immediately after receiving Bob's bootstrap key (B0).
            // This generates A1, which Alice will send in her first location message.
            // Bob will see A1 != A0 and ratchet his own side.
            val newLocalDh = rng.x25519KeyPair()
            val newLocalPriv = DhPrivateKey(newLocalDh.priv)
            val rkStep = dhRatchetSubStep(newLocalPriv, bobPub, session.rootKey)

            // Tokens also rotate when the rootKey changes.
            val newSendToken = session.parties.sendToken(rkStep.newRootKey)

            val nextAliceSession =
                session.copy(
                    rootKey = rkStep.newRootKey.copyOf(),
                    sendChainKey = rkStep.newChainKey.copyOf(),
                    // CRITICAL: Alice MUST keep the Epoch 0 headerKey for receiving Bob's
                    // initial messages (B0). She only advances HER send side to Epoch 1.
                    headerKey = session.headerKey?.copyOf(),
                    sendHeaderKey = session.nextHeaderKey?.copyOf(),
                    nextHeaderKey = rkStep.newHeaderKey.copyOf(),
                    sendToken = newSendToken,
                    recvToken = session.recvToken.copyOf(),
                    prevSendHeaderKey = session.sendHeaderKey?.copyOf(),
                    localDhPriv = newLocalPriv.copyOf(),
                    localDhPub = DhPublicKey(newLocalDh.pub.copyOf()),
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

            newLocalPriv.zeroize()
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

    internal fun encryptSuggestedName(
        sk: SharedSecret,
        ekAPub: DhPublicKey,
        ekBPub: DhPublicKey,
        suggestedName: String,
        rng: Rng,
    ): ByteArray {
        val kName = nameKey(sk)
        try {
            return sealRandomNonce(kName, suggestedName.encodeToByteArray(), aad = ekAPub.toByteArray() + ekBPub.toByteArray(), rng = rng)
        } finally {
            kName.zeroize()
        }
    }

    internal fun decryptSuggestedName(
        sk: SharedSecret,
        ekAPub: DhPublicKey,
        ekBPub: DhPublicKey,
        encryptedName: ByteArray,
    ): String {
        if (encryptedName.size < 28) { // 12-byte nonce + 16-byte tag
            throw AuthenticationException("encryptedName payload is too short")
        }
        val kName = nameKey(sk)
        try {
            return openRandomNonce(kName, encryptedName, aad = ekAPub.toByteArray() + ekBPub.toByteArray()).decodeToString()
        } catch (e: Exception) {
            throw AuthenticationException("Failed to decrypt suggested_name: ${e.message}")
        } finally {
            kName.zeroize()
        }
    }

    @OptIn(RawKeyAccess::class) // SK into HKDF
    private fun nameKey(sk: SharedSecret) = NameKey(hkdf(Label.SUGGESTED_NAME, ikm = sk.bytes, salt = null, length = KEY_SIZE))

    /**
     * Derive the initial session state from SK.
     * Alice/Bob start with a symmetric chain derived from SK.
     */
    @OptIn(RawKeyAccess::class) // SK into HKDF
    internal fun initSession(
        sk: SharedSecret,
        isAlice: Boolean,
        myDhPriv: DhPrivateKey,
        myDhPub: DhPublicKey,
        theirDhPub: DhPublicKey,
        aliceFp: Fingerprint,
        bobFp: Fingerprint,
    ): SessionState {
        val expanded =
            hkdf(Label.KEY_EXCHANGE, ikm = sk.bytes, salt = null, length = 192)
        val chainKey0 = ChainKey(expanded.copyOfRange(0, 32))
        val chainKey1 = ChainKey(expanded.copyOfRange(32, 64))
        val initialRootKey = RootKey(expanded.copyOfRange(64, 96))
        val headerKey0 = HeaderKey(expanded.copyOfRange(96, 128))
        val headerKey1 = HeaderKey(expanded.copyOfRange(128, 160))
        val nextHeaderKey = HeaderKey(expanded.copyOfRange(160, 192))

        // Alice sends on chain 0, Bob receives on chain 0.
        // Bob sends on chain 1, Alice receives on chain 1.
        val sendChainKey = if (isAlice) chainKey0 else chainKey1
        val recvChainKey = if (isAlice) chainKey1 else chainKey0

        // Alice sends with headerKey0, Bob receives with headerKey0.
        // Bob sends with headerKey1, Alice receives with headerKey1.
        val sendHeaderKey = if (isAlice) headerKey0 else headerKey1
        val recvHeaderKey = if (isAlice) headerKey1 else headerKey0

        // Initial tokens are also derived from SK.
        val parties = Parties.of(isAlice, aliceFp, bobFp)
        val sendToken = parties.sendToken(sk)
        val recvToken = parties.recvToken(sk)

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
            remoteDhPub = theirDhPub.copyOf(),
            aliceEkPub = (if (isAlice) myDhPub else theirDhPub).copyOf(),
            bobEkPub = (if (isAlice) theirDhPub else myDhPub).copyOf(),
            aliceFp = aliceFp.copyOf(),
            bobFp = bobFp.copyOf(),
            localFp = parties.local.copyOf(),
            remoteFp = parties.remote.copyOf(),
            prevSendToken = sendToken,
            prevSendHeaderKey = sendHeaderKey.copyOf(),
            isAlice = isAlice,
            pn = 0L,
            pr = 0L,
            headerKey = recvHeaderKey,
            sendHeaderKey = sendHeaderKey,
            nextHeaderKey = nextHeaderKey,
        )
    }

    @OptIn(RawKeyAccess::class) // SK into HKDF
    internal fun buildKeyConfirmation(
        sk: SharedSecret,
        ekAPub: DhPublicKey,
        ekBPub: DhPublicKey,
    ): ByteArray {
        val kConfirm = hkdf(Label.CONFIRM_KEY, ikm = sk.bytes, salt = null, length = KEY_SIZE)
        try {
            return hmacSha256(kConfirm, Label.CONFIRM.bytes + ekAPub.toByteArray() + ekBPub.toByteArray())
        } finally {
            kConfirm.zeroize()
        }
    }

    internal fun verifyKeyConfirmation(
        sk: SharedSecret,
        ekAPub: DhPublicKey,
        ekBPub: DhPublicKey,
        expected: ByteArray,
    ): Boolean {
        return constantTimeEquals(buildKeyConfirmation(sk, ekAPub, ekBPub), expected)
    }
}

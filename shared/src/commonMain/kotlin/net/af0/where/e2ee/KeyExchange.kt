package net.af0.where.e2ee

/**
 * Key exchange protocol (§4).
 *
 * Three-term DH (X3DH-lite) from Alice's QR to a bootstrapped shared session key SK.
 *
 * Protocol-level ordering of DH terms (from §4.2):
 *   DH1 = X25519(Bob.EK_B.priv, Alice.IK.pub) = X25519(Alice.IK.priv, Bob.EK_B.pub)
 *   DH2 = X25519(Bob.IK.priv, Alice.EK_A.pub) = X25519(Alice.EK_A.priv, Bob.IK.pub)
 *   DH3 = X25519(Bob.EK_B.priv, Alice.EK_A.pub) = X25519(Alice.EK_A.priv, Bob.EK_B.pub)
 *   SK  = HKDF-SHA-256(DH1 || DH2 || DH3, salt=null, info="Where-v1-KeyExchange")
 *
 * Each side computes the same DH values using its own private keys and the peer's public keys.
 * The DH terms must be concatenated in protocol order (DH1 || DH2 || DH3) on both sides.
 *
 * Convention: "senderFp" always refers to Alice (location sender), "recipientFp" to Bob.
 */

object KeyExchange {
    private const val CONFIRM_PLAINTEXT = "Where-v1-Confirm"

    /**
     * Alice: create the QR / invite-link payload.
     * Returns (QrPayload, EK_A.priv). The caller MUST zero EK_A.priv after aliceProcessInit.
     */
    fun aliceCreateQrPayload(
        identity: IdentityKeys,
        suggestedName: String,
    ): Pair<QrPayload, ByteArray> {
        val ek = generateX25519KeyPair()
        val signedData = identity.ik.pub + ek.pub + identity.sigIk.pub
        val sig = ed25519Sign(identity.sigIk.priv, signedData)
        val fp = sha256(identity.ik.pub).copyOfRange(0, 10).toHex()
        val payload =
            QrPayload(
                ikPub = identity.ik.pub.copyOf(),
                ekPub = ek.pub.copyOf(),
                sigPub = identity.sigIk.pub.copyOf(),
                suggestedName = suggestedName,
                fingerprint = fp,
                sig = sig,
            )
        return payload to ek.priv
    }

    /**
     * Bob: verify Alice's QR signature, perform the 3-term DH, derive SK and T_AB_0,
     * and return the KeyExchangeInit message plus the initial session state.
     *
     * @param qr        Alice's QR payload (location sender).
     * @param bobIdentity Bob's identity keypairs (location recipient).
     * @param aliceFp   SHA-256(Alice.IK.pub || Alice.SigIK.pub) — senderFp.
     * @param bobFp     SHA-256(Bob.IK.pub || Bob.SigIK.pub) — recipientFp.
     */
    fun bobProcessQr(
        qr: QrPayload,
        bobIdentity: IdentityKeys,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): Pair<KeyExchangeInitMessage, SessionState> {
        // Verify Alice's QR signature before touching any key material.
        val signedData = qr.ikPub + qr.ekPub + qr.sigPub
        require(ed25519Verify(qr.sigPub, signedData, qr.sig)) {
            "QR signature verification failed — aborting key exchange"
        }

        val ekB = generateX25519KeyPair()

        // 3-term DH in protocol order: DH1, DH2, DH3 (Bob's side).
        val dh1 = x25519(ekB.priv, qr.ikPub) // EK_B.priv × Alice.IK.pub
        val dh2 = x25519(bobIdentity.ik.priv, qr.ekPub) // IK_B.priv × Alice.EK_A.pub
        val dh3 = x25519(ekB.priv, qr.ekPub) // EK_B.priv × Alice.EK_A.pub
        val sk = deriveSK(dh1, dh2, dh3)

        // senderFp = aliceFp, recipientFp = bobFp (Alice sends location to Bob).
        val token = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)
        val session =
            KeyExchange.initSession(
                sk = sk,
                isSender = false, // Bob is the receiver of Alice's location
                myEkPriv = ekB.priv,
                myEkPub = ekB.pub,
                theirEkPub = qr.ekPub,
                routingToken = token,
            )

        val msgSignedData = bobIdentity.ik.pub + ekB.pub + bobIdentity.sigIk.pub
        val sig = ed25519Sign(bobIdentity.sigIk.priv, msgSignedData)
        val msg =
            KeyExchangeInitMessage(
                token = token.copyOf(),
                ikPub = bobIdentity.ik.pub.copyOf(),
                ekPub = ekB.pub.copyOf(),
                sigPub = bobIdentity.sigIk.pub.copyOf(),
                sig = sig,
            )
        return msg to session
    }

    /**
     * Alice: verify Bob's KeyExchangeInit, recompute SK, and derive the sender session state.
     * Alice MUST zero aliceEkPriv immediately after this call.
     *
     * @param msg          Bob's KeyExchangeInit.
     * @param aliceIdentity Alice's identity keypairs.
     * @param aliceEkPriv  Alice's ephemeral private key from aliceCreateQrPayload.
     * @param aliceEkPub   Alice's ephemeral public key (from the QrPayload.ekPub that was shown to Bob).
     * @param aliceFp      Alice's fingerprint (senderFp).
     * @param bobFp        Bob's fingerprint (recipientFp).
     */
    fun aliceProcessInit(
        msg: KeyExchangeInitMessage,
        aliceIdentity: IdentityKeys,
        aliceEkPriv: ByteArray,
        aliceEkPub: ByteArray,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): SessionState {
        // Verify Bob's signature.
        val signedData = msg.ikPub + msg.ekPub + msg.sigPub
        require(ed25519Verify(msg.sigPub, signedData, msg.sig)) {
            "KeyExchangeInit signature verification failed — aborting key exchange"
        }

        // 3-term DH in protocol order: DH1, DH2, DH3 (Alice's side).
        val dh1 = x25519(aliceIdentity.ik.priv, msg.ekPub) // IK_A.priv × Bob.EK_B.pub  = DH1
        val dh2 = x25519(aliceEkPriv, msg.ikPub) // EK_A.priv × Bob.IK.pub    = DH2
        val dh3 = x25519(aliceEkPriv, msg.ekPub) // EK_A.priv × Bob.EK_B.pub  = DH3
        val sk = deriveSK(dh1, dh2, dh3)

        val token = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)
        return initSession(
            sk = sk,
            isSender = true, // Alice is the sender of location
            myEkPriv = aliceEkPriv.copyOf(),
            myEkPub = aliceEkPub.copyOf(),
            theirEkPub = msg.ekPub,
            routingToken = token,
        )
    }

    /**
     * Bob: build the AES-256-GCM KeyConfirmation ciphertext.
     * Posted to T_AB_0 immediately after KeyExchangeInit.
     *
     * A dedicated confirmation key is derived from SK via HKDF with info
     * "Where-v1-Confirm-Key" to ensure no key material is shared with the
     * session encryption keys derived from the same SK.
     *
     * @param sk    32-byte session key.
     * @param token T_AB_0 (used as AAD).
     * @return AES-256-GCM ciphertext + 16-byte tag.
     */
    fun buildKeyConfirmation(
        sk: ByteArray,
        token: ByteArray,
    ): ByteArray {
        val confirmKey = deriveConfirmKey(sk)
        // Nonce is all-zero; confirmKey is single-use so (key, nonce) pair is unique.
        return aesgcmEncrypt(
            key = confirmKey,
            nonce = ByteArray(12),
            plaintext = CONFIRM_PLAINTEXT.encodeToByteArray(),
            aad = token,
        )
    }

    /**
     * Alice: verify Bob's KeyConfirmation before sending any location data.
     * Returns true if the confirmation decrypts and matches.
     */
    fun verifyKeyConfirmation(
        sk: ByteArray,
        token: ByteArray,
        ct: ByteArray,
    ): Boolean =
        try {
            val confirmKey = deriveConfirmKey(sk)
            val plaintext = aesgcmDecrypt(key = confirmKey, nonce = ByteArray(12), ciphertext = ct, aad = token)
            plaintext.contentEquals(CONFIRM_PLAINTEXT.encodeToByteArray())
        } catch (_: Exception) {
            false
        }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Final SK derivation. Takes the three DH outputs in protocol order (DH1, DH2, DH3)
     * and applies HKDF. Both Alice and Bob must pass the same DH values in the same order.
     */
    internal fun deriveSK(
        dh1: ByteArray,
        dh2: ByteArray,
        dh3: ByteArray,
    ): ByteArray =
        hkdfSha256(
            ikm = dh1 + dh2 + dh3,
            salt = null, // RFC 5869 §2.2: null → 32 zero bytes, canonical "no salt"
            info = INFO_KEY_EXCHANGE.encodeToByteArray(),
            length = 32,
        )

    /**
     * Derive the initial session state from SK.
     *
     * HKDF expands SK to 96 bytes: [root_key (32) || chain_key_0 (32) || chain_key_1 (32)].
     *
     * chain_key_0 is the send chain for the location sender (Alice) and the receive chain
     * for the recipient (Bob). chain_key_1 is the reverse. Assigning them based on [isSender]
     * ensures that send and receive chains are always independent — using the send chain to
     * derive receive keys (or vice versa) would break forward secrecy between the two directions.
     *
     * @param isSender true if the caller is Alice (location sender); false if Bob (recipient).
     */
    internal fun initSession(
        sk: ByteArray,
        isSender: Boolean,
        myEkPriv: ByteArray,
        myEkPub: ByteArray,
        theirEkPub: ByteArray,
        routingToken: ByteArray,
    ): SessionState {
        val expanded =
            hkdfSha256(
                ikm = sk,
                salt = null,
                info = INFO_SESSION.encodeToByteArray(),
                length = 96,
            )
        // chainKey0 = Alice's send / Bob's recv; chainKey1 = Bob's send / Alice's recv.
        val chainKey0 = expanded.copyOfRange(32, 64)
        val chainKey1 = expanded.copyOfRange(64, 96)
        return SessionState(
            rootKey = expanded.copyOfRange(0, 32),
            sendChainKey = if (isSender) chainKey0 else chainKey1,
            recvChainKey = if (isSender) chainKey1 else chainKey0,
            routingToken = routingToken.copyOf(),
            sendSeq = 0L,
            // seq counter starts at 1; recvSeq=0 means no messages received yet
            recvSeq = 0L,
            epoch = 0,
            myEkPriv = myEkPriv.copyOf(),
            myEkPub = myEkPub.copyOf(),
            theirEkPub = theirEkPub.copyOf(),
        )
    }

    private fun deriveConfirmKey(sk: ByteArray): ByteArray =
        hkdfSha256(
            ikm = sk,
            salt = null,
            info = INFO_CONFIRM_KEY.encodeToByteArray(),
            length = 32,
        )
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

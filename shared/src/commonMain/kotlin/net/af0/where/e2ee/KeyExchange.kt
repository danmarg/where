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
 *   SK  = HKDF-SHA-256(DH1 || DH2 || DH3, salt=zeroes, info="Where-v1-KeyExchange")
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
        val payload = QrPayload(
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
        val dh1 = x25519(ekB.priv, qr.ikPub)                    // EK_B.priv × Alice.IK.pub
        val dh2 = x25519(bobIdentity.ik.priv, qr.ekPub)          // IK_B.priv × Alice.EK_A.pub
        val dh3 = x25519(ekB.priv, qr.ekPub)                     // EK_B.priv × Alice.EK_A.pub
        val sk = deriveSK(dh1, dh2, dh3)

        // senderFp = aliceFp, recipientFp = bobFp (Alice sends location to Bob).
        val token = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)
        val session = KeyExchange.initSession(
            sk = sk, myEkPriv = ekB.priv, myEkPub = ekB.pub,
            theirEkPub = qr.ekPub, routingToken = token
        )

        val msgSignedData = bobIdentity.ik.pub + ekB.pub + bobIdentity.sigIk.pub
        val sig = ed25519Sign(bobIdentity.sigIk.priv, msgSignedData)
        val msg = KeyExchangeInitMessage(
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
     * @param aliceFp      Alice's fingerprint (senderFp).
     * @param bobFp        Bob's fingerprint (recipientFp).
     */
    fun aliceProcessInit(
        msg: KeyExchangeInitMessage,
        aliceIdentity: IdentityKeys,
        aliceEkPriv: ByteArray,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): SessionState {
        // Verify Bob's signature.
        val signedData = msg.ikPub + msg.ekPub + msg.sigPub
        require(ed25519Verify(msg.sigPub, signedData, msg.sig)) {
            "KeyExchangeInit signature verification failed — aborting key exchange"
        }

        // 3-term DH in protocol order: DH1, DH2, DH3 (Alice's side).
        val dh1 = x25519(aliceIdentity.ik.priv, msg.ekPub)   // IK_A.priv × Bob.EK_B.pub  = DH1
        val dh2 = x25519(aliceEkPriv, msg.ikPub)               // EK_A.priv × Bob.IK.pub    = DH2
        val dh3 = x25519(aliceEkPriv, msg.ekPub)               // EK_A.priv × Bob.EK_B.pub  = DH3
        val sk = deriveSK(dh1, dh2, dh3)

        val token = deriveRoutingToken(sk, epoch = 0, senderFp = aliceFp, recipientFp = bobFp)
        return initSession(
            sk = sk, myEkPriv = aliceEkPriv.copyOf(), myEkPub = ByteArray(32),
            theirEkPub = msg.ekPub, routingToken = token
        )
    }

    /**
     * Bob: build the AES-256-GCM KeyConfirmation ciphertext.
     * Posted to T_AB_0 immediately after KeyExchangeInit.
     *
     * @param sk    32-byte session key.
     * @param token T_AB_0 (used as AAD).
     * @return AES-256-GCM ciphertext + 16-byte tag.
     */
    fun buildKeyConfirmation(sk: ByteArray, token: ByteArray): ByteArray {
        // Nonce is all-zero; the key is single-use so (key, nonce) pair is unique.
        return aesgcmEncrypt(
            key = sk,
            nonce = ByteArray(12),
            plaintext = CONFIRM_PLAINTEXT.encodeToByteArray(),
            aad = token,
        )
    }

    /**
     * Alice: verify Bob's KeyConfirmation before sending any location data.
     * Returns true if the confirmation decrypts and matches.
     */
    fun verifyKeyConfirmation(sk: ByteArray, token: ByteArray, ct: ByteArray): Boolean =
        try {
            val plaintext = aesgcmDecrypt(key = sk, nonce = ByteArray(12), ciphertext = ct, aad = token)
            plaintext.contentEquals(CONFIRM_PLAINTEXT.encodeToByteArray())
        } catch (_: Exception) { false }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Final SK derivation. Takes the three DH outputs in protocol order (DH1, DH2, DH3)
     * and applies HKDF. Both Alice and Bob must pass the same DH values in the same order.
     */
    internal fun deriveSK(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        hkdfSha256(
            ikm = dh1 + dh2 + dh3,
            salt = ByteArray(32),
            info = INFO_KEY_EXCHANGE.encodeToByteArray(),
            length = 32,
        )

    /**
     * Derive the initial session state from SK.
     * HKDF expands SK to 64 bytes: [root_key (32) || send_chain_key (32)].
     */
    internal fun initSession(
        sk: ByteArray,
        myEkPriv: ByteArray,
        myEkPub: ByteArray,
        theirEkPub: ByteArray,
        routingToken: ByteArray,
    ): SessionState {
        val expanded = hkdfSha256(
            ikm = sk,
            salt = null,
            info = INFO_SESSION.encodeToByteArray(),
            length = 64,
        )
        return SessionState(
            rootKey = expanded.copyOfRange(0, 32),
            sendChainKey = expanded.copyOfRange(32, 64),
            routingToken = routingToken.copyOf(),
            sendSeq = 0L,
            recvSeq = 0L,  // seq counter starts at 1; recvSeq=0 means no messages received yet
            epoch = 0,
            myEkPriv = myEkPriv.copyOf(),
            myEkPub = myEkPub.copyOf(),
            theirEkPub = theirEkPub.copyOf(),
        )
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

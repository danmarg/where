package net.af0.where.e2ee

import kotlin.test.*

class AckRetirementTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `prevRecvToken is retired only after peer acks localDhPub`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        // 1. Alice (A1) sends to Bob (B0). Alice acks B0.
        val (alice1, msgA1) = Session.encryptMessage(aliceSession, loc)
        
        // Bob receives Alice's A1. A1 acks B0. Bob ratchets B0 -> B1.
        // SpeculativeState.localDhPub = B1. SpeculativeState.prevLocalDhPub = B0.
        // ackRemoteDhPub = B0. MATCH with prevLocalDhPub!
        // Bob retires T0 (bootstrap window).
        val (bob1, _) = Session.decryptMessage(bobSession, msgA1)
        assertTrue(bob1.prevRecvToken.isEmpty(), "Bob's prevRecvToken (bootstrap) should be retired after Alice acks B0")
        
        // 2. Bob (B1) sends to Alice (A1). Bob acks A1.
        val (bob2, msgB1) = Session.encryptMessage(bob1, loc)
        
        // Alice receives Bob's B1. B1 acks A1. Alice ratchets A1 -> A2.
        // SpeculativeState.localDhPub = A2. SpeculativeState.prevLocalDhPub = A1.
        // ackRemoteDhPub = A1. MATCH!
        // Alice retires T1 (bootstrap window).
        val (alice2, _) = Session.decryptMessage(alice1, msgB1)
        assertTrue(alice2.prevRecvToken.isEmpty(), "Alice's prevRecvToken (bootstrap) should be retired after Bob acks A1")
        
        // 3. Alice (A2) sends to Bob (B1). Alice acks B1.
        val (alice3, msgA2) = Session.encryptMessage(alice2, loc)
        
        // Bob receives Alice's A2. A2 acks B1. Bob ratchets B1 -> B2.
        // SpeculativeState.localDhPub = B2. SpeculativeState.prevLocalDhPub = B1.
        // ackRemoteDhPub = B1. MATCH!
        // Bob retires T1 (derived from Alice-at-A1).
        val (bob3, _) = Session.decryptMessage(bob2, msgA2)
        assertTrue(bob3.prevRecvToken.isEmpty(), "Bob's prevRecvToken should be retired after Alice acks B1 (triggering ratchet to B2)")
    }

    @Test
    fun `retiredDhPubs correctly rejects messages from ancient epochs`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        // Epoch 0: A0, B0
        val aliceInitialDh = aliceSession.aliceEkPub.copyOf()
        
        // Perform turns to advance Alice to A3, Bob to B3.
        var aS = aliceSession
        var bS = bobSession
        repeat(3) {
            val (aS1, msgA) = Session.encryptMessage(aS, loc)
            val (bS1, _) = Session.decryptMessage(bS, msgA)
            val (bS2, msgB) = Session.encryptMessage(bS1, loc)
            val (aS2, _) = Session.decryptMessage(aS1, msgB)
            aS = aS2
            bS = bS2
        }
        
        // Alice is at A3. remoteDhPub is B2. lastRemoteDhPub is B1. retiredDhPubs contains B0.
        assertTrue(aS.retiredDhPubs.contains(bS.bobEkPub.toHex()), "Alice's retiredDhPubs should contain Bob's bootstrap key after enough turns")

        // Attacker tries to replay a message from Bob's bootstrap epoch (B0)
        val recycledEnvelope = Session.encryptHeader(aS.headerKey, bS.bobEkPub, aS.localDhPub, 1L, 0L)
        val recycledPayload = EncryptedMessagePayload(envelope = recycledEnvelope, ct = ByteArray(32))

        assertFailsWith<ReplayException> {
            Session.decryptMessage(aS, recycledPayload)
        }
    }
}

private fun exchangeKeys(): Pair<SessionState, SessionState> {
    val aliceName = "Alice"
    val bobName = "Bob"
    val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(aliceName)
    val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, bobName)
    val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
    return aliceSession to bobSession
}

private fun ByteArray.isEmpty(): Boolean = this.all { it == 0.toByte() } || this.size == 0

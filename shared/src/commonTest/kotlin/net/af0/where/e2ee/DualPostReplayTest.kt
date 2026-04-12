package net.af0.where.e2ee

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DualPostReplayTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testDualPostReplayAcrossTokens() {
        val aliceEk = generateX25519KeyPair()
        val bobEk = generateX25519KeyPair()
        val sk = x25519(aliceEk.priv, bobEk.pub)
        
        val aliceFp = fingerprint(aliceEk.pub)
        val bobFp = fingerprint(bobEk.pub)

        // Setup Alice and Bob sessions
        var bobSession = KeyExchange.initSession(sk, isAlice = false, bobEk.priv, bobEk.pub, aliceEk.pub, aliceFp, bobFp)
        var aliceSession = KeyExchange.initSession(sk, isAlice = true, aliceEk.priv, aliceEk.pub, bobEk.pub, aliceFp, bobFp)

        println("Alice initial sendToken: ${aliceSession.sendToken.toHex()}")
        println("Bob initial recvToken: ${bobSession.recvToken.toHex()}")

        // 1. Alice sends first message (seq 1, epoch 0)
        val (updatedAlice, msg1) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(1.0, 1.0, 1.0, 100))
        aliceSession = updatedAlice
        val h1 = Session.decryptHeader(aliceSession.headerKey, msg1.envelope)
        println("Alice sent msg1: seq=${h1.seq} dh=${h1.dhPub.toHex()}")

        // 2. Alice sends second message
        val (finalAlice, msg2) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(2.0, 2.0, 1.0, 200))
        aliceSession = finalAlice
        val h2 = Session.decryptHeader(aliceSession.headerKey, msg2.envelope)
        println("Alice sent msg2: seq=${h2.seq} dh=${h2.dhPub.toHex()}")

        // 3. Bob receives msg1
        val (bobState1, _) = Session.decryptMessage(bobSession, msg1)
        bobSession = bobState1
        println("Bob processed msg1: recvSeq=${bobSession.recvSeq}")

        // 4. Bob receives msg2
        val h2_bob = Session.decryptHeader(bobSession.headerKey, msg2.envelope)
        println("Bob decrypting msg2: seq=${h2_bob.seq} current_recvSeq=${bobSession.recvSeq}")
        val (bobState2, _) = Session.decryptMessage(bobSession, msg2)
        bobSession = bobState2
        println("Bob processed msg2: recvSeq=${bobSession.recvSeq}")
        
        // 5. Simulate Replay: Bob receives msg2 AGAIN (e.g. from the old token)
        assertFailsWith<ProtocolException> {
            Session.decryptMessage(bobSession, msg2)
        }
    }

    @Test
    fun testTransactionalSafetyOnFailedRatchet() {
        val aliceEk = generateX25519KeyPair()
        val bobEk = generateX25519KeyPair()
        val sk = x25519(aliceEk.priv, bobEk.pub)
        val aliceFp = fingerprint(aliceEk.pub)
        val bobFp = fingerprint(bobEk.pub)

        var bobSession = KeyExchange.initSession(sk, isAlice = false, bobEk.priv, bobEk.pub, aliceEk.pub, aliceFp, bobFp)
        val initialState = bobSession.copy()

        // Create a message with a NEW DH key but TAMPERED ciphertext
        val attackerEk = generateX25519KeyPair()
        val envelope = Session.encryptHeader(bobSession.headerKey, attackerEk.pub, 1L, 0L)
        val badMsg = EncryptedMessagePayload(
            envelope = envelope,
            ct = ByteArray(32) { 0xFF.toByte() } // Junk
        )

        // Decryption must fail and State must remain UNCHANGED (transactional)
        assertFailsWith<AuthenticationException> {
            Session.decryptMessage(initialState, badMsg)
        }

        // Verify state is exactly as before
        assertEquals(initialState.rootKey.toHex(), bobSession.rootKey.toHex())
        assertEquals(initialState.remoteDhPub.toHex(), bobSession.remoteDhPub.toHex())
        assertEquals(initialState.recvSeq, bobSession.recvSeq)
        // Also verify the localDhPriv in INITIAL state wasn't zeroed by mistake
        assertFalse(initialState.localDhPriv.all { it == 0.toByte() })
    }
}

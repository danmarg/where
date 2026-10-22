package net.af0.where.e2ee

/**
 * Utility for decrypting headers and sorting a batch of messages chronologically.
 * Handles the "Sealed Envelope" multi-key trial decryption and the core decryption loop.
 */
internal object E2eeProtocol {
    data class DecryptionResult(
        val finalSession: SessionState,
        /** Every delivered plaintext, in ratchet order. */
        val delivered: List<MessagePlaintext>,
        val decryptedLocations: List<LocationPlaintext>,
        /** At least one message was decrypted and delivered to the application. */
        val anySuccess: Boolean,
        /**
         * At least one frame authenticated as coming from the peer, including soft-failed frames
         * (header authenticated, body corrupted or malformed). This is the peer-activity signal
         * (confirmation, lastRecvTs); [anySuccess] is the delivery signal.
         */
        val anyAuthenticated: Boolean,
        val anyReplay: Boolean,
        val processedIds: List<String>,
        val replayedIds: List<String>,
        val softFailCount: Int,
        val hardFailCount: Int,
        val silentDrops: Int,
    )

    /**
     * Decrypts headers for all [messages] using the keys available in [session].
     * Sorts successfully decrypted messages in chronological order (DH epoch then sequence).
     *
     * @return List of successfully decrypted headers paired with their original payloads.
     */
    fun decryptAndSort(
        session: SessionState,
        messages: List<EncryptedMessagePayload>,
    ): List<Pair<Session.DecryptedHeader, EncryptedMessagePayload>> {
        // Group into buckets by DH key to handle epoch transitions correctly, and always sort:
        // the result is the peer's send order (epoch, then seq), whatever order the server
        // returned. E2eeManager.processBatch relies on that to order Locations and stops.
        val ordered =
            messages.mapNotNull { msg ->
                try {
                    val header = tryDecryptHeader(session, msg.envelope)
                    header to msg
                } catch (e: Exception) {
                    null
                }
            }

        return ordered.sortedWith { (h1, _), (h2, _) ->
            val b1 = bucketForHeader(session, h1)
            val b2 = bucketForHeader(session, h2)

            if (b1 != b2) {
                b1.compareTo(b2)
            } else if (b1 == 2) {
                // A batch holds at most one epoch we haven't seen (§5.4.4), so pn is the same for
                // all of these and this is seq order; pn first only fixes an order for frames
                // from a second unseen epoch, which needs our state to have been rolled back.
                if (h1.pn != h2.pn) h1.pn.compareTo(h2.pn) else h1.seq.compareTo(h2.seq)
            } else {
                // Within the same bucket (DH epoch), sort by sequence number.
                // This is a defensive protocol invariant (§7.1): even if the server
                // delivery is out-of-order, the ratchet requires strictly increasing seq.
                h1.seq.compareTo(h2.seq)
            }
        }
    }

    /**
     * Executes the core decryption loop for a batch of ordered messages.
     * Commits session ratchets even on payload decryption failures ([ReceivedMessage.Undeliverable]).
     */
    fun decryptBatch(
        initialSession: SessionState,
        totalEncryptedCount: Int,
        orderedMessages: List<Pair<Session.DecryptedHeader, EncryptedMessagePayload>>,
        nowMillis: Long,
        rng: Rng,
    ): DecryptionResult {
        var currentSession = initialSession
        val delivered = mutableListOf<MessagePlaintext>()
        val decryptedLocations = mutableListOf<LocationPlaintext>()
        var anySuccess = false
        var anyAuthenticated = false
        var anyReplay = false
        var softFailCount = 0
        var hardFailCount = 0

        val processedIds = mutableListOf<String>()
        val replayedIds = mutableListOf<String>()

        for ((header, msg) in orderedMessages) {
            when (val received = Session.decryptMessage(currentSession, msg, header, nowMillis, rng)) {
                is ReceivedMessage.Delivered -> {
                    currentSession = received.state
                    anySuccess = true
                    anyAuthenticated = true
                    processedIds.add(msg.msgId)
                    val pt = received.plaintext
                    delivered.add(pt)
                    if (pt is MessagePlaintext.Location) {
                        decryptedLocations.add(
                            LocationPlaintext(
                                lat = pt.lat,
                                lng = pt.lng,
                                acc = pt.acc,
                                ts = pt.ts,
                                stationary = pt.stationary,
                            ),
                        )
                    }
                }
                is ReceivedMessage.Undeliverable -> {
                    // If header authenticated but payload failed, we MUST commit the
                    // ratcheted session state to prevent permanent DH desync (§5.5).
                    currentSession = received.state
                    softFailCount++
                    anyAuthenticated = true
                    // ACKed (deleted from the mailbox) even though nothing was delivered: the
                    // ciphertext can never be decrypted and its key is spent. Mailbox ACKs are
                    // transport cleanup only; senders never observe them (they don't read the
                    // receiver's mailbox), so this is indistinguishable from a server-side drop.
                    processedIds.add(msg.msgId)
                }
                is ReceivedMessage.Replay -> {
                    anyReplay = true
                    replayedIds.add(msg.msgId)
                }
                is ReceivedMessage.Rejected -> hardFailCount++
            }
        }

        return DecryptionResult(
            finalSession = currentSession,
            delivered = delivered,
            decryptedLocations = decryptedLocations,
            anySuccess = anySuccess,
            anyAuthenticated = anyAuthenticated,
            anyReplay = anyReplay,
            processedIds = processedIds,
            replayedIds = replayedIds,
            softFailCount = softFailCount,
            hardFailCount = hardFailCount,
            silentDrops = totalEncryptedCount - orderedMessages.size,
        )
    }

    private fun tryDecryptHeader(
        session: SessionState,
        envelope: ByteArray,
    ): Session.DecryptedHeader {
        val sessionAad = session.headerAad()
        return session.recvHeaderKeys().firstNotNullOfOrNull { key ->
            try {
                Session.decryptHeader(key, envelope, sessionAad)
            } catch (e: Exception) {
                null
            }
        } ?: throw Exception("All header keys failed")
    }

    internal fun bucketForHeader(
        session: SessionState,
        h: Session.DecryptedHeader,
    ): Int {
        return when {
            h.dhPub == session.remoteDhPub -> 1 // Current
            else -> 2 // Unknown NEW epoch (Newest)
        }
    }
}

package net.af0.where.e2ee

/**
 * Utility for decrypting headers and sorting a batch of messages chronologically.
 * Handles the "Sealed Envelope" multi-key trial decryption and the core decryption loop.
 */
internal object E2eeProtocol {

    data class DecryptionResult(
        val finalSession: SessionState,
        val decryptedLocations: List<LocationPlaintext>,
        val anySuccess: Boolean,
        val anyReplay: Boolean,
        val softFailCount: Int,
        val hardFailCount: Int,
        val silentDrops: Int
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
        val sortedMessagesWithHeaders =
            messages.mapNotNull { msg ->
                try {
                    val header = tryDecryptHeader(session, msg.envelope)
                    header to msg
                } catch (_: Exception) {
                    null // Un-decryptable header
                }
            }

        return sortedMessagesWithHeaders.sortedWith { (h1, _), (h2, _) ->
            val b1 = bucketForHeader(session, h1)
            val b2 = bucketForHeader(session, h2)

            if (b1 != b2) {
                b1.compareTo(b2)
            } else if (b1 == 2) {
                // For multiple unknown NEW epochs, sort by 'pn' to stay chronological (§7.1)
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
     * Commits session ratchets even on payload decryption failures (DecryptionExceptionWithState).
     */
    fun decryptBatch(
        initialSession: SessionState,
        totalEncryptedCount: Int,
        orderedMessages: List<Pair<Session.DecryptedHeader, EncryptedMessagePayload>>
    ): DecryptionResult {
        var currentSession = initialSession
        val decryptedLocations = mutableListOf<LocationPlaintext>()
        var anySuccess = false
        var anyReplay = false
        var softFailCount = 0
        var hardFailCount = 0

        for ((header, msg) in orderedMessages) {
            try {
                val (newSession, pt) = Session.decryptMessage(currentSession, msg, header)
                currentSession = newSession
                anySuccess = true
                if (pt is MessagePlaintext.Location) {
                    decryptedLocations.add(
                        LocationPlaintext(
                            lat = pt.lat,
                            lng = pt.lng,
                            acc = pt.acc,
                            ts = pt.ts,
                        ),
                    )
                }
            } catch (e: Exception) {
                if (e is ReplayException) {
                    anyReplay = true
                } else if (e is DecryptionExceptionWithState) {
                    // If header authenticated but payload failed, we MUST commit the 
                    // ratcheted session state to prevent permanent DH desync (§5.5).
                    currentSession = e.newState
                    softFailCount++
                } else {
                    hardFailCount++
                }
            }
        }

        return DecryptionResult(
            finalSession = currentSession,
            decryptedLocations = decryptedLocations,
            anySuccess = anySuccess,
            anyReplay = anyReplay,
            softFailCount = softFailCount,
            hardFailCount = hardFailCount,
            silentDrops = totalEncryptedCount - orderedMessages.size
        )
    }

    private fun tryDecryptHeader(
        session: SessionState,
        envelope: ByteArray,
    ): Session.DecryptedHeader {
        return try {
            Session.decryptHeader(session.headerKey, envelope)
        } catch (e0: Exception) {
            try {
                Session.decryptHeader(session.nextHeaderKey, envelope)
            } catch (e1: Exception) {
                // Last resort: try skipped epoch header keys (#212-followup)
                var found: Session.DecryptedHeader? = null
                for ((epoch, hk) in session.skippedEpochHeaderKeys) {
                    try {
                        found = Session.decryptHeader(hk, envelope)
                        break
                    } catch (_: Exception) {
                    }
                }
                found ?: throw Exception("All header keys failed")
            }
        }
    }

    internal fun bucketForHeader(
        session: SessionState,
        h: Session.DecryptedHeader,
    ): Int {
        return when {
            h.dhPub.contentEquals(session.remoteDhPub) -> 1
            h.dhPub.contentEquals(session.lastRemoteDhPub) -> 0
            session.seenRemoteDhPubs.contains(h.dhPub.toHex()) -> 3 // Ancient epoch (already superseded)
            else -> 2 // Unknown NEW epoch
        }
    }
}

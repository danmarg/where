package net.af0.where.e2ee

/**
 * Utility for decrypting headers and sorting a batch of messages chronologically.
 * Handles the "Sealed Envelope" multi-key trial decryption.
 */
internal object BatchProcessor {
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

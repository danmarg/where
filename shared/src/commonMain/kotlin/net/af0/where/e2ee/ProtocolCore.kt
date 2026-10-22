package net.af0.where.e2ee

/**
 * The protocol as one pure step function: `step(state, event, now, rng)` returns the next state
 * and the effects to carry out, and touches nothing else. No clock, no CSPRNG, no storage, no
 * network, no locks: given the same inputs it returns the same bytes on every platform, which is
 * what the golden vectors (commonTest/resources/vectors) pin down.
 *
 * [E2eeManager] and [LocationClient] interpret it: they load the state under the store lock, call
 * [step], persist the returned state, and turn the effects into outbox rows, mailbox acks and
 * token changes. App policy (names, clamping peer timestamps, retry and force-ACK rules, which
 * mailboxes to poll when) stays with them.
 *
 * A step that throws (a forged init, an expired invite, a bricked session, a batch from a stale
 * mailbox) leaves the caller's state as it was: nothing is to be persisted and no effect happens.
 *
 * Atomicity contract for interpreters (E2eeManager; AtomicityTest checks it):
 * - **One transaction.** The new state and every local effect commit together or not at all, in
 *   one store transaction under the store lock: the session, the outbox rows a [Effect.Put]
 *   queues and a [Effect.CancelPut] deletes, and the pending invite a [Event.ProcessInit]
 *   consumes ([Effect.RetireToken] for the discovery mailbox). A crash or failed write leaves the
 *   old state and outbox, and the step simply runs again.
 * - **After the commit, idempotent and loss-tolerant.** Mailbox I/O happens only after the commit
 *   and may be repeated or lost to a crash: a PUT is retried from the outbox and is idempotent per
 *   msgId; an [Effect.Ack] lost to a crash leaves frames that come back as replays and are acked
 *   then; a [Effect.RetireToken] lost to a crash costs nothing, because the next poll reads the
 *   receive token from the saved session (LocationClient follows the saved token; the effect only
 *   counts follows), and frames left on the retired mailbox expire with the server's TTL (§5.4.5).
 * - **Polled mailbox.** A [Event.Receive] names the mailbox it was polled from and is refused
 *   unless that is the session's current receive mailbox, so a stale or concurrent poll can't
 *   commit or ack anything.
 */
internal object ProtocolCore {
    /** One pairing, from one party's side. */
    sealed class State {
        /** Nothing yet: either side may start, by inviting or by scanning. */
        data object Idle : State()

        /** Alice, waiting for a scan of [qr]. [priv] is the invite's ephemeral key. */
        data class Inviting(val qr: QrPayload, val priv: DhPrivateKey) : State()

        /** Both sides hold [session]. */
        data class Paired(val session: SessionState) : State()
    }

    sealed class Event {
        /** Alice: mint an invite. The QR comes back in the [State.Inviting] state. */
        data class CreateInvite(val suggestedName: String) : Event()

        /** Bob: accept [qr], offering [suggestedName] as the name Alice sees for him. */
        data class ScanQr(val qr: QrPayload, val suggestedName: String) : Event()

        /** Alice: Bob's KeyExchangeInit arrived on the invite's discovery mailbox. */
        data class ProcessInit(val init: KeyExchangeInitPayload) : Event()

        /** Encrypt [payload] to the peer. */
        data class Send(val payload: MessagePlaintext) : Event()

        /**
         * A batch polled from [mailbox] (hex). It must be the session's current receive mailbox:
         * a batch from any other (a stale or concurrent poll, a misrouted caller) is refused with
         * [StaleMailboxException] before anything is decrypted, committed or acked.
         */
        data class Receive(val mailbox: String, val messages: List<MailboxPayload>) : Event()
    }

    /** Mailboxes are named by their token in hex, as the server and the outbox see them. */
    sealed class Effect {
        /** Post [payload] to [mailbox] (`PUT /inbox/{mailbox}/{payload.msgId}`), retrying until it lands. */
        data class Put(val mailbox: String, val payload: MailboxPayload) : Effect()

        /**
         * Drop the sender's queued [Put]s for [mailbox], its previous send token (§5.4.3). The
         * interpreter deletes every outbox row of the friend addressed to [mailbox], so this is
         * sound only because each such row is either the epoch's transition frame, whose delivery
         * the peer's ratchet proves, or was PUT before it: the outbox drains in order, and only the
         * epoch's first frame (seq 1) is addressed to the previous token, after the rest of the
         * previous epoch. Every later frame is addressed to the current send token and is never
         * touched. Not emitted while the previous and current send token are the same (the
         * responder's first ratchet), where that argument fails. BootstrapOutboxCancelTest and
         * the schedule property test (a message may leave an outbox only by being posted, or by
         * the server losing it) check this.
         */
        data class CancelPut(val mailbox: String) : Effect()

        /** Delete [ids] from [mailbox] (`DELETE /inbox/{mailbox}?ids=…`). */
        data class Ack(val mailbox: String, val ids: List<String>) : Effect()

        /** Hand [plaintext] from the peer to the app. */
        data class Deliver(val plaintext: MessagePlaintext) : Effect()

        /** Stop polling [mailbox]: nothing more will arrive there (the receive token rotated, or the invite was used). */
        data class RetireToken(val mailbox: String) : Effect()
    }

    /** What a [Event.Receive] saw beyond its effects, for the interpreter's bookkeeping. */
    data class ReceiveReport(
        /** At least one frame was delivered. */
        val anySuccess: Boolean,
        /** At least one frame authenticated as the peer's, delivered or not (peer-activity signal). */
        val anyAuthenticated: Boolean,
        val anyReplay: Boolean,
        /** Frames that authenticated but couldn't be delivered, plus frames that didn't authenticate. */
        val failCount: Int,
        /** Frames whose header no current key opened. */
        val silentDrops: Int,
        /** The batch advanced the receive chain or the root key. */
        val stateAdvanced: Boolean,
        /** The batch completed a DH ratchet step. */
        val dhRatchet: Boolean,
    )

    data class Step(
        val state: State,
        val effects: List<Effect>,
        val report: ReceiveReport? = null,
    )

    /**
     * Applies [event] to [state] at [nowMillis], drawing randomness from [rng]. Throws
     * [IllegalStateException] for an event the state can't take (e.g. [Event.Send] before
     * pairing) and the protocol's own exceptions for a rejected invite or init.
     */
    fun step(
        state: State,
        event: Event,
        nowMillis: Long,
        rng: Rng,
    ): Step =
        when (event) {
            is Event.CreateInvite -> expect<State.Idle>(state, event).let { createInvite(event, nowMillis, rng) }
            is Event.ScanQr -> expect<State.Idle>(state, event).let { scanQr(event, nowMillis, rng) }
            is Event.ProcessInit -> processInit(expect<State.Inviting>(state, event), event, rng)
            is Event.Send -> send(expect<State.Paired>(state, event).session, event, rng)
            is Event.Receive -> receive(expect<State.Paired>(state, event).session, event, nowMillis, rng)
        }

    private inline fun <reified S : State> expect(
        state: State,
        event: Event,
    ): S =
        state as? S
            ?: throw IllegalStateException("${event::class.simpleName} needs ${S::class.simpleName}, not ${state::class.simpleName}")

    private fun createInvite(
        event: Event.CreateInvite,
        nowMillis: Long,
        rng: Rng,
    ): Step {
        val (qr, priv) = KeyExchange.aliceCreateQrPayload(event.suggestedName, nowMillis / 1000, rng)
        return Step(State.Inviting(qr, priv), emptyList())
    }

    private fun scanQr(
        event: Event.ScanQr,
        nowMillis: Long,
        rng: Rng,
    ): Step {
        val (init, session) = KeyExchange.bobProcessQr(event.qr, event.suggestedName, nowMillis / 1000, rng)
        val payload =
            KeyExchangeInitPayload(
                v = init.protocolVersion,
                ekPub = init.ekPub,
                keyConfirmation = init.keyConfirmation,
                encryptedName = init.encryptedName,
                suggestedName = event.suggestedName,
            )
        return Step(State.Paired(session), listOf(Effect.Put(event.qr.discoveryToken().toHex(), payload)))
    }

    private fun processInit(
        state: State.Inviting,
        event: Event.ProcessInit,
        rng: Rng,
    ): Step {
        val msg =
            KeyExchangeInitMessage(
                protocolVersion = event.init.v,
                ekPub = event.init.ekPub,
                keyConfirmation = event.init.keyConfirmation,
                encryptedName = event.init.encryptedName,
            )
        // Work on a copy: the input state is the caller's, and must survive a rejected init intact.
        val priv = state.priv.copyOf()
        val session =
            try {
                KeyExchange.aliceProcessInit(msg, priv, state.qr.ekPub.toDhPublicKey(), rng)
            } finally {
                priv.zeroize()
            }
        return Step(State.Paired(session), listOf(Effect.RetireToken(state.qr.discoveryToken().toHex())))
    }

    private fun send(
        session: SessionState,
        event: Event.Send,
        rng: Rng,
    ): Step {
        val (next, message, destination) = Session.encryptMessage(session, event.payload, rng)
        val advanced =
            next.sendSeq > session.sendSeq ||
                next.pn > session.pn ||
                !next.rootKey.constantTimeEquals(session.rootKey)
        check(advanced) { "Nonce safety violation: sequence number did not advance" }
        return Step(State.Paired(next), listOf(Effect.Put(destination.toHex(), message)))
    }

    private fun receive(
        session: SessionState,
        event: Event.Receive,
        nowMillis: Long,
        rng: Rng,
    ): Step {
        if (event.mailbox != session.recvToken.toHex()) {
            throw StaleMailboxException("batch polled from a mailbox other than the current receive mailbox")
        }
        val encrypted = event.messages.filterIsInstance<EncryptedMessagePayload>()
        val ordered = E2eeProtocol.decryptAndSort(session, encrypted)
        val result = E2eeProtocol.decryptBatch(session, encrypted.size, ordered, nowMillis, rng)
        val next = result.finalSession

        val dhRatchet = !next.rootKey.constantTimeEquals(session.rootKey)
        val effects = mutableListOf<Effect>()
        result.delivered.mapTo(effects) { Effect.Deliver(it) }
        // The peer ratcheted, so it read our epoch's transition message (the first of the epoch,
        // posted to the previous mailbox; see SessionState.transportFor). Only that one is stale:
        // anything queued for the current send token still has to go. Not on Bob's first
        // ratchet: Alice ratchets on her own in aliceProcessInit, before reading anything of his,
        // and his previous send token is still the bootstrap one she polls (prevSendToken ==
        // sendToken until his first ratchet). Cancelling there dropped his queued frames unsent
        // (BootstrapOutboxCancelTest; ScheduleInterleavingPropertyTest finds it too).
        if (dhRatchet && session.prevSendToken != session.sendToken) {
            effects.add(Effect.CancelPut(session.transportFor(1).token.toHex()))
        }
        // Delivered, undeliverable (key spent) and replayed frames are done with; so is anything
        // that isn't an encrypted frame (nothing else belongs in a session mailbox).
        val ids = result.processedIds + result.replayedIds + event.messages.filter { it !is EncryptedMessagePayload }.map { it.msgId }
        if (ids.isNotEmpty()) effects.add(Effect.Ack(session.recvToken.toHex(), ids))
        if (next.recvToken != session.recvToken) effects.add(Effect.RetireToken(session.recvToken.toHex()))

        val report =
            ReceiveReport(
                anySuccess = result.anySuccess,
                anyAuthenticated = result.anyAuthenticated,
                anyReplay = result.anyReplay,
                failCount = result.softFailCount + result.hardFailCount,
                silentDrops = result.silentDrops,
                stateAdvanced = next.recvSeq > session.recvSeq || dhRatchet,
                dhRatchet = dhRatchet,
            )
        return Step(State.Paired(next), effects, report)
    }
}

package net.af0.where.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.*
import java.io.File
import kotlin.system.exitProcess
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.*
import net.af0.where.model.UserLocation
import java.util.Base64

class FileE2eeStorage(private val file: File) : E2eeStorage {
    private val json = Json { prettyPrint = true }
    private var data = mutableMapOf<String, String>()

    init {
        if (file.exists()) {
            try {
                data = json.decodeFromString(file.readText())
            } catch (e: Exception) {
                println("Warning: Could not load storage file: ${e.message}")
            }
        }
    }

    override fun getString(key: String): String? = data[key]

    override fun putString(key: String, value: String) {
        data[key] = value
        file.writeText(json.encodeToString(data))
    }
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun qrPayloadToUrl(qr: QrPayload): String {
    val json = Json { ignoreUnknownKeys = true }
    // Mimic the iOS/Android URL format
    // {"ekPub": "...", "suggestedName": "...", "fingerprint": "..."}
    val ekPubB64 = Base64.getEncoder().encodeToString(qr.ekPub)
    val map = mapOf(
        "ekPub" to ekPubB64,
        "suggestedName" to qr.suggestedName,
        "fingerprint" to qr.fingerprint
    )
    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(map).toByteArray())
    return "where://invite?q=$b64"
}

fun urlToQrPayload(url: String): QrPayload? {
    val q = url.substringAfter("q=").substringBefore("&")
    val json = Json { ignoreUnknownKeys = true }
    val decoded = String(Base64.getUrlDecoder().decode(q))
    val map: Map<String, String> = json.decodeFromString(decoded)
    val ekPub = Base64.getDecoder().decode(map["ekPub"] ?: return null)
    return QrPayload(
        ekPub = ekPub,
        suggestedName = map["suggestedName"] ?: "Friend",
        fingerprint = map["fingerprint"] ?: ""
    )
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: where-cli <command> [options]")
        println("Commands:")
        println("  invite <name>              Create an invite URL")
        println("  join <url> <name>          Join a friend using an invite URL")
        println("  list                       List friends")
        println("  poll                       Poll for updates")
        println("  send <lat> <lng>           Send location to all friends")
        println("Options:")
        println("  --host <url>               Specify the server host (default: http://localhost:8080)")
        println("  --state <path>             Specify the state file (default: where_cli_state.json)")
        exitProcess(1)
    }

    var statePath = "where_cli_state.json"
    val stateIdx = args.indexOf("--state")
    if (stateIdx != -1 && stateIdx + 1 < args.size) {
        statePath = args[stateIdx + 1]
    }

    val storageFile = File(statePath)
    val storage = FileE2eeStorage(storageFile)
    val store = E2eeStore(storage)

    var host = "http://localhost:8080"
    val hostIdx = args.indexOf("--host")
    if (hostIdx != -1 && hostIdx + 1 < args.size) {
        host = args[hostIdx + 1]
    }

    when (args[0]) {
        "invite" -> {
            val name = args.getOrNull(1) ?: "CLI User"
            val qr = store.createInvite(name)
            println("Invite URL: ${qrPayloadToUrl(qr)}")
            println("Discovery Token: ${qr.discoveryToken().toHex()}")
            println("Waiting for friend to join... (Ctrl+C to stop)")
            runBlocking {
                while (store.listFriends().isEmpty()) {
                    poll(store, host)
                    kotlinx.coroutines.delay(5000)
                }
                println("Friend joined!")
                store.listFriends().forEach { friend ->
                    println("Friend: ${friend.name} (${friend.id})")
                }
            }
        }
        "join" -> {
            val url = args.getOrNull(1) ?: run { println("URL required"); return }
            val name = args.getOrNull(2) ?: "Friend"
            val qr = urlToQrPayload(url) ?: run { println("Invalid URL"); return }
            
            runBlocking {
                val (initPayload, bobEntry) = store.processScannedQr(qr, name)
                val discoveryHex = qr.discoveryToken().toHex()
                try {
                    E2eeMailboxClient.post(host, discoveryHex, initPayload)
                    println("Joined ${qr.suggestedName} as $name")
                    
                    // Bob posts initial OPK bundle
                    val bundle = store.generateOpkBundle(bobEntry.id)
                    if (bundle != null) {
                        E2eeMailboxClient.post(host, bobEntry.session.routingToken.toHex(), bundle)
                    }
                } catch (e: Exception) {
                    println("Failed to join: ${e.message}")
                }
            }
        }
        "list" -> {
            val friends = store.listFriends()
            if (friends.isEmpty()) {
                println("No friends yet.")
            } else {
                friends.forEach { friend ->
                    println("${friend.id.take(8)}: ${friend.name} (${if (friend.isInitiator) "Alice" else "Bob"})")
                }
            }
        }
        "poll" -> {
            println("Polling for updates... (Ctrl+C to stop)")
            runBlocking {
                while (true) {
                    poll(store, host)
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
        "send" -> {
            val lat = args.getOrNull(1)?.toDoubleOrNull() ?: run { println("Lat required"); return }
            val lng = args.getOrNull(2)?.toDoubleOrNull() ?: run { println("Lng required"); return }
            runBlocking {
                sendLocation(store, host, lat, lng)
            }
        }
        else -> {
            println("Unknown command: ${args[0]}")
        }
    }
}

suspend fun poll(store: E2eeStore, host: String) {
    // Poll for pending invites if Alice
    store.pendingQrPayload?.let { qr ->
        val discoveryHex = qr.discoveryToken().toHex()
        val messages = E2eeMailboxClient.poll(host, discoveryHex)
        messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull()?.let { init ->
            println("Received KeyExchangeInit from ${init.suggestedName}")
            try {
                val entry = store.processKeyExchangeInit(init, init.suggestedName)
                println("Established session with ${entry?.name}")
            } catch (e: Exception) {
                println("Failed to process KeyExchangeInit: ${e.message}")
            }
        }
    }

    // Poll all friends
    for (friend in store.listFriends()) {
        val hexToken = friend.session.routingToken.toHex()
        val messages = try {
            E2eeMailboxClient.poll(host, hexToken)
        } catch (e: Exception) {
            println("Poll failed for ${friend.name}: ${e.message}")
            emptyList()
        }

        // Epoch rotation
        for (msg in messages.filterIsInstance<EpochRotationPayload>()) {
            println("Received EpochRotation from ${friend.name}")
            val ack = try {
                store.processEpochRotation(friend.id, msg)
            } catch (e: Exception) {
                println("Epoch rotation failed: ${e.message}")
                null
            } ?: continue

            val newToken = store.getFriend(friend.id)?.session?.routingToken?.toHex() ?: break
            E2eeMailboxClient.post(host, newToken, ack)
            
            // Replenish OPKs
            val bundle = store.generateOpkBundle(friend.id)
            if (bundle != null) {
                E2eeMailboxClient.post(host, newToken, bundle)
            }
        }

        // Cache OPKs
        for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
            println("Received PreKeyBundle from ${friend.name}")
            store.storeOpkBundle(friend.id, msg)
        }

        // Decrypt locations
        var session = store.getFriend(friend.id)?.session ?: continue
        var changed = false
        for (msg in messages.filterIsInstance<EncryptedLocationPayload>().sortedBy { it.seqAsLong() }) {
            val result = Session.decryptLocation(
                state = session,
                ct = msg.ct,
                seq = msg.seqAsLong(),
                senderFp = session.aliceFp,
                recipientFp = session.bobFp
            ) ?: continue
            session = result.first
            val loc = result.second
            println("Location from ${friend.name}: ${loc.lat}, ${loc.lng} at ${loc.ts}")
            changed = true
        }
        if (changed) {
            store.updateSession(friend.id, session)
        }

        // RatchetAck
        for (msg in messages.filterIsInstance<RatchetAckPayload>()) {
            store.processRatchetAck(friend.id, msg)
        }

        // Replenish OPKs if needed
        if (store.shouldReplenishOpks(friend.id)) {
            store.generateOpkBundle(friend.id)?.let { bundle ->
                E2eeMailboxClient.post(host, friend.session.routingToken.toHex(), bundle)
            }
        }
    }
}

suspend fun sendLocation(store: E2eeStore, host: String, lat: Double, lng: Double) {
    val ts = System.currentTimeMillis() / 1000
    val plaintext = LocationPlaintext(lat = lat, lng = lng, acc = 0.0, ts = ts)
    
    for (friend in store.listFriends()) {
        if (store.shouldRotateEpoch(friend.id)) {
            val oldToken = friend.session.routingToken.toHex()
            val rotPayload = store.initiateEpochRotation(friend.id)
            if (rotPayload != null) {
                E2eeMailboxClient.post(host, oldToken, rotPayload)
            }
        }

        val current = store.getFriend(friend.id) ?: continue
        val (newSession, ct) = Session.encryptLocation(
            state = current.session,
            location = plaintext,
            senderFp = current.session.aliceFp,
            recipientFp = current.session.bobFp
        )
        store.updateSession(friend.id, newSession)
        
        val payload = EncryptedLocationPayload(
            epoch = newSession.epoch,
            seq = newSession.sendSeq.toString(),
            ct = ct
        )
        E2eeMailboxClient.post(host, current.session.routingToken.toHex(), payload)
        println("Sent location to ${friend.name}")
    }
}

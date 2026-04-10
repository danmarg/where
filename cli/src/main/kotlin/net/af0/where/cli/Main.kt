package net.af0.where.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.*
import net.af0.where.initializeLibsodium
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

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

    override fun putString(
        key: String,
        value: String,
    ) {
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
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    // Mimic the iOS/Android URL format
    // {"ekPub": "...", "suggestedName": "...", "fingerprint": "..."}
    val ekPubB64 = Base64.getEncoder().encodeToString(qr.ekPub)
    val secretB64 = Base64.getEncoder().encodeToString(qr.discoverySecret)
    val map =
        mapOf(
            "ekPub" to ekPubB64,
            "suggestedName" to qr.suggestedName,
            "fingerprint" to qr.fingerprint,
            "discoverySecret" to secretB64,
        )
    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(map).toByteArray())
    return "where://invite?q=$b64"
}

fun printQrCode(url: String) {
    try {
        val writer = MultiFormatWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 10, 10)
        println("\n" + "═".repeat(bitMatrix.width * 2 + 4))
        println("║ " + " ".repeat(bitMatrix.width * 2) + " ║")
        for (y in 0 until bitMatrix.height) {
            print("║ ")
            for (x in 0 until bitMatrix.width) {
                print(if (bitMatrix[x, y]) "██" else "  ")
            }
            println(" ║")
        }
        println("║ " + " ".repeat(bitMatrix.width * 2) + " ║")
        println("═".repeat(bitMatrix.width * 2 + 4) + "\n")
    } catch (e: Exception) {
        println("(Could not generate QR code: ${e.message})")
    }
}

fun urlToQrPayload(url: String): QrPayload? {
    val q = url.substringAfter("q=").substringBefore("&")
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    val decoded = String(Base64.getUrlDecoder().decode(q))
    val map: Map<String, String> = json.decodeFromString(decoded)
    val ekPub = Base64.getDecoder().decode(map["ekPub"] ?: return null)
    val discoverySecret = map["discoverySecret"]?.let { Base64.getDecoder().decode(it) } ?: return null
    if (ekPub.size != 32 || discoverySecret.size != 32) return null
    return QrPayload(
        ekPub = ekPub,
        suggestedName = map["suggestedName"] ?: "Friend",
        fingerprint = map["fingerprint"] ?: "",
        discoverySecret = discoverySecret,
    )
}

fun main(args: Array<String>) {
    initializeLibsodium()
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

    val locationClient = LocationClient(host, store)

    when (args[0]) {
        "whoami" ->
            runBlocking {
                val friends = store.listFriends()
                if (friends.isEmpty()) {
                    println("No friends yet. Your ID will be your session fingerprint after pairing.")
                } else {
                    // Your ID is the other side's stored fingerprint for you (symmetrical)
                    val friend = friends.first()
                    val mySession = friend.session
                    val myFp = if (friend.isInitiator) mySession.aliceFp else mySession.bobFp
                    println("Your ID: ${myFp.toHex()}")
                }
            }
        "invite" ->
            runBlocking {
                val name = args.getOrNull(1) ?: "CLI User"
                val qr = store.createInvite(name)
                val url = qrPayloadToUrl(qr)
                println("Invite URL: $url")
                printQrCode(url)
                println("Discovery Token: ${qr.discoveryToken().toHex()}")
                if ("--no-wait" in args) return@runBlocking
                println("\nPress Enter to start waiting for friend to join... (Ctrl+C to stop)")
                readLine()
                while (store.listFriends().isEmpty()) {
                    poll(locationClient, store, host)
                    kotlinx.coroutines.delay(5000)
                }
                println("Friend joined!")
                store.listFriends().forEach { friend ->
                    println("Friend: ${friend.name} (${friend.id})")
                }
            }
        "join" -> {
            val url =
                args.getOrNull(1) ?: run {
                    println("URL required")
                    return
                }
            val name = args.getOrNull(2) ?: "Friend"
            val qr =
                urlToQrPayload(url) ?: run {
                    println("Invalid URL")
                    return
                }

            runBlocking {
                val (initPayload, bobEntry) = store.processScannedQr(qr, name)
                val discoveryHex = qr.discoveryToken().toHex()
                println("Bob discovery token: $discoveryHex")
                println("Bob ID: ${bobEntry.id}")
                try {
                    E2eeMailboxClient.post(host, discoveryHex, initPayload)
                    println("Posted KeyExchangeInit to mailbox")
                    println("Joined ${qr.suggestedName} as $name")

                    // Bob posts initial OPK bundle
                    locationClient.postOpkBundle(bobEntry.id)
                } catch (e: Exception) {
                    println("Failed to join: ${e.message}")
                }
            }
        }
        "list" ->
            runBlocking {
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
            val once = "--once" in args
            if (!once) println("Polling for updates... (Ctrl+C to stop)")
            runBlocking {
                while (true) {
                    poll(locationClient, store, host)
                    if (once) break
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
        "send" -> {
            val lat =
                args.getOrNull(1)?.toDoubleOrNull() ?: run {
                    println("Lat required")
                    return
                }
            val lng =
                args.getOrNull(2)?.toDoubleOrNull() ?: run {
                    println("Lng required")
                    return
                }
            val force = "--force" in args

            val lastSentStr = storage.getString("last_sent_time")
            val lastSent = lastSentStr?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()

            // Mirror the Android fix: 15s throttle for non-heartbeat.
            if (!force && now - lastSent < 15_000L) {
                println("Throttled: Last update was only ${(now - lastSent) / 1000}s ago. Use --force to override.")
                return
            }

            runBlocking {
                try {
                    locationClient.sendLocation(lat, lng)
                    storage.putString("last_sent_time", now.toString())
                    println("Location sent successfully.")
                } catch (e: Exception) {
                    println("Failed to send location: ${e.message}")
                }
            }
        }
        else -> {
            println("Unknown command: ${args[0]}")
        }
    }
}

suspend fun poll(
    locationClient: LocationClient,
    store: E2eeStore,
    host: String,
) {
    // Poll for pending invites if Alice
    store.pendingQrPayload()?.let { qr ->
        val discoveryHex = qr.discoveryToken().toHex()
        println("Alice polling mailbox with token: $discoveryHex")
        val messages = E2eeMailboxClient.poll(host, discoveryHex)
        println("Alice got ${messages.size} messages from mailbox")
        messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull()?.let { init ->
            println("Received KeyExchangeInit from ${init.suggestedName}")
            try {
                val entry = store.processKeyExchangeInit(init, init.suggestedName)
                val friend = store.getFriend(entry?.id ?: "")
                val sendToken = friend?.session?.sendToken?.toHex() ?: "?"
                println("Established session with ${entry?.name}, sendToken=$sendToken")
            } catch (e: Exception) {
                println("Failed to process KeyExchangeInit: ${e.message}")
            }
        }
    }

    // Poll all friends using shared LocationClient
    try {
        val updates = locationClient.poll()
        for (update in updates) {
            val friend = store.getFriend(update.userId)
            println("Location from ${friend?.name ?: update.userId}: ${update.lat}, ${update.lng} at ${update.timestamp}")
        }
    } catch (e: Exception) {
        println("Poll failed: ${e.message}")
    }
}

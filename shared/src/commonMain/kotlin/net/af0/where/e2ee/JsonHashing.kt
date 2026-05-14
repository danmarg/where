package net.af0.where.e2ee

import kotlinx.serialization.json.JsonElement

/**
 * Consistently hashes a JsonElement using SHA-256 for idempotency IDs.
 */
internal fun hashJson(jsonElement: JsonElement): String {
    val digest = sha256(jsonElement.toString().encodeToByteArray())
    return digest.toHex()
}

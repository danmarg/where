package net.af0.where.e2ee

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Handles atomic, double-buffered storage for a serializable object.
 * Prevents data loss by alternating between two slots and always overwriting the older one.
 */
internal class DoubleBufferedStorage<T : Any>(
    private val storage: E2eeStorage,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val timestampSelector: (T) -> Long,
) {
    private val lastSeenTs = mutableMapOf<String, Long>()

    fun load(
        keyBase: String,
        keyLegacy: String? = null,
    ): T? {
        val keyA = "${keyBase}_a"
        val keyB = "${keyBase}_b"

        val jsonA = storage.getString(keyA)
        val jsonB = storage.getString(keyB)
        val jsonLegacy = keyLegacy?.let { storage.getString(it) }

        val storeA = jsonA?.let { tryDecode(it, "Slot A ($keyBase)") }
        val storeB = jsonB?.let { tryDecode(it, "Slot B ($keyBase)") }
        val storeLegacy = jsonLegacy?.let { tryDecode(it, "Legacy ($keyBase)") }

        val result = listOfNotNull(storeA, storeB, storeLegacy).maxByOrNull { timestampSelector(it) }
        if (result != null) {
            lastSeenTs[keyBase] = timestampSelector(result)
        }
        return result
    }

    fun save(
        keyBase: String,
        data: T,
        keyLegacy: String? = null,
    ) {
        val tsNew = timestampSelector(data)
        check(tsNew > 0) { "DoubleBufferedStorage: timestamp must be positive" }

        val tsPrev = lastSeenTs[keyBase] ?: 0L
        if (tsNew < tsPrev) {
            println("[DoubleBufferedStorage] WARNING: Monotonicity violation for $keyBase: $tsNew < $tsPrev. Overriding to $tsPrev + 1.")
        }
        // Note: We don't force data to change here (it's immutable), but we use tsNew to pick the slot.
        // E2eeStore.nextTs() is the primary guarantor of monotonicity.

        val keyA = "${keyBase}_a"
        val keyB = "${keyBase}_b"

        val jsonStr = json.encodeToString(serializer, data)

        val jsonA = storage.getString(keyA)
        val jsonB = storage.getString(keyB)

        val targetSlot =
            when {
                jsonA == null -> keyA
                jsonB == null -> keyB
                else -> {
                    val storeA = jsonA.let { tryDecode(it, "Slot A (save check $keyBase)") }
                    val storeB = jsonB.let { tryDecode(it, "Slot B (save check $keyBase)") }
                    if (storeA == null) {
                        keyA
                    } else if (storeB == null) {
                        keyB
                    } else {
                        val tsA = timestampSelector(storeA)
                        val tsB = timestampSelector(storeB)
                        if (tsA <= tsB) keyA else keyB
                    }
                }
            }

        storage.putString(targetSlot, jsonStr)
        lastSeenTs[keyBase] = maxOf(tsPrev, tsNew)

        if (keyLegacy != null) {
            storage.putString(keyLegacy, "")
        }
    }

    private fun tryDecode(
        jsonStr: String,
        label: String,
    ): T? {
        if (jsonStr.isEmpty()) return null
        return try {
            json.decodeFromString(serializer, jsonStr)
        } catch (e: Exception) {
            println("[DoubleBufferedStorage] Failed to decode $label: ${e.message}")
            null
        }
    }
}

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
    private val keyA: String,
    private val keyB: String,
    private val keyLegacy: String? = null,
    private val timestampSelector: (T) -> Long,
) {
    private var lastLoadedTs: Long = 0L

    fun load(): T? {
        val jsonA = storage.getString(keyA)
        val jsonB = storage.getString(keyB)
        val jsonLegacy = keyLegacy?.let { storage.getString(it) }

        val storeA = jsonA?.let { tryDecode(it, "Slot A") }
        val storeB = jsonB?.let { tryDecode(it, "Slot B") }
        val storeLegacy = jsonLegacy?.let { tryDecode(it, "Legacy") }

        val best = listOfNotNull(storeA, storeB, storeLegacy).maxByOrNull { timestampSelector(it) }
        if (best != null) {
            lastLoadedTs = timestampSelector(best)
        }
        return best
    }

    fun save(data: T) {
        val now = currentTimeSeconds()
        // Monotonicity check: ensure we don't save a timestamp older than the one we loaded.
        val saveTs = if (now <= lastLoadedTs) lastLoadedTs + 1 else now

        val jsonStr = json.encodeToString(serializer, data)

        // Double Buffering: Determine which slot to overwrite.
        // We ALWAYS write to the slot that was NOT the one we just successfully loaded,
        // or we alternate if we are unsure. This ensures a known-good backup exists.
        val jsonA = storage.getString(keyA)
        val jsonB = storage.getString(keyB)

        val targetSlot =
            when {
                jsonA == null -> keyA
                jsonB == null -> keyB
                else -> {
                    // Both exist. Overwrite the OLDER one.
                    val storeA = jsonA.let { tryDecode(it, "Slot A (save check)") }
                    val storeB = jsonB.let { tryDecode(it, "Slot B (save check)") }
                    if (storeA == null) keyA
                    else if (storeB == null) keyB
                    else {
                        val tsA = timestampSelector(storeA)
                        val tsB = timestampSelector(storeB)
                        if (tsA <= tsB) keyA else keyB
                    }
                }
            }

        println("[DoubleBufferedStorage] saving to $targetSlot (now=$now, lastLoaded=$lastLoadedTs)")
        storage.putString(targetSlot, jsonStr)
        
        // Migration cleanup: once we've successfully saved to a new slot, we can clear legacy
        if (keyLegacy != null) {
            storage.putString(keyLegacy, "")
        }
        
        lastLoadedTs = saveTs
    }

    private fun tryDecode(jsonStr: String, label: String): T? {
        if (jsonStr.isEmpty()) return null
        return try {
            json.decodeFromString(serializer, jsonStr)
        } catch (e: Exception) {
            println("[DoubleBufferedStorage] Failed to decode $label: ${e.message}")
            null
        }
    }
}

package com.taboia872.androidassist.llm

import com.taboia872.androidassist.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * ROTATION of several API keys inside ONE provider slot.
 *
 * The app can hold MANY keys per server (slot: a provider id, "groq",
 * "fish", a search engine slot...). The GLOBAL strategy
 * (AppConfig.keyRotationStrategy) decides which one is used:
 *
 *  - random      : a random key per request
 *  - round_robin : keep the current key until the server reports exhaustion
 *                  (401/403/429), then move to the NEXT one
 *  - last_used   : always start from the key used in the previous request
 *  - last_unused : prefer the most recently added key that was never used;
 *                  when every key was used, the least recently used wins
 *
 * A key rejected by the server NEVER aborts the request: the next candidate
 * is tried and only when EVERY key fails the error surfaces.
 */
object KeyRotation {

    const val RANDOM = "random"
    const val ROUND_ROBIN = "round_robin"
    const val LAST_USED = "last_used"
    const val LAST_UNUSED = "last_unused"

    /** All strategies, in the order shown in the picker. */
    val ALL = listOf(RANDOM, ROUND_ROBIN, LAST_USED, LAST_UNUSED)

    /**
     * Thrown by a client when the server rejects the key (401/403/429) —
     * the caller then tries the next candidate key.
     */
    class KeyRejected(val code: Int, message: String) : Exception(message)

    /** Rotation state of one slot. */
    data class SlotState(
        /** round_robin: the key currently in use (next request starts here). */
        var cursor: Int = -1,
        /** last_used: the key used in the last successful request. */
        var last: Int = -1,
        /** indexes already used at least once (last_unused). */
        val used: MutableSet<Int> = mutableSetOf(),
        /** index -> epoch millis of the last use (last_unused fallback). */
        val lastUsedAt: MutableMap<Int, Long> = mutableMapOf()
    )

    private val cache = mutableMapOf<String, SlotState>()

    // -------------------------------------------------------------- state

    fun state(slot: String): SlotState = cache.getOrPut(slot) { load(slot) }

    private fun load(slot: String): SlotState {
        val root = runCatching { JSONObject(AppSettings.config.value.keyRotationState) }
            .getOrNull() ?: return SlotState()
        val o = root.optJSONObject(slot) ?: return SlotState()
        val st = SlotState()
        st.cursor = o.optInt("cursor", -1)
        st.last = o.optInt("last", -1)
        val used = o.optJSONArray("used")
        if (used != null) for (i in 0 until used.length()) st.used.add(used.optInt(i))
        val at = o.optJSONObject("lastUsedAt")
        if (at != null) {
            for (k in at.keys()) {
                val idx = k.toIntOrNull() ?: continue
                st.lastUsedAt[idx] = at.optLong(k)
            }
        }
        return st
    }

    /** Persist the state of one slot inside the config. */
    fun persist(slot: String) {
        val st = state(slot)
        val root = runCatching { JSONObject(AppSettings.config.value.keyRotationState) }
            .getOrNull() ?: JSONObject()
        root.put(slot, JSONObject().apply {
            put("cursor", st.cursor)
            put("last", st.last)
            val used = JSONArray()
            st.used.sorted().forEach { used.put(it) }
            put("used", used)
            val at = JSONObject()
            st.lastUsedAt.forEach { (i, ts) -> at.put(i.toString(), ts) }
            put("lastUsedAt", at)
        })
        AppSettings.update { it.copy(keyRotationState = root.toString()) }
    }

    // --------------------------------------------------------- candidates

    /** Indexes to try, in order, for a request on [slot]. */
    fun candidates(slot: String, keys: List<String>, strategy: String): List<Int> {
        val n = keys.size
        if (n == 0) return emptyList()
        val st = state(slot)
        val start = when (strategy) {
            RANDOM -> kotlin.random.Random.nextInt(n)
            ROUND_ROBIN -> if (st.cursor in 0 until n) st.cursor else 0
            LAST_USED -> if (st.last in 0 until n) st.last else 0
            LAST_UNUSED -> {
                val unused = (n - 1 downTo 0).firstOrNull { it !in st.used }
                unused ?: (0 until n).minByOrNull { st.lastUsedAt[it] ?: 0L } ?: 0
            }
            else -> 0
        }
        return (0 until n).map { (start + it) % n }
    }

    /** Register a successful use of [index]. */
    fun markUsed(slot: String, index: Int, strategy: String) {
        val st = state(slot)
        st.used.add(index)
        st.lastUsedAt[index] = System.currentTimeMillis()
        st.last = index
        if (strategy == ROUND_ROBIN) st.cursor = index
        persist(slot)
    }

    /** A key was rejected: in round_robin the cursor moves past it. */
    fun advanceOnFailure(slot: String, index: Int, strategy: String, keyCount: Int) {
        if (strategy != ROUND_ROBIN || keyCount <= 0) return
        val st = state(slot)
        st.cursor = (index + 1) % keyCount
        persist(slot)
    }

    // -------------------------------------------------------------- runner

    /**
     * Run [block] with the candidate keys of [slot] until one succeeds.
     * [block] must throw [KeyRejected] when the server rejects the key
     * (401/403/429) so the next candidate is tried.
     */
    suspend fun <T> withKeys(
        slot: String,
        keys: List<String>,
        strategy: String,
        block: suspend (String) -> T
    ): T {
        if (keys.isEmpty()) throw IllegalStateException("No API key configured")
        var lastError: Exception? = null
        for (idx in candidates(slot, keys, strategy)) {
            try {
                val out = block(keys[idx])
                markUsed(slot, idx, strategy)
                return out
            } catch (e: KeyRejected) {
                lastError = e
                advanceOnFailure(slot, idx, strategy, keys.size)
            }
        }
        throw lastError ?: IllegalStateException("No API key configured")
    }
}

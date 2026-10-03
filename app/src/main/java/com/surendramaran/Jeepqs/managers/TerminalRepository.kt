// app/src/main/java/com/surendramaran/Jeepqs/managers/TerminalRepository.kt
package com.surendramaran.Jeepqs.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.surendramaran.Jeepqs.services.SupabaseService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Terminal coordinates + geofence radius, loaded from Supabase `terminals`.
 *
 * Order of truth: Supabase (polled) -> last cached copy -> hardcoded defaults.
 * Listeners are called on the main thread ONLY when something actually changed.
 */
object TerminalRepository {

    private const val TAG = "TerminalRepository"
    private const val PREFS = "terminal_config"
    private const val KEY_JSON = "terminals_json"
    private const val POLL_INTERVAL_MS = 60_000L

    data class Terminal(
        val id: Int,
        val name: String,
        val lat: Double,
        val lng: Double,
        val radiusM: Float
    )

    // Last-resort fallback = the REAL terminals (same values as the admin app's REAL_TERMINALS).
    private val DEFAULTS = listOf(
        Terminal(1, "Donsol Terminal", 12.9032, 123.59425, 100f),
        Terminal(2, "Daraga Terminal", 13.14769, 123.71216, 100f)
    )

    @Volatile
    private var terminals: Map<Int, Terminal> = DEFAULTS.associateBy { it.id }

    private val listeners = CopyOnWriteArraySet<(Map<Int, Terminal>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var cacheLoaded = false

    fun all(): Map<Int, Terminal> = terminals
    fun get(id: Int): Terminal? = terminals[id]
    fun name(id: Int): String = terminals[id]?.name ?: "Terminal $id"

    fun addListener(l: (Map<Int, Terminal>) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Map<Int, Terminal>) -> Unit) { listeners.remove(l) }

    /** Loads the cached copy once, without starting the poller. Safe for WorkManager. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (!cacheLoaded) {
            loadCache(context.applicationContext)
            cacheLoaded = true
        }
    }

    /** Safe to call repeatedly. Loads cache, fetches now, then polls every 60s. */
    fun start(context: Context, supabase: SupabaseService) {
        val app = context.applicationContext
        ensureLoaded(app)
        if (pollRunnable != null) return

        pollRunnable = object : Runnable {
            override fun run() {
                refresh(app, supabase)
                main.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        main.post(pollRunnable!!)
    }

    fun stop() {
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
    }

    fun refresh(context: Context, supabase: SupabaseService) {
        supabase.getTerminals { list ->
            if (list.isNullOrEmpty()) {
                Log.w(TAG, "terminal fetch failed/empty; keeping current values")
                return@getTerminals
            }
            apply(context.applicationContext, list)
        }
    }

    /** For workers: cache first, then one bounded fetch. Falls back silently if offline. */
    fun refreshBlocking(context: Context, supabase: SupabaseService) {
        ensureLoaded(context)
        val latch = CountDownLatch(1)
        supabase.getTerminals { list ->
            if (!list.isNullOrEmpty()) apply(context.applicationContext, list)
            latch.countDown()
        }
        latch.await(8, TimeUnit.SECONDS)
    }

    private fun apply(context: Context, fetched: List<Terminal>) {
        val merged = terminals + fetched.associateBy { it.id }
        if (merged == terminals) return          // nothing changed

        terminals = merged
        saveCache(context, merged.values)
        Log.i(TAG, "terminals changed: $merged")
        main.post { listeners.forEach { it(merged) } }
    }

    private fun saveCache(context: Context, list: Collection<Terminal>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("lat", it.lat).put("lng", it.lng)
                    .put("radius", it.radiusM.toDouble())
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, arr.toString()).apply()
    }

    private fun loadCache(context: Context) {
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null) ?: return
            val arr = JSONArray(raw)
            val cached = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Terminal(
                    o.getInt("id"), o.getString("name"),
                    o.getDouble("lat"), o.getDouble("lng"),
                    o.getDouble("radius").toFloat()
                )
            }
            terminals = terminals + cached.associateBy { it.id }
            Log.i(TAG, "loaded cached terminals: $terminals")
        } catch (e: Exception) {
            Log.w(TAG, "cache load failed: ${e.message}")
        }
    }
}
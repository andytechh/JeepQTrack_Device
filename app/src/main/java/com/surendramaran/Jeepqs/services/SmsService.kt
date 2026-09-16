package com.surendramaran.Jeepqs.services

import android.content.Context
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import java.util.concurrent.TimeUnit

class SMSService(
    private val context: Context,
    private val supabase: SupabaseService
) {
    companion object {
        private const val TAG = "SMSService"

        private const val SMS_API_URL = "https://unismsapi.com/api/sms"
        private val SMS_SECRET_KEY = "sk_ziz_QD8gILD8hpCH3IrTaYXbhPHq6zWkoKl1sinUtAuTgGs8sW6v8COEkkVZ-AA7OXgPIfpNFEUCTJnLsP3mig-1136"
        private const val SENDER_ID = "Unisoft"

        // Prepended to every SMS body inside sendSms() — nothing downstream
        // can bypass it, regardless of which function built the message.
        private const val SMS_HEADER = "Smart Qs"

        private const val PUSH_API_URL = "https://exp.host/--/api/v2/push/send"
        private const val CONNECTION_TIMEOUT = 15L
        private const val WRITE_TIMEOUT = 15L
        private const val READ_TIMEOUT = 30L

        // Per-number cooldown — protects against spamming the SAME recipient
        // with the SAME/similar alert repeatedly. Staff notifications
        // (dispatcher/driver/admin) bypass this — see sendSms(bypassRateLimit).
        private const val SMS_MIN_INTERVAL_MS = 3 * 60 * 1000L // 3 min

        // Hard daily ceiling across the whole app — applies to EVERYONE,
        // staff included, since this is a cost/credit safety net, not a
        // spam filter.
        private const val SMS_DAILY_CAP = 300

        private val lastSentAt = ConcurrentHashMap<String, Long>()
        private var dailyCount = 0
        private var dailyCountDate = ""
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 20 // allow more than OkHttp's default 5 concurrent sends to UniSMS
            })
            .build()
    }

    private fun getCurrentTime(): String =
        SimpleDateFormat("hh:mm a", Locale.US).format(Date())

    private fun getEstimatedDepartureTime(minutes: Int = 30): String {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, minutes) }
        return SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
    }

    // ─── PHONE NORMALIZATION ────────────────────────────────────────

    private fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("63") && digits.length == 12 -> "+$digits"
            digits.startsWith("0") && digits.length == 11 -> "+63${digits.substring(1)}"
            digits.length == 10 && digits.startsWith("9") -> "+63$digits"
            else -> null
        }
    }

    // ─── RATE LIMIT CHECK ───────────────────────────────────────────
    // bypassInterval skips the per-number cooldown (used for staff, who
    // must receive every operational event regardless of how close
    // together they occur) but NEVER skips the daily cap.

    private fun allowedToSendSms(phoneNumber: String, bypassInterval: Boolean = false): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        synchronized(this) {
            if (dailyCountDate != today) {
                dailyCountDate = today
                dailyCount = 0
            }
            if (dailyCount >= SMS_DAILY_CAP) {
                Log.w(TAG, "SMS daily cap ($SMS_DAILY_CAP) reached — skipping $phoneNumber")
                return false
            }
        }

        if (!bypassInterval) {
            val now = System.currentTimeMillis()
            val last = lastSentAt[phoneNumber]
            if (last != null && now - last < SMS_MIN_INTERVAL_MS) {
                Log.w(TAG, "Rate-limited $phoneNumber (${(now - last) / 1000}s since last SMS)")
                return false
            }
        }

        lastSentAt[phoneNumber] = System.currentTimeMillis()
        synchronized(this) { dailyCount++ }
        return true
    }

    // ─── SEND SMS (UniSMS) ────────────────────────────────────────────
    // Single choke point for every SMS the app sends. Normalizes the
    // phone number and prepends the "Smart Qs" header here.
    //
    // bypassRateLimit = true for staff (dispatcher/driver/admin) so a
    // dispatcher who just got a "loading" SMS still receives "departure"
    // 90 seconds later instead of being silently dropped by the 3-min
    // per-number cooldown. Passengers keep the cooldown (default false).

    fun sendSms(
        to: String,
        content: String,
        bypassRateLimit: Boolean = false,
        callback: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        if (to.isEmpty()) {
            Log.w(TAG, "❌ sendSms skipped — empty phone number")
            callback(false, "Empty phone number")
            return
        }

        val normalized = normalizePhone(to)
        if (normalized == null) {
            Log.w(TAG, "❌ sendSms skipped — unrecognized phone format: $to")
            callback(false, "invalid_phone_format")
            return
        }

        if (!allowedToSendSms(normalized, bypassRateLimit)) {
            callback(false, "rate_limited")
            return
        }

        val headeredContent = "$SMS_HEADER\n$content"

        try {
            val json = JsonObject().apply {
                addProperty("recipient", normalized)
                addProperty("content", headeredContent)
                addProperty("sender_id", SENDER_ID)
            }

            val request = Request.Builder()
                .url(SMS_API_URL)
                .post(json.toString().toRequestBody(JSON))
                .header("Authorization", Credentials.basic(SMS_SECRET_KEY, ""))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "❌ SMS to $normalized failed (network): ${e.message}", e)
                    callback(false, e.message)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = response.body?.string()
                        if (!response.isSuccessful) {
                            if (response.code == 422 && body?.contains("spam", ignoreCase = true) == true) {
                                Log.e(TAG, "🚫 SMS to $normalized REJECTED as spam-like by UniSMS — content needs rewording: $body")
                            } else {
                                Log.e(TAG, "❌ SMS to $normalized failed: HTTP ${response.code} — $body")
                            }
                        } else {
                            Log.d(TAG, "✅ SMS to $normalized sent: $body")
                        }
                        callback(response.isSuccessful, body)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendSms threw before request was sent: ${e.message}", e)
            callback(false, null)
        }
    }

    // ─── SEND PUSH NOTIFICATION ──────────────────────────────────────

    fun sendPushNotification(
        expoToken: String,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap(),
        callback: (Boolean) -> Unit = {}
    ) {
        if (expoToken.isEmpty() || !expoToken.startsWith("ExponentPushToken")) {
            callback(false)
            return
        }

        try {
            val json = JsonObject().apply {
                addProperty("to", expoToken)
                addProperty("title", title)
                addProperty("body", message)
                addProperty("sound", "default")
                addProperty("priority", "high")
                addProperty("channelId", "jeepq_default")
                addProperty("ttl", 86400)

                add("data", JsonObject().apply {
                    data.forEach { (key, value) -> addProperty(key, value) }
                })
            }

            val request = Request.Builder()
                .url(PUSH_API_URL)
                .post(json.toString().toRequestBody(JSON))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { callback(response.isSuccessful) }
                }
            })
        } catch (_: Exception) {
            callback(false)
        }
    }

    // ─── NOTIFICATION CONTENT HELPER ─────────────────────────────────

    private fun createNotificationContent(
        eventType: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        userName: String
    ): Triple<String, String, JsonObject> {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val destination = if (terminalName.contains("Donsol")) "Daraga Terminal" else "Donsol Terminal"

        return when (eventType) {
            "arrival" -> Triple(
                "Jeepney Arrived",
                "Hello $userName!\nJeepney $displayName has arrived at $terminalName.\nDriver: $driverName\nQueue position assigned.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            "loading" -> Triple(
                "Loading Started",
                "Hello $userName!\nJeepney $displayName is now loading at $terminalName.\nDriver: $driverName\nEstimated departure: ${getEstimatedDepartureTime()}",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("loading_minutes", 30)
                }
            )
            "loading_complete" -> Triple(
                "Loading Complete",
                "Hello $userName!\nJeepney $displayName has finished loading at $terminalName.\nDriver: $driverName\nDeparting soon.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            "loading_alert" -> Triple(
                "Loading Alert",
                "Hello $userName!\nJeepney $displayName is still loading at $terminalName.\nDriver: $driverName\nAuto-departure in 5 minutes.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("loading_minutes", 25)
                }
            )
            "departure" -> Triple(
                "Jeepney Departing",
                "Hello $userName!\nJeepney $displayName is departing from $terminalName.\nDriver: $driverName\nDestination: $destination",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("destination", destination)
                }
            )
            "arrived_at_terminal" -> Triple(
                "Arrived at Terminal",
                "Hello $userName!\nJeepney $displayName has arrived at $terminalName.\nDriver: $driverName\nPassengers can now alight.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            else -> Triple(
                "Jeepney Update",
                "Hello $userName!\nJeepney $displayName update: $eventType\nTerminal: $terminalName",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("event_type", eventType)
                }
            )
        }
    }

    // ─── NOTIFY COMMUTERS ───────────────────────────────────────────

    private fun notifyCommuters(
        fetchUsers: (callback: (JSONArray?) -> Unit) -> Unit,
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int,
        eventType: String,
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        fetchUsers { users ->
            if (users == null || users.length() == 0) {
                onComplete(0, 0)
                return@fetchUsers
            }

            var completedCount = 0
            var sentCount = 0
            var failedCount = 0
            val totalUsers = users.length()

            for (i in 0 until totalUsers) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    val displayName = user.optString("display_name", "User")
                    val smsOptedIn = user.optBoolean("notifications_enabled", false)

                    val (title, message, jsonData) = createNotificationContent(
                        eventType, plateNumber, jeepName, driverName, terminalName, displayName
                    )

                    val data = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("terminal_id", terminalId)
                        addProperty("bracket", bracket)
                        jsonData.keySet().forEach { key -> add(key, jsonData.get(key)) }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        val pushData = mapOf(
                            "jeepney_id" to jeepneyId,
                            "plate_number" to plateNumber,
                            "type" to eventType,
                            "terminal_id" to terminalId.toString()
                        )
                        sendPushNotification(expoToken, title, message, pushData)
                    }

                    // Passengers keep the per-number cooldown (bypassRateLimit
                    // defaults to false) — deliberate, unlike staff below.
                    if (smsOptedIn && phoneNumber.isNotNullOrEmpty()) {
                        sendSms(phoneNumber, message) { success, _ ->
                            if (success) sentCount++ else failedCount++
                            completedCount++
                            if (completedCount >= totalUsers) onComplete(sentCount, failedCount)
                        }
                    } else {
                        completedCount++
                        if (completedCount >= totalUsers) onComplete(sentCount, failedCount)
                    }
                } catch (_: Exception) {
                    failedCount++
                    completedCount++
                    if (completedCount >= totalUsers) onComplete(sentCount, failedCount)
                }
            }
        }
    }

    private fun String?.isNotNullOrEmpty(): Boolean = !this.isNullOrEmpty()

    // ─── NOTIFY DISPATCHERS ─────────────────────────────────────────
    // bypassRateLimit = true: dispatchers must get every event (arrival,
    // loading, loading_alert, departure) even if they land within 3
    // minutes of each other for the same jeepney.

    fun notifyDispatchers(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String,
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        supabase.getDispatchers { users ->
            if (users == null || users.length() == 0) {
                onComplete(0, 0)
                return@getDispatchers
            }

            var completedCount = 0
            var sentCount = 0
            var failedCount = 0
            val total = users.length()

            for (i in 0 until total) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    val displayName = user.optString("display_name", "Dispatcher")

                    if (phoneNumber.isNullOrEmpty()) {
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                        continue
                    }

                    val (title, message, jsonData) = createNotificationContent(
                        eventType, plateNumber, jeepName, driverName, terminalName, displayName
                    )

                    val data = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        jsonData.keySet().forEach { key -> add(key, jsonData.get(key)) }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, title, message,
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to eventType,
                                "role" to "dispatcher"
                            )
                        )
                    }

                    sendSms(phoneNumber, message, bypassRateLimit = true) { success, _ ->
                        if (success) sentCount++ else failedCount++
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                    }
                } catch (_: Exception) {
                    failedCount++
                    completedCount++
                    if (completedCount >= total) onComplete(sentCount, failedCount)
                }
            }
        }
    }

    // ─── NOTIFY DRIVERS ─────────────────────────────────────────────

    fun notifyDrivers(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String,
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        supabase.getDrivers { users ->
            if (users == null || users.length() == 0) {
                onComplete(0, 0)
                return@getDrivers
            }

            var completedCount = 0
            var sentCount = 0
            var failedCount = 0
            val total = users.length()

            for (i in 0 until total) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    val displayName = user.optString("display_name", "Driver")

                    if (phoneNumber.isNullOrEmpty()) {
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                        continue
                    }

                    val (title, message, jsonData) = createNotificationContent(
                        eventType, plateNumber, jeepName, driverName, terminalName, displayName
                    )

                    val data = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        jsonData.keySet().forEach { key -> add(key, jsonData.get(key)) }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, title, message,
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to eventType,
                                "role" to "driver"
                            )
                        )
                    }

                    sendSms(phoneNumber, message, bypassRateLimit = true) { success, _ ->
                        if (success) sentCount++ else failedCount++
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                    }
                } catch (_: Exception) {
                    failedCount++
                    completedCount++
                    if (completedCount >= total) onComplete(sentCount, failedCount)
                }
            }
        }
    }

    // ─── NOTIFY ADMINS ──────────────────────────────────────────────

    fun notifyAdmins(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String,
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        supabase.getAdmins { users ->
            if (users == null || users.length() == 0) {
                onComplete(0, 0)
                return@getAdmins
            }

            var completedCount = 0
            var sentCount = 0
            var failedCount = 0
            val total = users.length()

            for (i in 0 until total) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    val displayName = user.optString("display_name", "Admin")

                    val (title, message, jsonData) = createNotificationContent(
                        eventType, plateNumber, jeepName, driverName, terminalName, displayName
                    )

                    val data = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        jsonData.keySet().forEach { key -> add(key, jsonData.get(key)) }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, title, message,
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to eventType,
                                "role" to "admin"
                            )
                        )
                    }

                    if (phoneNumber.isNullOrEmpty()) {
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                        continue
                    }

                    sendSms(phoneNumber, message, bypassRateLimit = true) { success, _ ->
                        if (success) sentCount++ else failedCount++
                        completedCount++
                        if (completedCount >= total) onComplete(sentCount, failedCount)
                    }
                } catch (_: Exception) {
                    failedCount++
                    completedCount++
                    if (completedCount >= total) onComplete(sentCount, failedCount)
                }
            }
        }
    }

    // ─── SPECIFIC EVENT NOTIFIERS ────────────────────────────────────

    fun notifyArrival(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int
    ) {
        notifyCommuters(
            fetchUsers = { cb -> supabase.getCommuters(terminalId, cb) },
            jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "arrival"
        )
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "arrival")
        notifyAdmins(jeepneyId, plateNumber, jeepName, driverName, terminalName, "arrival")
    }

    fun notifyLoadingStarted(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int
    ) {
        notifyCommuters(
            fetchUsers = { cb -> supabase.getCommuters(terminalId, cb) },
            jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading"
        )
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading")
        notifyAdmins(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading")
    }

    fun notifyLoadingComplete(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        occupancy: Int,
        terminalId: Int,
        bracket: Int
    ) {
        notifyCommuters(
            fetchUsers = { cb -> supabase.getCommuters(terminalId, cb) },
            jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading_complete"
        )
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading_complete")
        notifyAdmins(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading_complete")
    }

    fun notifyDeparture(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int
    ) {
        notifyCommuters(
            fetchUsers = { cb -> supabase.getCommuters(terminalId, cb) },
            jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "departure"
        )
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "departure")
        notifyDrivers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "departure")
        notifyAdmins(jeepneyId, plateNumber, jeepName, driverName, terminalName, "departure")
    }

    fun notifyLoadingAlert(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        minutes: Long,
        terminalId: Int,
        bracket: Int
    ) {
        notifyCommuters(
            fetchUsers = { cb -> supabase.getCommuters(terminalId, cb) },
            jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading_alert"
        )

        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            Loading alert
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading time: $minutes minutes
            Auto-departure in ${30 - minutes} minutes.
        """.trimIndent()

        supabase.getDispatchers { users ->
            if (users == null) return@getDispatchers
            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    if (phoneNumber.isNullOrEmpty()) continue
                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("loading_minutes", minutes)
                    }
                    supabase.insertNotificationAdmin(userId, "Loading Alert", message, "loading_alert", jsonData)
                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, "Loading Alert", message.take(100),
                            mapOf("jeepney_id" to jeepneyId, "plate_number" to plateNumber, "type" to "loading_alert")
                        )
                    }
                    sendSms(phoneNumber, message, bypassRateLimit = true)
                } catch (_: Exception) { }
            }
        }

        supabase.getAdmins { users ->
            if (users == null) return@getAdmins
            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("loading_minutes", minutes)
                    }
                    supabase.insertNotificationAdmin(userId, "Loading Alert", message, "loading_alert", jsonData)
                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, "Loading Alert", message.take(100),
                            mapOf("jeepney_id" to jeepneyId, "plate_number" to plateNumber, "type" to "loading_alert")
                        )
                    }
                    if (!phoneNumber.isNullOrEmpty()) sendSms(phoneNumber, message, bypassRateLimit = true)
                } catch (_: Exception) { }
            }
        }
    }

    // ─── OCCUPANCY ALERT ──────────────────────────────────────────────

    fun notifyOccupancyAlert(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        occupancy: Int,
        capacity: Int,
        terminalId: Int,
        bracket: Int
    ) {
        val percent = if (capacity > 0) (occupancy * 100) / capacity else 0
        if (percent < 80) return

        val message = """
            Occupancy alert
            Jeepney: $jeepName ($plateNumber)
            Driver: $driverName
            Passengers: $occupancy / $capacity ($percent%)
            Terminal: $terminalName
            Please dispatch additional jeepneys.
        """.trimIndent()

        supabase.getDispatchers { users ->
            if (users == null) return@getDispatchers
            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")
                    if (phoneNumber.isNullOrEmpty()) continue

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("occupancy", occupancy)
                        addProperty("capacity", capacity)
                        addProperty("percent", percent)
                    }

                    supabase.insertNotificationAdmin(userId, "Occupancy Alert", message, "occupancy", jsonData)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, "Occupancy Alert", message.take(100),
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to "occupancy",
                                "percent" to percent.toString()
                            )
                        )
                    }

                    sendSms(phoneNumber, message, bypassRateLimit = true)
                } catch (_: Exception) { }
            }
        }

        supabase.getAdmins { users ->
            if (users == null) return@getAdmins
            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val expoToken = user.optString("expo_push_token", "")

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("occupancy", occupancy)
                        addProperty("capacity", capacity)
                        addProperty("percent", percent)
                    }

                    supabase.insertNotificationAdmin(userId, "Occupancy Alert", message, "occupancy", jsonData)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken, "Occupancy Alert", message.take(100),
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to "occupancy",
                                "percent" to percent.toString()
                            )
                        )
                    }

                    if (!phoneNumber.isNullOrEmpty()) sendSms(phoneNumber, message, bypassRateLimit = true)
                } catch (_: Exception) { }
            }
        }
    }

    fun sendOccupancyAlert(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        occupancy: Int,
        capacity: Int,
        phoneNumber: String
    ) {
        val percent = if (capacity > 0) (occupancy * 100) / capacity else 0
        val message = """
            Occupancy alert
            Jeepney: $jeepName ($plateNumber)
            Driver: $driverName
            Passengers: $occupancy / $capacity ($percent%)
            Status: Reaching capacity
            Please dispatch additional jeepneys.
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }

    // ─── DIRECT SMS METHODS (utility) ─────────────────────────────────

    fun notifySingleUser(
        userId: String,
        phoneNumber: String,
        expoToken: String,
        displayName: String,
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String
    ) {
        if (phoneNumber.isEmpty()) return

        val (title, message, jsonData) = createNotificationContent(
            eventType, plateNumber, jeepName, driverName, terminalName, displayName
        )

        val data = JsonObject().apply {
            addProperty("jeepney_id", jeepneyId)
            addProperty("plate_number", plateNumber)
            jsonData.keySet().forEach { key -> add(key, jsonData.get(key)) }
        }

        supabase.insertNotificationAdmin(userId, title, message, eventType, data)

        if (expoToken.isNotEmpty()) {
            sendPushNotification(
                expoToken, title, message,
                mapOf("jeepney_id" to jeepneyId, "plate_number" to plateNumber, "type" to eventType)
            )
        }

        sendSms(phoneNumber, message)
    }

    fun sendArrivalNotification(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        queuePosition: Int, terminalName: String, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            Jeepney arrived
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Queue: #$queuePosition
            Time: ${getCurrentTime()}
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }

    fun sendLoadingNotification(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        terminalName: String, duration: Long, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val departTime = SimpleDateFormat("hh:mm a", Locale.US)
            .format(Date(System.currentTimeMillis() + duration * 60 * 1000))
        val message = """
            Loading started
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading: $duration min
            Departure: $departTime
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }

    fun sendDepartureNotification(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        terminalName: String, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val destination = if (terminalName.contains("Donsol")) "Daraga Terminal" else "Donsol Terminal"
        val message = """
            Jeepney departing
            Jeepney: $displayName
            Driver: $driverName
            From: $terminalName
            To: $destination
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }

    fun sendDispatchConfirmation(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        occupancy: Int, terminalName: String, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val destination = if (terminalName.contains("Donsol")) "Daraga Terminal" else "Donsol Terminal"
        val message = """
            Jeepney dispatched
            Jeepney: $displayName
            Driver: $driverName
            From: $terminalName
            To: $destination
            Passengers: $occupancy
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }

    fun sendQueueUpdate(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        terminalName: String, newPosition: Int, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            Queue update
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            New queue position: #$newPosition
            You are now closer to your turn.
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    fun sendLoadingAlertToDispatcher(
        jeepneyId: String, plateNumber: String, jeepName: String, driverName: String,
        terminalName: String, phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            Loading alert
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading time: 25 minutes
            Auto-departure in 5 minutes.
        """.trimIndent()
        sendSms(phoneNumber, message, bypassRateLimit = true)
    }
}
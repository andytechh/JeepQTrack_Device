// SMSService.kt – complete version
package com.surendramaran.Jeepqs.services

import android.content.Context
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SMSService(
    private val context: Context,
    private val supabase: SupabaseService
) {
    companion object {
        private const val SMS_API_URL = "https://api.httpsms.com/v1/messages/send"
        private const val SMS_API_KEY = "uk_Ol8Fny0jEw3W2dM6X1btPtALGraBjpqD_bNrIFSdLC6JFFDwGwC79FS_QW5ZpRex"
        private const val SENDER_NUMBER = "+639244508563"
        private const val PUSH_API_URL = "https://exp.host/--/api/v2/push/send"
        private const val CONNECTION_TIMEOUT = 15L
        private const val WRITE_TIMEOUT = 15L
        private const val READ_TIMEOUT = 30L
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getCurrentTime(): String =
        SimpleDateFormat("hh:mm a", Locale.US).format(Date())

    private fun getEstimatedDepartureTime(minutes: Int = 30): String {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, minutes) }
        return SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
    }

    // ─── SEND SMS ─────────────────────────────────────────────────────

    fun sendSms(to: String, content: String, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (to.isEmpty()) {
            callback(false, "Empty phone number")
            return
        }

        try {
            val json = JsonObject().apply {
                addProperty("from", SENDER_NUMBER)
                addProperty("to", to)
                addProperty("content", content)
                addProperty("encrypted", false)
            }

            val request = Request.Builder()
                .url(SMS_API_URL)
                .post(json.toString().toRequestBody(JSON))
                .addHeader("x-api-key", SMS_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(false, e.message)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = response.body?.string()
                        callback(response.isSuccessful, body)
                    }
                }
            })
        } catch (_: Exception) {
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
                    data.forEach { (key, value) ->
                        addProperty(key, value)
                    }
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
                    response.use {
                        callback(response.isSuccessful)
                    }
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
                "🚌 Jeepney Arrived",
                "Hello $userName!\nJeepney $displayName has arrived at $terminalName.\nDriver: $driverName\nQueue position assigned.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            "loading" -> Triple(
                "⏳ Loading Started",
                "Hello $userName!\nJeepney $displayName is now loading at $terminalName.\nDriver: $driverName\nEstimated departure: ${getEstimatedDepartureTime()}",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("loading_minutes", 30)
                }
            )
            "loading_complete" -> Triple(
                "✅ Loading Complete",
                "Hello $userName!\nJeepney $displayName has finished loading at $terminalName.\nDriver: $driverName\n🚀 Departing soon!",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            "loading_alert" -> Triple(
                "⏳ Loading Alert",
                "Hello $userName!\nJeepney $displayName is still loading at $terminalName.\nDriver: $driverName\nAuto-departure in 5 minutes.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("loading_minutes", 25)
                }
            )
            "departure" -> Triple(
                "🚀 Jeepney Departing",
                "Hello $userName!\nJeepney $displayName is departing from $terminalName.\nDriver: $driverName\nDestination: $destination",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("destination", destination)
                }
            )
            "arrived_at_terminal" -> Triple(
                "📍 Arrived at Terminal",
                "Hello $userName!\nJeepney $displayName has arrived at $terminalName.\nDriver: $driverName\nPassengers can now alight.",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )
            else -> Triple(
                "📢 Jeepney Update",
                "Hello $userName!\nJeepney $displayName update: $eventType\nTerminal: $terminalName",
                JsonObject().apply {
                    addProperty("plate_number", plateNumber)
                    addProperty("event_type", eventType)
                }
            )
        }
    }

    // ─── SEND TO ALL USERS ───────────────────────────────────────────

    fun notifyAllUsers(
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
        supabase.getCommuters(terminalId) { users ->
            if (users == null || users.length() == 0) {
                onComplete(0, 0)
                return@getCommuters
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

                    if (phoneNumber.isNullOrEmpty()) {
                        completedCount++
                        if (completedCount >= totalUsers) onComplete(sentCount, failedCount)
                        continue
                    }

                    val (title, message, jsonData) = createNotificationContent(
                        eventType, plateNumber, jeepName, driverName, terminalName, displayName
                    )

                    val data = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("terminal_id", terminalId)
                        addProperty("bracket", bracket)
                        jsonData.keySet().forEach { key ->
                            add(key, jsonData.get(key))
                        }
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

                    sendSms(phoneNumber, message) { success, _ ->
                        if (success) sentCount++ else failedCount++
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

    // ─── NOTIFY DISPATCHERS ──────────────────────────────────────────

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
                        jsonData.keySet().forEach { key ->
                            add(key, jsonData.get(key))
                        }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken,
                            title,
                            message,
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to eventType,
                                "role" to "dispatcher"
                            )
                        )
                    }

                    sendSms(phoneNumber, message) { success, _ ->
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

    // ─── NOTIFY DRIVERS ──────────────────────────────────────────────

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
                        jsonData.keySet().forEach { key ->
                            add(key, jsonData.get(key))
                        }
                    }

                    supabase.insertNotificationAdmin(userId, title, message, eventType, data)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken,
                            title,
                            message,
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to eventType,
                                "role" to "driver"
                            )
                        )
                    }

                    sendSms(phoneNumber, message) { success, _ ->
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
        notifyAllUsers(jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "arrival")
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "arrival")
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
        notifyAllUsers(jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading")
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading")
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
        notifyAllUsers(jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading_complete")
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "loading_complete")
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
        notifyAllUsers(jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "departure")
        notifyDispatchers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "departure")
        notifyDrivers(jeepneyId, plateNumber, jeepName, driverName, terminalName, "departure")
    }

    // ─── OCCUPANCY ALERT ─────────────────────────────────────────────

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
            ⚠️ OCCUPANCY ALERT
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

                    supabase.insertNotificationAdmin(userId, "⚠️ Occupancy Alert", message, "occupancy", jsonData)

                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken,
                            "⚠️ Occupancy Alert",
                            message.take(100),
                            mapOf(
                                "jeepney_id" to jeepneyId,
                                "plate_number" to plateNumber,
                                "type" to "occupancy",
                                "percent" to percent.toString()
                            )
                        )
                    }

                    sendSms(phoneNumber, message)
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
            ⚠️ OCCUPANCY ALERT
            Jeepney: $jeepName ($plateNumber)
            Driver: $driverName
            Passengers: $occupancy / $capacity ($percent%)
            Status: Reaching Capacity
            Please dispatch additional jeepneys.
            - JeepQ Track System
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    // ─── TEST NOTIFICATION ───────────────────────────────────────────

    fun testNotification(userId: String, phoneNumber: String, expoToken: String = "") {
        val title = "🧪 Test Notification"
        val message = "This is a test notification from JeepQ Track! 🚀"

        val jsonData = JsonObject().apply {
            addProperty("test", true)
            addProperty("timestamp", System.currentTimeMillis())
        }

        supabase.insertNotificationAdmin(userId, title, message, "system", jsonData)
        sendSms(phoneNumber, message)

        if (expoToken.isNotEmpty()) {
            sendPushNotification(
                expoToken = expoToken,
                title = title,
                message = message,
                data = mapOf(
                    "type" to "test",
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )
        }
    }

    // ─── DIRECT SMS METHODS ──────────────────────────────────────────

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
            jsonData.keySet().forEach { key ->
                add(key, jsonData.get(key))
            }
        }

        supabase.insertNotificationAdmin(userId, title, message, eventType, data)

        if (expoToken.isNotEmpty()) {
            sendPushNotification(
                expoToken,
                title,
                message,
                mapOf(
                    "jeepney_id" to jeepneyId,
                    "plate_number" to plateNumber,
                    "type" to eventType
                )
            )
        }

        sendSms(phoneNumber, message)
    }

    fun sendArrivalNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        queuePosition: Int,
        terminalName: String,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            🚌 JEEPNEY ARRIVED
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Queue: #$queuePosition
            Time: ${getCurrentTime()}
            - JeepQ Track System
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    fun sendLoadingNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        duration: Long,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val departTime = SimpleDateFormat("hh:mm a", Locale.US)
            .format(Date(System.currentTimeMillis() + duration * 60 * 1000))
        val message = """
            ⏳ LOADING STARTED
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading: $duration min
            Departure: $departTime
            - JeepQ Track System
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    fun sendDepartureNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val destination = if (terminalName.contains("Donsol")) "Daraga Terminal" else "Donsol Terminal"
        val message = """
            🚀 JEEPNEY DEPARTING
            Jeepney: $displayName
            Driver: $driverName
            From: $terminalName
            To: $destination
            - JeepQ Track System
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    fun sendDispatchConfirmation(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        occupancy: Int,
        terminalName: String,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val destination = if (terminalName.contains("Donsol")) "Daraga Terminal" else "Donsol Terminal"
        val message = """
            ✅ JEEPNEY DISPATCHED
            Jeepney: $displayName
            Driver: $driverName
            From: $terminalName
            To: $destination
            Passengers: $occupancy
            - JeepQ Track System
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    fun sendQueueUpdate(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        newPosition: Int,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            🔄 QUEUE UPDATE
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            New Queue Position: #$newPosition
            You are now closer to your turn!
        """.trimIndent()
        sendSms(phoneNumber, message)
    }

    // ─── LOADING ALERT (25-min) ──────────────────────────────────────

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
        // Broadcast to all commuters at this terminal
        notifyAllUsers(jeepneyId, plateNumber, jeepName, driverName, terminalName, terminalId, bracket, "loading_alert")

        // Dispatcher-only alert (keep existing logic)
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            ⏳ LOADING ALERT
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
                    supabase.insertNotificationAdmin(userId, "⏳ Loading Alert", message, "loading_alert", jsonData)
                    if (expoToken.isNotEmpty()) {
                        sendPushNotification(
                            expoToken,
                            "⏳ Loading Alert",
                            message.take(100),
                            mapOf("jeepney_id" to jeepneyId, "plate_number" to plateNumber, "type" to "loading_alert")
                        )
                    }
                    sendSms(phoneNumber, message)
                } catch (_: Exception) { }
            }
        }
    }

    fun sendLoadingAlertToDispatcher(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) "$jeepName ($plateNumber)" else plateNumber
        val message = """
            ⏳ LOADING ALERT
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading time: 25 minutes
            Auto-departure in 5 minutes.
        """.trimIndent()
        sendSms(phoneNumber, message)
    }
}
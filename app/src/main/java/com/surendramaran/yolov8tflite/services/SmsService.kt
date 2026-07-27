package com.surendramaran.Jeepqs.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class SMSService(
    private val context: Context,
    private val supabase: SupabaseService
) {
    companion object {
        private const val TAG = "SMSService"
        private const val API_URL = "https://api.httpsms.com/v1/messages/send"
        private const val API_KEY = "uk_aCh13hV8LlkN5l64mFXKQw1vn9ayYNZv4LNpmk5rzsp6f1Bzs8fUL2OGOJo1mL_5"
        private const val SENDER_NUMBER = "+639938901689"
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient()

    // ─── SEND SMS ─────────────────────────────────────────────────────
    fun sendSms(to: String, content: String, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        try {
            Log.d(TAG, "📱 Sending SMS to: $to")

            val json = JsonObject().apply {
                addProperty("from", SENDER_NUMBER)
                addProperty("to", to)
                addProperty("content", content)
                addProperty("encrypted", false)
            }

            val request = Request.Builder()
                .url(API_URL)
                .post(json.toString().toRequestBody(JSON))
                .addHeader("x-api-key", API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "❌ SMS failed: ${e.message}")
                    callback(false, e.message)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    val success = response.isSuccessful
                    response.close()

                    if (success) {
                        Log.d(TAG, "✅ SMS sent to $to")
                    } else {
                        Log.e(TAG, "❌ SMS error: $body")
                    }
                    callback(success, body)
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "❌ SMS exception: ${e.message}")
            callback(false, e.message)
        }
    }

    // ─── DIRECT SMS METHODS (For Dispatcher) ────────────────────────

    fun sendArrivalNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        queuePosition: Int,
        terminalName: String,
        phoneNumber: String
    ) {
        val displayName = if (jeepName.isNotEmpty()) {
            "$jeepName ($plateNumber)"
        } else {
            plateNumber
        }

        val message = """
🚌 JEEPNEY ARRIVED

Jeepney: $displayName
Driver: $driverName
Terminal: $terminalName
Queue Position: #$queuePosition
Status: Ready for loading
Time: ${getCurrentTime()}

📍 Waiting for loading instructions.

- JeepQ Track System
        """.trimIndent()

        Log.d(TAG, "📱 Sending arrival notification to dispatcher")
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
        val displayName = if (jeepName.isNotEmpty()) {
            "$jeepName ($plateNumber)"
        } else {
            plateNumber
        }

        val departTime = Date(System.currentTimeMillis() + duration * 60 * 1000)
        val formattedTime = SimpleDateFormat("hh:mm a", Locale.US).format(departTime)

        val message = """
⏳ LOADING STARTED

Jeepney: $displayName
Driver: $driverName
Terminal: $terminalName
Loading Time: $duration minutes
Estimated Departure: $formattedTime

⏰ Passengers, please be ready.

- JeepQ Track System
        """.trimIndent()

        Log.d(TAG, "📱 Sending loading notification to dispatcher")
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
        val displayName = if (jeepName.isNotEmpty()) {
            "$jeepName ($plateNumber)"
        } else {
            plateNumber
        }

        val destination = if (terminalName.contains("Donsol")) {
            "Daraga Terminal"
        } else {
            "Donsol Terminal"
        }

        val message = """
🚀 JEEPNEY DEPARTING

Jeepney: $displayName
Driver: $driverName
From: $terminalName
Destination: $destination
Status: En Route

📍 Tracking live at:
${if (terminalName.contains("Donsol")) "Donsol → Daraga" else "Daraga → Donsol"}

- JeepQ Track System
        """.trimIndent()

        Log.d(TAG, "📱 Sending departure notification to dispatcher")
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
        val displayName = if (jeepName.isNotEmpty()) {
            "$jeepName ($plateNumber)"
        } else {
            plateNumber
        }

        val destination = if (terminalName.contains("Donsol")) {
            "Daraga Terminal"
        } else {
            "Donsol Terminal"
        }

        val message = """
✅ JEEPNEY DISPATCHED

Jeepney: $displayName
Driver: $driverName
From: $terminalName
Destination: $destination
Passengers: $occupancy
Status: En Route

🛣️ Safe travels!

- JeepQ Track System
        """.trimIndent()

        Log.d(TAG, "📱 Sending dispatch confirmation to dispatcher")
        sendSms(phoneNumber, message)
    }

    // ─── NOTIFY ALL USERS ───────────────────────────────────────────
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
                Log.w(TAG, "⚠️ No commuters found for terminal $terminalId")
                onComplete(0, 0)
                return@getCommuters
            }

            Log.d(TAG, "📢 Notifying ${users.length()} commuters")

            var sentCount = 0
            var failedCount = 0

            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val displayName = user.optString("display_name", "User")

                    if (phoneNumber.isNullOrEmpty()) continue

                    val (title, message, type, data) = createNotificationContent(
                        eventType = eventType,
                        plateNumber = plateNumber,
                        jeepName = jeepName,
                        driverName = driverName,
                        terminalName = terminalName,
                        userName = displayName
                    )

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("terminal_id", terminalId)
                        addProperty("bracket", bracket)
                    }

                    supabase.insertNotification(
                        userId = userId,
                        title = title,
                        message = message,
                        type = type,
                        data = jsonData
                    ) { success ->
                        Log.d(TAG, "Notification saved for $displayName: $success")
                    }

                    sendSms(phoneNumber, message) { success, error ->
                        if (success) {
                            sentCount++
                            Log.d(TAG, "✅ Notified $displayName ($phoneNumber)")
                        } else {
                            failedCount++
                            Log.e(TAG, "❌ Failed to notify $displayName: $error")
                        }

                        if (sentCount + failedCount >= users.length()) {
                            onComplete(sentCount, failedCount)
                        }
                    }

                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "❌ Error: ${e.message}")
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
                Log.w(TAG, "⚠️ No dispatchers found")
                onComplete(0, 0)
                return@getDispatchers
            }

            Log.d(TAG, "📢 Notifying ${users.length()} dispatchers")

            var sentCount = 0
            var failedCount = 0

            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val displayName = user.optString("display_name", "Dispatcher")

                    if (phoneNumber.isNullOrEmpty()) continue

                    val (title, message, type, data) = createNotificationContent(
                        eventType = eventType,
                        plateNumber = plateNumber,
                        jeepName = jeepName,
                        driverName = driverName,
                        terminalName = terminalName,
                        userName = displayName
                    )

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("terminal_id", 0)
                    }

                    supabase.insertNotification(
                        userId = userId,
                        title = title,
                        message = message,
                        type = type,
                        data = jsonData
                    ) { success ->
                        Log.d(TAG, "Dispatcher notification saved: $success")
                    }

                    sendSms(phoneNumber, message) { success, error ->
                        if (success) sentCount++ else failedCount++
                        if (sentCount + failedCount >= users.length()) {
                            onComplete(sentCount, failedCount)
                        }
                    }

                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "❌ Error: ${e.message}")
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
                Log.w(TAG, "⚠️ No drivers found")
                onComplete(0, 0)
                return@getDrivers
            }

            Log.d(TAG, "📢 Notifying ${users.length()} drivers")

            var sentCount = 0
            var failedCount = 0

            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val displayName = user.optString("display_name", "Driver")

                    if (phoneNumber.isNullOrEmpty()) continue

                    val (title, message, type, data) = createNotificationContent(
                        eventType = eventType,
                        plateNumber = plateNumber,
                        jeepName = jeepName,
                        driverName = driverName,
                        terminalName = terminalName,
                        userName = displayName
                    )

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("terminal_id", 0)
                    }

                    supabase.insertNotification(
                        userId = userId,
                        title = title,
                        message = message,
                        type = type,
                        data = jsonData
                    )

                    sendSms(phoneNumber, message) { success, error ->
                        if (success) sentCount++ else failedCount++
                        if (sentCount + failedCount >= users.length()) {
                            onComplete(sentCount, failedCount)
                        }
                    }

                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "❌ Error: ${e.message}")
                }
            }
        }
    }

    // ─── CREATE NOTIFICATION CONTENT ──────────────────────────────
    private fun createNotificationContent(
        eventType: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        userName: String
    ): Quadruple<String, String, String, JsonObject> {
        val displayName = if (jeepName.isNotEmpty()) {
            "$jeepName ($plateNumber)"
        } else {
            plateNumber
        }

        val destination = if (terminalName.contains("Donsol")) {
            "Daraga Terminal"
        } else {
            "Donsol Terminal"
        }

        return when (eventType) {
            "arrival" -> Quadruple(
                "🚌 Jeepney Arrived",
                """
Hello $userName!

Jeepney $displayName has arrived at $terminalName.
Driver: $driverName
Queue position assigned.
You will be notified when loading starts.
""".trimIndent(),
                "arrival",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )

            "loading" -> Quadruple(
                "⏳ Loading Started",
                """
Hello $userName!

Jeepney $displayName is now loading at $terminalName.
Driver: $driverName
Loading Time: 30 minutes
Estimated departure: ${getEstimatedDepartureTime()}
Please be ready!
""".trimIndent(),
                "eta",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("loading_minutes", 30)
                }
            )

            "loading_complete" -> Quadruple(
                "✅ Loading Complete",
                """
Hello $userName!

Jeepney $displayName has finished loading at $terminalName.
Driver: $driverName
Status: Ready to depart

🚀 Departing soon!

- JeepQ Track System
""".trimIndent(),
                "status",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )

            "departure" -> Quadruple(
                "🚀 Jeepney Departing",
                """
Hello $userName!

Jeepney $displayName is departing from $terminalName.
Driver: $driverName
Destination: $destination
Status: En Route
Track live: ${if (terminalName.contains("Donsol")) "Donsol → Daraga" else "Daraga → Donsol"}
""".trimIndent(),
                "dispatch",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("destination", destination)
                }
            )

            "arrived_at_terminal" -> Quadruple(
                "📍 Arrived at Terminal",
                """
Hello $userName!

Jeepney $displayName has arrived at $terminalName.
Driver: $driverName
Passengers can now alight.
Thank you for using JeepQ Track!
""".trimIndent(),
                "arrival",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )

            "occupancy_alert" -> Quadruple(
                "⚠️ Occupancy Alert",
                """
Hello $userName!

Jeepney $displayName is reaching capacity.
Driver: $driverName
Please dispatch additional jeepneys if needed.
""".trimIndent(),
                "occupancy",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("occupancy", driverName)
                }
            )

            "queue_update" -> Quadruple(
                "🔄 Queue Update",
                """
Hello $userName!

Jeepney $displayName queue position has been updated.
Terminal: $terminalName
You are now closer to your turn.
""".trimIndent(),
                "queue",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("terminal", terminalName)
                }
            )

            "eta_update" -> Quadruple(
                "⏰ ETA Update",
                """
Hello $userName!

Jeepney $displayName ETA update.
Driver: $driverName
Current location near $terminalName
Estimated arrival in 15 minutes.
""".trimIndent(),
                "eta",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("eta_minutes", 15)
                }
            )

            else -> Quadruple(
                "📢 Jeepney Update",
                """
Hello $userName!

Jeepney $displayName update: $eventType
Terminal: $terminalName

- JeepQ Track System
""".trimIndent(),
                "status",
                JsonObject().apply {
                    addProperty("jeepney_id", "")
                    addProperty("plate_number", plateNumber)
                    addProperty("event_type", eventType)
                }
            )
        }
    }

    private fun getEstimatedDepartureTime(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 30)
        return SimpleDateFormat("hh:mm a", Locale.US).format(calendar.time)
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("hh:mm a", Locale.US).format(Date())
    }

    // ─── SPECIFIC NOTIFICATION METHODS ──────────────────────────────

    fun notifyArrival(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int
    ) {
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "arrival"
        ) { sent, failed ->
            Log.d(TAG, "Arrival: $sent sent, $failed failed")
        }

        notifyDispatchers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "arrival"
        ) { sent, failed ->
            Log.d(TAG, "Dispatcher arrival: $sent sent, $failed failed")
        }
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
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "loading"
        ) { sent, failed ->
            Log.d(TAG, "Loading: $sent sent, $failed failed")
        }

        notifyDispatchers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "loading"
        ) { sent, failed ->
            Log.d(TAG, "Dispatcher loading: $sent sent, $failed failed")
        }
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
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "loading_complete"
        ) { sent, failed ->
            Log.d(TAG, "Loading complete: $sent sent, $failed failed")
        }

        notifyDispatchers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "loading_complete"
        ) { sent, failed ->
            Log.d(TAG, "Dispatcher loading complete: $sent sent, $failed failed")
        }
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
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "departure"
        ) { sent, failed ->
            Log.d(TAG, "Departure: $sent sent, $failed failed")
        }

        notifyDispatchers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "departure"
        ) { sent, failed ->
            Log.d(TAG, "Dispatcher departure: $sent sent, $failed failed")
        }

        notifyDrivers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "departure"
        ) { sent, failed ->
            Log.d(TAG, "Driver departure: $sent sent, $failed failed")
        }
    }

    fun notifyTerminalArrival(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        terminalId: Int,
        bracket: Int
    ) {
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "arrived_at_terminal"
        ) { sent, failed ->
            Log.d(TAG, "Terminal arrival: $sent sent, $failed failed")
        }

        notifyDispatchers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "arrived_at_terminal"
        ) { sent, failed ->
            Log.d(TAG, "Dispatcher terminal arrival: $sent sent, $failed failed")
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

Please dispatch additional jeepneys if needed.

- JeepQ Track System
        """.trimIndent()

        Log.d(TAG, "📱 Sending occupancy alert to $phoneNumber")
        sendSms(phoneNumber, message)
    }

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

        // Only alert if occupancy > 80%
        if (percent < 80) return

        val message = """
⚠️ OCCUPANCY ALERT

Jeepney: $jeepName ($plateNumber)
Driver: $driverName
Passengers: $occupancy / $capacity ($percent%)
Terminal: $terminalName

Please dispatch additional jeepneys.
        """.trimIndent()

        // Send to dispatchers only
        supabase.getDispatchers { users ->
            if (users == null) return@getDispatchers

            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val displayName = user.optString("display_name", "Dispatcher")

                    if (phoneNumber.isNullOrEmpty()) continue

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("occupancy", occupancy)
                        addProperty("capacity", capacity)
                        addProperty("percent", percent)
                    }

                    supabase.insertNotification(
                        userId = userId,
                        title = "⚠️ Occupancy Alert",
                        message = message,
                        type = "occupancy",
                        data = jsonData
                    )

                    sendSms(phoneNumber, message)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error: ${e.message}")
                }
            }
        }
    }

    fun notifyQueueUpdate(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        newPosition: Int,
        terminalId: Int,
        bracket: Int
    ) {
        val message = """
🔄 QUEUE UPDATE

Jeepney: $jeepName ($plateNumber)
Driver: $driverName
Terminal: $terminalName
New Queue Position: #$newPosition

You are now closer to your turn!
        """.trimIndent()

        // Send to the driver of this jeepney
        supabase.getDrivers { users ->
            if (users == null) return@getDrivers

            for (i in 0 until users.length()) {
                try {
                    val user = users.getJSONObject(i)
                    val jeepneyIdFromUser = user.optString("jeepney_id")

                    // Only notify the driver of this specific jeepney
                    if (jeepneyIdFromUser != jeepneyId) continue

                    val userId = user.optString("id")
                    val phoneNumber = user.optString("phone_number")
                    val displayName = user.optString("display_name", "Driver")

                    if (phoneNumber.isNullOrEmpty()) continue

                    val jsonData = JsonObject().apply {
                        addProperty("jeepney_id", jeepneyId)
                        addProperty("plate_number", plateNumber)
                        addProperty("new_position", newPosition)
                        addProperty("terminal_id", terminalId)
                    }

                    supabase.insertNotification(
                        userId = userId,
                        title = "🔄 Queue Update",
                        message = message,
                        type = "queue",
                        data = jsonData
                    )

                    sendSms(phoneNumber, message)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error: ${e.message}")
                }
            }
        }
    }

    fun notifyETAUpdate(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        etaMinutes: Int,
        terminalId: Int,
        bracket: Int
    ) {
        notifyAllUsers(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "eta_update"
        ) { sent, failed ->
            Log.d(TAG, "ETA update: $sent sent, $failed failed")
        }
    }

    // ─── SINGLE USER NOTIFICATIONS ──────────────────────────────────

    fun notifySingleUser(
        userId: String,
        phoneNumber: String,
        displayName: String,
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String
    ) {
        val (title, message, type, data) = createNotificationContent(
            eventType = eventType,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            userName = displayName
        )

        supabase.insertNotification(
            userId = userId,
            title = title,
            message = message,
            type = type,
            data = data
        )

        sendSms(phoneNumber, message) { success, _ ->
            Log.d(TAG, "Single notification to $displayName: $success")
        }
    }
}

// ─── HELPER CLASS FOR QUADRUPLE ────────────────────────────────────
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
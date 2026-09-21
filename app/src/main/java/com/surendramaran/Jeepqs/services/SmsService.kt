package com.surendramaran.Jeepqs.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.surendramaran.Jeepqs.BuildConfig
import com.surendramaran.Jeepqs.settings.DeviceConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SMSService(
    private val context: Context,
    private val supabase: SupabaseService
) {

    companion object {
        private const val TAG = "SMSService"

        private const val HTTPSMS_API_URL =
            "https://api.httpsms.com/v1/messages/send"

        private const val HTTPSMS_API_KEY_1 =
            BuildConfig.HTTPSMS_API_KEY_1

        private const val HTTPSMS_API_KEY_2 =
            BuildConfig.HTTPSMS_API_KEY_2

        private const val PUSH_API_URL =
            "https://exp.host/--/api/v2/push/send"

        private const val CONNECTION_TIMEOUT = 15L
        private const val WRITE_TIMEOUT = 15L
        private const val READ_TIMEOUT = 30L

        private const val SMS_MIN_INTERVAL_MS =
            3 * 60 * 1000L

        private const val SMS_DAILY_CAP = 300

        private val lastSentAt =
            ConcurrentHashMap<String, Long>()

        private var dailyCount = 0
        private var dailyCountDate = ""

        // ─── SMS EVENT ALLOWLIST ──────────────────────────────────────
        // Only these event types ever go out as SMS, for BOTH staff and
        // commuters. Every event type still gets a push notification and
        // an in-app (Supabase) notification row regardless of this list —
        // this only gates the actual sendSms() call.
        //
        //   arrival        -> jeepney arrived at terminal
        //   loading_alert  -> the 25-min-into-loading alert
        //   departure      -> jeepney has departed
        //
        // Explicitly EXCLUDED from SMS (push/in-app only):
        //   loading            (loading just started / ~30 min estimate)
        //   loading_complete
        //   arrived_at_terminal
        //   occupancy          ("almost full" alert)
        //   system / test / anything else
        private val SMS_EVENT_TYPES = setOf(
            "arrival",
            "loading_alert",
            "departure"
        )

        private fun isSmsEligibleEvent(eventType: String): Boolean {
            return SMS_EVENT_TYPES.contains(eventType)
        }
    }

    private val JSON =
        "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(
                CONNECTION_TIMEOUT,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                WRITE_TIMEOUT,
                TimeUnit.SECONDS
            )
            .readTimeout(
                READ_TIMEOUT,
                TimeUnit.SECONDS
            )
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat(
            "hh:mm a",
            Locale.US
        ).format(Date())
    }

    private fun getEstimatedDepartureTime(
        minutes: Int = 30
    ): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, minutes)

        return SimpleDateFormat(
            "hh:mm a",
            Locale.US
        ).format(calendar.time)
    }

    // Normalizes Philippine mobile numbers to +63 format.
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }

        return when {
            digits.startsWith("63") &&
                    digits.length == 12 -> "+$digits"

            digits.startsWith("0") &&
                    digits.length == 11 ->
                "+63${digits.substring(1)}"

            digits.length == 10 ->
                "+63$digits"

            digits.isEmpty() -> ""

            raw.startsWith("+") ->
                "+$digits"

            else ->
                digits
        }
    }

    // Checks the global SMS daily limit and commuter throttle.
    private fun allowedToSendSms(
        phoneNumber: String,
        isStaffNotification: Boolean
    ): Boolean {

        val today = SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        ).format(Date())

        synchronized(this) {
            if (dailyCountDate != today) {
                dailyCountDate = today
                dailyCount = 0
            }

            if (dailyCount >= SMS_DAILY_CAP) {
                Log.w(
                    TAG,
                    "🚫 Daily SMS cap reached. " +
                            "Skipping $phoneNumber"
                )
                return false
            }
        }

        // Staff are not subject to the recipient throttle.
        if (isStaffNotification) {
            synchronized(this) {
                dailyCount++
            }

            return true
        }

        val key = normalizePhone(phoneNumber)

        if (key.isEmpty()) {
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastSentAt[key]

        if (
            last != null &&
            now - last < SMS_MIN_INTERVAL_MS
        ) {
            Log.w(
                TAG,
                "⏱️ SMS throttled for $phoneNumber"
            )
            return false
        }

        lastSentAt[key] = now

        synchronized(this) {
            dailyCount++
        }

        return true
    }

    fun sendSms(
        to: String,
        content: String,
        isStaffNotification: Boolean = false,
        callback: (Boolean, String?) -> Unit = { _, _ -> }
    ) {

        if (to.isBlank()) {
            Log.w(
                TAG,
                "❌ SMS skipped: empty recipient"
            )

            callback(
                false,
                "empty_recipient"
            )

            return
        }

        val configuredSlot =
            DeviceConfig.getConfiguredSlot(context)

        val fromNumber =
            DeviceConfig.httpSmsFromNumberForSlot(
                context,
                configuredSlot
            )

        val normalizedFrom =
            normalizePhone(fromNumber)

        val normalizedTo =
            normalizePhone(to)

        // Never send an SMS from a device to itself.
        if (
            normalizedFrom.isNotEmpty() &&
            normalizedTo.isNotEmpty() &&
            normalizedFrom == normalizedTo
        ) {
            Log.w(
                TAG,
                "🚫 SELF-SEND BLOCKED: " +
                        "Device ${configuredSlot + 1} " +
                        "($normalizedFrom) -> $normalizedTo"
            )

            callback(
                false,
                "self_send_blocked"
            )

            return
        }

        if (
            !allowedToSendSms(
                phoneNumber = to,
                isStaffNotification = isStaffNotification
            )
        ) {
            callback(
                false,
                if (isStaffNotification) {
                    "daily_cap"
                } else {
                    "rate_limited"
                }
            )

            return
        }

        Log.d(
            TAG,
            "📤 SMS -> $normalizedTo " +
                    "using configured Device ${configuredSlot + 1} " +
                    "from $normalizedFrom" +
                    if (isStaffNotification) {
                        " [STAFF]"
                    } else {
                        " [COMMUTER]"
                    }
        )

        attemptHttpSmsSend(
            to = normalizedTo,
            content = content,
            deviceSlot = configuredSlot,
            callback = callback
        )
    }

    private fun attemptHttpSmsSend(
        to: String,
        content: String,
        deviceSlot: Int,
        callback: (Boolean, String?) -> Unit
    ) {

        val apiKey =
            if (deviceSlot == 1) {
                HTTPSMS_API_KEY_2
            } else {
                HTTPSMS_API_KEY_1
            }

        val fromNumber =
            DeviceConfig.httpSmsFromNumberForSlot(
                context,
                deviceSlot
            )

        if (apiKey.isBlank()) {
            Log.e(
                TAG,
                "❌ Device ${deviceSlot + 1} " +
                        "has no httpSMS API key"
            )

            callback(
                false,
                "missing_api_key"
            )

            return
        }

        if (fromNumber.isBlank()) {
            Log.e(
                TAG,
                "❌ Device ${deviceSlot + 1} " +
                        "has no configured sender number"
            )

            callback(
                false,
                "missing_sender_number"
            )

            return
        }

        val normalizedFrom =
            normalizePhone(fromNumber)

        val normalizedTo =
            normalizePhone(to)

        if (
            normalizedFrom.isNotEmpty() &&
            normalizedFrom == normalizedTo
        ) {
            Log.w(
                TAG,
                "🚫 SELF-SEND BLOCKED at HTTP layer: " +
                        "$normalizedFrom"
            )

            callback(
                false,
                "self_send_blocked"
            )

            return
        }

        try {
            val json = JsonObject().apply {
                addProperty(
                    "from",
                    fromNumber
                )

                addProperty(
                    "to",
                    normalizedTo
                )

                addProperty(
                    "content",
                    content
                )
            }

            val request =
                Request.Builder()
                    .url(HTTPSMS_API_URL)
                    .post(
                        json
                            .toString()
                            .toRequestBody(JSON)
                    )
                    .addHeader(
                        "x-api-key",
                        apiKey
                    )
                    .addHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .addHeader(
                        "Accept",
                        "application/json"
                    )
                    .build()

            client.newCall(request)
                .enqueue(object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {
                        Log.e(
                            TAG,
                            "❌ Device ${deviceSlot + 1} " +
                                    "SMS network failure: " +
                                    "${e.message}",
                            e
                        )

                        callback(
                            false,
                            e.message
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {
                        response.use {

                            val body =
                                response.body
                                    ?.string()

                            if (
                                response.isSuccessful
                            ) {
                                Log.d(
                                    TAG,
                                    "✅ SMS sent by Device " +
                                            "${deviceSlot + 1} " +
                                            "from=$fromNumber " +
                                            "to=$to"
                                )

                                callback(
                                    true,
                                    body
                                )
                            } else {
                                Log.e(
                                    TAG,
                                    "❌ httpSMS failed: " +
                                            "Device ${deviceSlot + 1}, " +
                                            "HTTP ${response.code}, " +
                                            "body=$body"
                                )

                                callback(
                                    false,
                                    body
                                )
                            }
                        }
                    }
                })

        } catch (e: Exception) {
            Log.e(
                TAG,
                "❌ SMS request exception: " +
                        "${e.message}",
                e
            )

            callback(
                false,
                e.message
            )
        }
    }

    fun sendPushNotification(
        expoToken: String,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap(),
        callback: (Boolean) -> Unit = {}
    ) {

        if (
            expoToken.isBlank() ||
            !expoToken.startsWith(
                "ExponentPushToken"
            )
        ) {
            callback(false)
            return
        }

        try {
            val json = JsonObject().apply {

                addProperty(
                    "to",
                    expoToken
                )

                addProperty(
                    "title",
                    title
                )

                addProperty(
                    "body",
                    message
                )

                addProperty(
                    "sound",
                    "default"
                )

                addProperty(
                    "priority",
                    "high"
                )

                addProperty(
                    "channelId",
                    "jeepq_default"
                )

                addProperty(
                    "ttl",
                    86400
                )

                add(
                    "data",
                    JsonObject().apply {
                        data.forEach { (key, value) ->
                            addProperty(
                                key,
                                value
                            )
                        }
                    }
                )
            }

            val request =
                Request.Builder()
                    .url(PUSH_API_URL)
                    .post(
                        json
                            .toString()
                            .toRequestBody(JSON)
                    )
                    .addHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .addHeader(
                        "Accept",
                        "application/json"
                    )
                    .build()

            client.newCall(request)
                .enqueue(object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {
                        Log.e(
                            TAG,
                            "❌ Push failed: " +
                                    "${e.message}"
                        )

                        callback(false)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {
                        response.use {
                            callback(
                                response.isSuccessful
                            )
                        }
                    }
                })

        } catch (e: Exception) {
            Log.e(
                TAG,
                "❌ Push exception: " +
                        "${e.message}"
            )

            callback(false)
        }
    }

    private fun createNotificationContent(
        eventType: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        userName: String
    ): Triple<String, String, JsonObject> {

        val displayName =
            if (jeepName.isNotEmpty()) {
                "$jeepName ($plateNumber)"
            } else {
                plateNumber
            }

        val destination =
            if (
                terminalName.contains(
                    "Donsol",
                    ignoreCase = true
                )
            ) {
                "Daraga Terminal"
            } else {
                "Donsol Terminal"
            }

        return when (eventType) {

            "arrival" -> Triple(
                "Jeepney Arrived",
                """
                Hello $userName!
                Jeepney $displayName has arrived at $terminalName.
                Driver: $driverName
                Queue position assigned.
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "terminal",
                        terminalName
                    )
                }
            )

            "loading" -> Triple(
                "Loading Started",
                """
                Hello $userName!
                Jeepney $displayName is now loading at $terminalName.
                Driver: $driverName
                Estimated departure: ${getEstimatedDepartureTime()}
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "loading_minutes",
                        30
                    )
                }
            )

            "loading_complete" -> Triple(
                "Loading Complete",
                """
                Hello $userName!
                Jeepney $displayName has finished loading at $terminalName.
                Driver: $driverName
                Departing soon!
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "terminal",
                        terminalName
                    )
                }
            )

            "loading_alert" -> Triple(
                "Loading Alert",
                """
                Hello $userName!
                Jeepney $displayName is still loading at $terminalName.
                Driver: $driverName
                Auto-departure in 5 minutes.
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "loading_minutes",
                        25
                    )
                }
            )

            "departure" -> Triple(
                "Jeepney Departing",
                """
                Hello $userName!
                Jeepney $displayName is departing from $terminalName.
                Driver: $driverName
                Destination: $destination
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "destination",
                        destination
                    )
                }
            )

            "arrived_at_terminal" -> Triple(
                "Arrived at Terminal",
                """
                Hello $userName!
                Jeepney $displayName has arrived at $terminalName.
                Driver: $driverName
                Passengers can now alight.
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "terminal",
                        terminalName
                    )
                }
            )

            else -> Triple(
                "Jeepney Update",
                """
                Hello $userName!
                Jeepney $displayName update: $eventType
                Terminal: $terminalName
                """.trimIndent(),
                JsonObject().apply {
                    addProperty(
                        "plate_number",
                        plateNumber
                    )
                    addProperty(
                        "event_type",
                        eventType
                    )
                }
            )
        }
    }

    /**
     * Notifies commuters subscribed to a terminal. Push + in-app notification
     * rows are ALWAYS created for every event type. SMS is only sent when
     * BOTH of these are true:
     *   1. eventType is in SMS_EVENT_TYPES (arrival / loading_alert / departure)
     *   2. the individual commuter has notifications_enabled = true, i.e. they
     *      tapped "Notify me" for SMS on the frontend.
     * A commuter who has NOT opted in still gets push notifications for every
     * event — they just never receive SMS.
     */
    private fun notifyCommuters(
        fetchUsers: (
            callback: (JSONArray?) -> Unit
        ) -> Unit,
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

            if (
                users == null ||
                users.length() == 0
            ) {
                onComplete(0, 0)
                return@fetchUsers
            }

            var completed = 0
            var sent = 0
            var failed = 0

            val total =
                users.length()

            for (
            i in 0 until total
            ) {
                try {

                    val user =
                        users.getJSONObject(i)

                    val userId =
                        user.optString("id")

                    val phoneNumber =
                        user
                            .optString(
                                "phone_number"
                            )
                            .trim()

                    val expoToken =
                        user
                            .optString(
                                "expo_push_token",
                                ""
                            )
                            .trim()

                    val displayName =
                        user.optString(
                            "display_name",
                            "User"
                        )

                    // The commuter's own "Notify me" (SMS) toggle from the frontend.
                    val smsOptedIn =
                        user.optBoolean(
                            "notifications_enabled",
                            false
                        )

                    val (
                        title,
                        message,
                        jsonData
                    ) =
                        createNotificationContent(
                            eventType,
                            plateNumber,
                            jeepName,
                            driverName,
                            terminalName,
                            displayName
                        )

                    val data =
                        JsonObject().apply {

                            addProperty(
                                "jeepney_id",
                                jeepneyId
                            )

                            addProperty(
                                "plate_number",
                                plateNumber
                            )

                            addProperty(
                                "terminal_id",
                                terminalId
                            )

                            addProperty(
                                "bracket",
                                bracket
                            )

                            jsonData
                                .keySet()
                                .forEach { key ->
                                    add(
                                        key,
                                        jsonData.get(key)
                                    )
                                }
                        }

                    // In-app notification row: always created, every event type.
                    supabase.insertNotificationAdmin(
                        userId,
                        title,
                        message,
                        eventType,
                        data
                    )

                    // Push notification: always sent (if a token is on file),
                    // every event type — this is the default channel.
                    if (
                        expoToken.isNotEmpty()
                    ) {
                        sendPushNotification(
                            expoToken,
                            title,
                            message,
                            mapOf(
                                "jeepney_id"
                                        to jeepneyId,
                                "plate_number"
                                        to plateNumber,
                                "type"
                                        to eventType,
                                "terminal_id"
                                        to terminalId.toString()
                            )
                        )
                    }

                    // SMS: only for allowlisted events AND only if this
                    // commuter opted in via "Notify me".
                    if (
                        isSmsEligibleEvent(eventType) &&
                        smsOptedIn &&
                        phoneNumber.isNotEmpty()
                    ) {

                        sendSms(
                            to = phoneNumber,
                            content = message,
                            isStaffNotification = false
                        ) { success, _ ->

                            if (success) {
                                sent++
                            } else {
                                failed++
                            }

                            completed++

                            if (
                                completed >= total
                            ) {
                                onComplete(
                                    sent,
                                    failed
                                )
                            }
                        }

                    } else {

                        completed++

                        if (
                            completed >= total
                        ) {
                            onComplete(
                                sent,
                                failed
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Commuter notification failed",
                        e
                    )

                    failed++
                    completed++

                    if (
                        completed >= total
                    ) {
                        onComplete(
                            sent,
                            failed
                        )
                    }
                }
            }
        }
    }

    /**
     * Notifies all active staff (driver/dispatcher/admin). Push + in-app
     * notification rows are ALWAYS created for every event type. SMS is only
     * sent when eventType is in SMS_EVENT_TYPES (arrival / loading_alert /
     * departure) — staff have no separate opt-in toggle, so the event-type
     * allowlist is the only gate.
     */
    private fun notifyAllStaff(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        eventType: String,
        extraData: JsonObject? = null,
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {

        supabase.getActiveStaff { users ->

            if (users == null) {
                Log.e(
                    TAG,
                    "❌ getAllStaff returned null"
                )

                onComplete(0, 0)
                return@getActiveStaff
            }

            if (users.length() == 0) {
                Log.w(
                    TAG,
                    "⚠️ No active staff found"
                )

                onComplete(0, 0)
                return@getActiveStaff
            }

            var completed = 0
            var sent = 0
            var failed = 0

            val total =
                users.length()

            Log.d(
                TAG,
                "👥 Staff broadcast: " +
                        "$eventType -> $total recipients" +
                        if (isSmsEligibleEvent(eventType)) {
                            " [push+sms]"
                        } else {
                            " [push only]"
                        }
            )

            fun complete(
                success: Boolean
            ) {
                synchronized(this) {

                    if (success) {
                        sent++
                    } else {
                        failed++
                    }

                    completed++

                    if (
                        completed >= total
                    ) {
                        Log.d(
                            TAG,
                            "👥 Staff broadcast complete: " +
                                    "sent=$sent failed=$failed"
                        )

                        onComplete(
                            sent,
                            failed
                        )
                    }
                }
            }

            for (
            i in 0 until total
            ) {

                try {

                    val user =
                        users.getJSONObject(i)

                    val userId =
                        user.optString("id")

                    val phoneNumber =
                        user
                            .optString(
                                "phone_number"
                            )
                            .trim()

                    val expoToken =
                        user
                            .optString(
                                "expo_push_token",
                                ""
                            )
                            .trim()

                    val displayName =
                        user.optString(
                            "display_name",
                            "Staff"
                        )

                    val role =
                        user.optString(
                            "role",
                            "staff"
                        )

                    val (
                        title,
                        message,
                        jsonData
                    ) =
                        createNotificationContent(
                            eventType,
                            plateNumber,
                            jeepName,
                            driverName,
                            terminalName,
                            displayName
                        )

                    val data =
                        JsonObject().apply {

                            addProperty(
                                "jeepney_id",
                                jeepneyId
                            )

                            addProperty(
                                "plate_number",
                                plateNumber
                            )

                            addProperty(
                                "event_type",
                                eventType
                            )

                            addProperty(
                                "staff_role",
                                role
                            )

                            jsonData
                                .keySet()
                                .forEach { key ->
                                    add(
                                        key,
                                        jsonData.get(key)
                                    )
                                }

                            extraData
                                ?.keySet()
                                ?.forEach { key ->
                                    add(
                                        key,
                                        extraData.get(key)
                                    )
                                }
                        }

                    // In-app notification row: always created, every event type.
                    if (
                        userId.isNotEmpty()
                    ) {
                        supabase.insertNotificationAdmin(
                            userId,
                            title,
                            message,
                            eventType,
                            data
                        )
                    }

                    // Push notification: always sent (if a token is on file),
                    // every event type.
                    if (
                        expoToken.isNotEmpty()
                    ) {
                        sendPushNotification(
                            expoToken,
                            title,
                            message,
                            mapOf(
                                "jeepney_id"
                                        to jeepneyId,
                                "plate_number"
                                        to plateNumber,
                                "type"
                                        to eventType,
                                "role"
                                        to role
                            )
                        )
                    }

                    // SMS: only for allowlisted events. Non-allowlisted events
                    // (loading, loading_complete, occupancy, arrived_at_terminal,
                    // etc.) skip SMS entirely — push/in-app above already covered
                    // this recipient.
                    if (!isSmsEligibleEvent(eventType)) {
                        complete(true)
                        continue
                    }

                    if (
                        phoneNumber.isEmpty()
                    ) {
                        Log.w(
                            TAG,
                            "⚠️ Staff $displayName " +
                                    "[$role] has no phone"
                        )

                        complete(false)
                        continue
                    }

                    sendSms(
                        to = phoneNumber,
                        content = message,
                        isStaffNotification = true
                    ) { success, reason ->

                        if (success) {
                            Log.d(
                                TAG,
                                "✅ Staff SMS: " +
                                        "$displayName [$role]"
                            )
                        } else {
                            Log.e(
                                TAG,
                                "❌ Staff SMS: " +
                                        "$displayName [$role] " +
                                        "reason=$reason"
                            )
                        }

                        complete(success)
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Staff recipient failed",
                        e
                    )

                    complete(false)
                }
            }
        }
    }

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
            fetchUsers = {
                    callback ->
                supabase.getCommuters(
                    terminalId,
                    callback
                )
            },
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "arrival"
        )

        notifyAllStaff(
            jeepneyId,
            plateNumber,
            jeepName,
            driverName,
            terminalName,
            "arrival"
        )
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
            fetchUsers = {
                    callback ->
                supabase.getCommuters(
                    terminalId,
                    callback
                )
            },
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "loading"
        )

        notifyAllStaff(
            jeepneyId,
            plateNumber,
            jeepName,
            driverName,
            terminalName,
            "loading"
        )
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
            fetchUsers = {
                    callback ->
                supabase.getCommuters(
                    terminalId,
                    callback
                )
            },
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "loading_complete"
        )

        notifyAllStaff(
            jeepneyId,
            plateNumber,
            jeepName,
            driverName,
            terminalName,
            "loading_complete",
            JsonObject().apply {
                addProperty(
                    "occupancy",
                    occupancy
                )
            }
        )
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
            fetchUsers = {
                    callback ->
                supabase.getCommuters(
                    terminalId,
                    callback
                )
            },
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "departure"
        )

        notifyAllStaff(
            jeepneyId,
            plateNumber,
            jeepName,
            driverName,
            terminalName,
            "departure"
        )
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
            fetchUsers = {
                    callback ->
                supabase.getCommuters(
                    terminalId,
                    callback
                )
            },
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            terminalId = terminalId,
            bracket = bracket,
            eventType = "loading_alert"
        )

        notifyAllStaff(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "loading_alert",
            extraData = JsonObject().apply {
                addProperty(
                    "loading_minutes",
                    minutes
                )

                addProperty(
                    "auto_departure_minutes",
                    30 - minutes
                )
            }
        )
    }

    /**
     * Staff-only "almost full" occupancy alert. Push + in-app only — occupancy
     * is not in SMS_EVENT_TYPES, so notifyAllStaff will skip SMS for this call
     * automatically.
     */
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

        val percent =
            if (capacity > 0) {
                (occupancy * 100) / capacity
            } else {
                0
            }

        if (percent < 80) {
            return
        }

        notifyAllStaff(
            jeepneyId = jeepneyId,
            plateNumber = plateNumber,
            jeepName = jeepName,
            driverName = driverName,
            terminalName = terminalName,
            eventType = "occupancy",
            extraData = JsonObject().apply {
                addProperty(
                    "occupancy",
                    occupancy
                )

                addProperty(
                    "capacity",
                    capacity
                )

                addProperty(
                    "percent",
                    percent
                )
            }
        )
    }

    /**
     * Direct operational dispatch alert to a specific staff phone number
     * (e.g. "please dispatch additional jeepneys"). This is NOT part of the
     * commuter/staff broadcast pipeline above and is not gated by
     * SMS_EVENT_TYPES — it's an explicit, single-recipient staff SMS, same
     * as before.
     */
    fun sendOccupancyAlert(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        occupancy: Int,
        capacity: Int,
        phoneNumber: String
    ) {

        val percent =
            if (capacity > 0) {
                (occupancy * 100) / capacity
            } else {
                0
            }

        val message =
            """
            ⚠️ OCCUPANCY ALERT
            Jeepney: $jeepName ($plateNumber)
            Driver: $driverName
            Passengers: $occupancy / $capacity ($percent%)
            Status: Reaching Capacity
            Please dispatch additional jeepneys.
            - JeepQ Track System
            """.trimIndent()

        sendSms(
            phoneNumber,
            message,
            isStaffNotification = true
        )
    }

    // Explicit admin-triggered test — always sends, not gated by event type.
    fun testNotification(
        userId: String,
        phoneNumber: String,
        expoToken: String = ""
    ) {

        val title =
            "🧪 Test Notification"

        val message =
            "This is a test notification from JeepQ Track! 🚀"

        val jsonData =
            JsonObject().apply {
                addProperty(
                    "test",
                    true
                )

                addProperty(
                    "timestamp",
                    System.currentTimeMillis()
                )
            }

        supabase.insertNotificationAdmin(
            userId,
            title,
            message,
            "system",
            jsonData
        )

        sendSms(
            phoneNumber,
            message,
            isStaffNotification = true
        )

        if (expoToken.isNotEmpty()) {
            sendPushNotification(
                expoToken,
                title,
                message,
                mapOf(
                    "type" to "test",
                    "timestamp" to
                            System.currentTimeMillis()
                                .toString()
                )
            )
        }
    }

    /**
     * Single-recipient notification (push + in-app always; SMS only for
     * allowlisted event types AND only if the caller says this recipient
     * is opted in for SMS — defaults to false, i.e. push-only, unless the
     * caller explicitly passes true after checking notifications_enabled).
     */
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
        eventType: String,
        smsOptedIn: Boolean = false
    ) {

        val (
            title,
            message,
            jsonData
        ) =
            createNotificationContent(
                eventType,
                plateNumber,
                jeepName,
                driverName,
                terminalName,
                displayName
            )

        val data =
            JsonObject().apply {

                addProperty(
                    "jeepney_id",
                    jeepneyId
                )

                addProperty(
                    "plate_number",
                    plateNumber
                )

                jsonData
                    .keySet()
                    .forEach { key ->
                        add(
                            key,
                            jsonData.get(key)
                        )
                    }
            }

        supabase.insertNotificationAdmin(
            userId,
            title,
            message,
            eventType,
            data
        )

        if (expoToken.isNotEmpty()) {
            sendPushNotification(
                expoToken,
                title,
                message,
                mapOf(
                    "jeepney_id"
                            to jeepneyId,
                    "plate_number"
                            to plateNumber,
                    "type"
                            to eventType
                )
            )
        }

        if (
            isSmsEligibleEvent(eventType) &&
            smsOptedIn &&
            phoneNumber.isNotBlank()
        ) {
            sendSms(
                phoneNumber,
                message
            )
        }
    }

    /**
     * @deprecated Superseded by notifyArrival(), which broadcasts to all
     * subscribed commuters + staff via notifyCommuters()/notifyAllStaff()
     * (push always, SMS only for opted-in commuters / allowlisted events).
     * Kept for any legacy single-recipient callers; SMS here is still gated
     * by SMS_EVENT_TYPES for consistency, but there is no per-commuter
     * opt-in check at this call site — callers must confirm the recipient
     * opted in to SMS before calling this.
     */
    fun sendArrivalNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        queuePosition: Int,
        terminalName: String,
        phoneNumber: String
    ) {

        val displayName =
            if (jeepName.isNotEmpty()) {
                "$jeepName ($plateNumber)"
            } else {
                plateNumber
            }

        val message =
            """
            JEEPNEY ARRIVED
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Queue: #$queuePosition
            Time: ${getCurrentTime()}
            - JeepQ Track System
            """.trimIndent()

        if (!isSmsEligibleEvent("arrival")) {
            return
        }

        sendSms(
            phoneNumber,
            message
        )
    }

    /**
     * @deprecated Superseded by notifyLoadingStarted(). "loading" is NOT in
     * SMS_EVENT_TYPES (only the 25-min loading_alert, arrival, and departure
     * are SMS), so this is now a no-op for SMS by design — loading-started
     * should only reach commuters/staff via push, which this legacy function
     * does not send. Prefer notifyLoadingStarted() instead.
     */
    fun sendLoadingNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        duration: Long,
        phoneNumber: String
    ) {

        if (!isSmsEligibleEvent("loading")) {
            Log.d(
                TAG,
                "sendLoadingNotification: 'loading' is not SMS-eligible, skipping SMS " +
                        "(use notifyLoadingStarted() for push+in-app)"
            )
            return
        }

        val displayName =
            if (jeepName.isNotEmpty()) {
                "$jeepName ($plateNumber)"
            } else {
                plateNumber
            }

        val departTime =
            SimpleDateFormat(
                "hh:mm a",
                Locale.US
            ).format(
                Date(
                    System.currentTimeMillis() +
                            duration * 60 * 1000
                )
            )

        val message =
            """
            LOADING STARTED
            Jeepney: $displayName
            Driver: $driverName
            Terminal: $terminalName
            Loading: $duration min
            Departure: $departTime
            - JeepQ Track System
            """.trimIndent()

        sendSms(
            phoneNumber,
            message
        )
    }

    /**
     * @deprecated Superseded by notifyDeparture(). SMS here is still gated
     * by SMS_EVENT_TYPES for consistency, but there is no per-commuter
     * opt-in check at this call site — callers must confirm the recipient
     * opted in to SMS before calling this.
     */
    fun sendDepartureNotification(
        jeepneyId: String,
        plateNumber: String,
        jeepName: String,
        driverName: String,
        terminalName: String,
        phoneNumber: String
    ) {

        if (!isSmsEligibleEvent("departure")) {
            return
        }

        val displayName =
            if (jeepName.isNotEmpty()) {
                "$jeepName ($plateNumber)"
            } else {
                plateNumber
            }

        val destination =
            if (
                terminalName.contains(
                    "Donsol",
                    ignoreCase = true
                )
            ) {
                "Daraga Terminal"
            } else {
                "Donsol Terminal"
            }

        val message =
            """
            JEEPNEY DEPARTING
            Jeepney: $displayName
            Driver: $driverName
            From: $terminalName
            Destination: $destination
            Time: ${getCurrentTime()}
            - JeepQ Track System
            """.trimIndent()

        sendSms(
            phoneNumber,
            message
        )
    }
}
package com.surendramaran.Jeepqs.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.surendramaran.Jeepqs.settings.DeviceConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupabaseService(
    private val context: Context
) {

    companion object {
        private const val TAG = "SupabaseService"
        private const val SUPABASE_URL = "https://mfztenjrwtfjsgebmvda.supabase.co"

        private const val SUPABASE_KEY = "sb_publishable_goOnWdfw3tacBtKYBE7ZFA_JLhgM9vb"

        private val SERVICE_ROLE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1menRlbmpyd3RmanNnZWJtdmRhIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4MjcxOTgxMSwiZXhwIjoyMDk4Mjk1ODExfQ.Xs96smUK9DGSzDBrsehHaJKnYaSrDROJWMRnXVUM3jE"

        private const val CONNECTION_TIMEOUT = 30L
        private const val WRITE_TIMEOUT = 30L
        private const val READ_TIMEOUT = 30L
    }

    private val jeepId
        get() = DeviceConfig.jeepId(context)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val adminClient: OkHttpClient by lazy {
        client.newBuilder().build()
    }
    private fun utcDateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun timestamp(): String {
        return utcDateFormat().format(Date())
    }

    private fun executeRequest(request: Request, callback: (Boolean) -> Unit) {
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
    }

    private fun executeRequestWithBody(request: Request, callback: (String?) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string()
                    if (response.isSuccessful) {
                        callback(body)
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    private fun executeRpc(function: String, json: JsonObject, onSuccess: (String) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/rpc/$function")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ RPC '$function' network failure: ${e.message}", e)
                onSuccess("[]")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string() ?: "[]"
                    if (!response.isSuccessful) {
                        Log.e(TAG, "❌ RPC '$function' HTTP ${response.code}: $body")
                    } else {
                        Log.d(TAG, "✅ RPC '$function' HTTP ${response.code}: $body")
                    }
                    onSuccess(body)
                }
            }
        })
    }

    // ─── DOOR COUNTS ──────────────────────────────────────────────────

    fun upsertDoorCounts(front: Int, rear: Int, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(false)
            return
        }

        val checkRequest = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/door_counts?jeep_id=eq.$jeepId&select=id")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(checkRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string()
                    try {
                        val exists = !body.isNullOrEmpty() && JSONArray(body).length() > 0
                        val json = JsonObject().apply {
                            addProperty("front_count", front)
                            addProperty("rear_count", rear)
                            addProperty("updated_at", timestamp())
                        }
                        if (exists) {
                            patchDoorCounts(json, callback)
                        } else {
                            json.addProperty("jeep_id", jeepId)
                            insertDoorCounts(json, callback)
                        }
                    } catch (_: Exception) {
                        callback(false)
                    }
                }
            }
        })
    }

    private fun patchDoorCounts(json: JsonObject, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(false)
            return
        }
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/door_counts?jeep_id=eq.$jeepId")
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    private fun insertDoorCounts(json: JsonObject, callback: (Boolean) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/door_counts")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    // ─── JEEPNEY UPDATES ─────────────────────────────────────────────

    fun updateOccupancy(total: Int, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("current_occupancy", total)
            addProperty("last_occupancy_update", timestamp())
        }
        patchJeepneys(json, callback)
    }

    /**
     * ⚠️ Only safe for statuses that don't care about queue_position
     * ('arrived', 'inactive', etc). NEVER use this to move a jeepney OUT of
     * 'loading'/'waiting' into 'en_route' or 'dispatched' — those transitions
     * must clear queue_position in the same request or they'll violate
     * jeepneys_enroute_queue_pos_check and silently fail. Use departJeepney()
     * for that instead.
     */
    fun updateStatus(status: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("status", status)
            addProperty("updated_at", timestamp())
        }
        patchJeepneys(json, callback)
    }
    fun updateGps(latitude: Double, longitude: Double, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("latitude", latitude)
            addProperty("longitude", longitude)
            addProperty("current_latitude", latitude)
            addProperty("current_longitude", longitude)
            addProperty("last_location_update", timestamp())
            addProperty("updated_at", timestamp())
        }
        patchJeepneys(json, callback)
    }
    private fun patchJeepneys(json: JsonObject, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(false)
            return
        }
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId")
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    // ─── GPS TRACKING ─────────────────────────────────────────────────

    fun sendGpsTracking(
        latitude: Double,
        longitude: Double,
        speed: Double = 0.0,
        heading: Double = 0.0,
        callback: (Boolean) -> Unit
    ) {
        if (jeepId.isNullOrEmpty()) {
            callback(false)
            return
        }

        val json = JsonObject().apply {
            addProperty("jeepney_id", jeepId)
            addProperty("latitude", latitude)
            addProperty("longitude", longitude)
            addProperty("speed", speed)
            addProperty("heading", heading)
            addProperty("recorded_at", timestamp())
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/gps_tracking")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        executeRequest(request, callback)
    }

    // ─── JEEPNEY INFO ─────────────────────────────────────────────────

    data class JeepneyInfo(
        val id: String = "",
        val plateNumber: String,
        val jeepName: String,
        val bracket: Int,
        val capacity: Int,
        val driverName: String,
        val occupancy: Int,
        val queuePosition: Int? = null
    )

    data class JeepneyWithDriver(
        val plateNumber: String,
        val jeepName: String,
        val bracket: Int,
        val capacity: Int,
        val occupancy: Int,
        val driverName: String,
        val driverPhone: String,
        val status: String,
        val terminalId: Int,
        val loadingStartedAtMillis: Long?
    )

    fun getJeepInfo(callback: (JeepneyInfo?) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(null)
            return
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId&select=id,plate_number,jeep_name,bracket,capacity,current_occupancy,driver_name")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        val info = JeepneyInfo(
                            id = obj.optString("id", ""),
                            plateNumber = obj.optString("plate_number", "UNKNOWN"),
                            jeepName = obj.optString("jeep_name", ""),
                            bracket = obj.optInt("bracket", 1),
                            capacity = obj.optInt("capacity", 24),
                            driverName = obj.optString("driver_name", "Not Assigned"),
                            occupancy = obj.optInt("current_occupancy", 0)
                        )
                        callback(info)
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    /**
     * Resolves the phone number of the driver assigned to a given jeepney,
     * via users.jeepney_id + role='driver' (see the users table schema —
     * phone_number is nullable and unique among non-commuter roles). Returns
     * null if no driver row is linked, or that driver has no phone on file.
     */
    fun getDriverPhoneForJeepney(jeepneyId: String, callback: (String?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/users?jeepney_id=eq.$jeepneyId&role=eq.driver&select=phone_number")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val phone = array.getJSONObject(0).optString("phone_number", "")
                        callback(phone.takeIf { it.isNotEmpty() })
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getJeepneyWithDriver(callback: (JeepneyWithDriver?) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(null)
            return
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId&select=plate_number,jeep_name,bracket,capacity,current_occupancy,driver_name,status,terminal_id,loading_started_at")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        val loadingStartedAtStr = obj.optString("loading_started_at", "")
                        val loadingStartedAtMillis = if (loadingStartedAtStr.isNotEmpty()) {
                            try {
                                utcDateFormat().parse(loadingStartedAtStr)?.time
                            } catch (_: Exception) { null }
                        } else null

                        getDriverPhoneForJeepney(jeepId!!) { driverPhone ->
                            val info = JeepneyWithDriver(
                                plateNumber = obj.optString("plate_number", "UNKNOWN"),
                                jeepName = obj.optString("jeep_name", ""),
                                bracket = obj.optInt("bracket", 1),
                                capacity = obj.optInt("capacity", 24),
                                occupancy = obj.optInt("current_occupancy", 0),
                                driverName = obj.optString("driver_name", "Not Assigned"),
                                driverPhone = driverPhone ?: "",
                                status = obj.optString("status", "inactive"),
                                terminalId = obj.optInt("terminal_id", 1),
                                loadingStartedAtMillis = loadingStartedAtMillis
                            )
                            callback(info)
                        }
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // ─── QUEUE OPERATIONS ────────────────────────────────────────────

    fun addToQueueWithBracket(jeepneyId: String, bracket: Int, callback: (Int, String) -> Unit) {
        val json = JsonObject().apply {
            addProperty("jeep_id", jeepneyId)
            addProperty("bracket_num", bracket)
        }
        executeRpc("add_to_queue_with_bracket", json) { body ->
            try {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    callback(obj.optInt("queue_pos", 0), obj.optString("queue_status", "waiting"))
                } else {
                    callback(0, "inactive")
                }
            } catch (_: Exception) {
                callback(0, "inactive")
            }
        }
    }

    fun addToQueueWithBracketAndTerminal(
        jeepneyId: String,
        bracket: Int,
        terminalId: Int,
        callback: (Int, String) -> Unit
    ) {
        val json = JsonObject().apply {
            addProperty("jeep_id", jeepneyId)
            addProperty("bracket_num", bracket)
            addProperty("terminal_id", terminalId)
        }
        executeRpc("add_to_queue_with_bracket_and_terminal", json) { body ->
            try {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    callback(obj.optInt("queue_pos", 0), obj.optString("queue_status", "waiting"))
                } else {
                    Log.w(TAG, "add_to_queue_with_bracket_and_terminal returned empty array: $body")
                    callback(0, "inactive")
                }
            } catch (e: Exception) {
                Log.e(TAG, "add_to_queue_with_bracket_and_terminal parse failed, body=$body", e)
                callback(0, "inactive")
            }
        }
    }

    fun removeFromQueue(jeepneyId: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply { addProperty("jeep_id", jeepneyId) }
        executeRpc("remove_from_queue", json) { callback(true) }
    }

    fun skipWaitingJeepney(jeepneyId: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply { addProperty("jeep_id", jeepneyId) }
        executeRpc("skip_waiting_jeepney", json) { callback(true) }
    }

    fun getNextInQueue(terminalId: Int, bracket: Int, callback: (JeepneyInfo?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/rpc/get_next_in_queue?p_terminal_id=$terminalId&p_bracket=$bracket")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        callback(JeepneyInfo(
                            id = obj.optString("jeep_id", obj.optString("id", "")),
                            plateNumber = obj.optString("plate_number", ""),
                            jeepName = obj.optString("jeep_name", ""),
                            bracket = obj.optInt("bracket", 1),
                            capacity = obj.optInt("capacity", 24),
                            driverName = obj.optString("driver_name", ""),
                            occupancy = obj.optInt("current_occupancy", 0),
                            queuePosition = obj.optInt("queue_pos", obj.optInt("queue_position", 1))
                        ))
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // ─── USERS ────────────────────────────────────────────────────────

    fun getUsers(
        role: String? = null,
        terminalId: Int? = null,
        bracket: Int? = null,
        callback: (JSONArray?) -> Unit
    ) {
        var url = "$SUPABASE_URL/rest/v1/users?select=id,phone_number,display_name,role,preferred_terminal,preferred_bracket,expo_push_token&is_active=eq.true"

        role?.let { url += "&role=eq.$it" }
        terminalId?.let { url += "&preferred_terminal=eq.$it" }
        bracket?.let { url += "&preferred_bracket=eq.$it" }

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    callback(JSONArray(body))
                } else {
                    callback(null)
                }
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getCommuters(terminalId: Int, callback: (JSONArray?) -> Unit) {
        val json = JsonObject().apply {
            addProperty("p_terminal_id", terminalId)
        }
        executeRpc("get_active_terminal_commuters", json) { body ->
            try {
                callback(JSONArray(body))
            } catch (e: Exception) {
                Log.e(TAG, "getCommuters parse failed, body=$body", e)
                callback(null)
            }
        }
    }
    /**
     * Called when a commuter taps "Notify me" on a terminal. Upserts a
     * 3-hour subscription (re-tapping just refreshes the expiry) via the
     * subscribe_to_terminal RPC — see terminal_subscriptions_migration.sql.
     * This is the ONLY commuter subscription mechanism in the app; there is
     * no per-jeepney subscription table, so getCommuters(terminalId) below
     * is the single source of truth for all commuter notifications.
     */
    fun subscribeToTerminal(userId: String, terminalId: Int, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("p_user_id", userId)
            addProperty("p_terminal_id", terminalId)
        }
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/rpc/subscribe_to_terminal")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest(request, callback)
    }

    fun getDispatchers(callback: (JSONArray?) -> Unit) {
        getUsers(role = "dispatcher", callback = callback)
    }

    fun getDrivers(callback: (JSONArray?) -> Unit) {
        getUsers(role = "driver", callback = callback)
    }
    fun getAdmins(callback: (JSONArray?) -> Unit) {
        getUsers(role = "admin", callback = callback)
    }
    fun getUserById(userId: String, callback: (JSONObject?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/users?select=id,email,phone_number,display_name,role,expo_push_token&id=eq.$userId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        callback(array.getJSONObject(0))
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getUserByPhone(phoneNumber: String, callback: (JSONObject?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/users?phone_number=eq.$phoneNumber&select=id,display_name,role,preferred_terminal,preferred_bracket")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        callback(array.getJSONObject(0))
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getUserIdForJeepney(jeepneyId: String, callback: (String?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/users?select=id&jeepney_id=eq.$jeepneyId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        callback(obj.optString("id"))
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getDriverIdFromJeepney(jeepneyId: String, callback: (String?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?select=driver_id&id=eq.$jeepneyId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        val driverId = obj.optString("driver_id")
                        if (driverId.isNotEmpty() && driverId != "null") {
                            callback(driverId)
                            return@executeRequestWithBody
                        }
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // ─── NOTIFICATIONS ───────────────────────────────────────────────

    fun insertNotificationAdmin(
        userId: String? = null,
        title: String,
        message: String,
        type: String,
        data: JsonObject? = null,
        callback: (Boolean) -> Unit = {}
    ) {
        try {
            val json = JsonObject().apply {
                if (userId != null) addProperty("user_id", userId) else add("user_id", JsonNull.INSTANCE)
                addProperty("title", title)
                addProperty("message", message)
                addProperty("type", type)
                addProperty("read", false)
                addProperty("created_at", timestamp())
                addProperty("updated_at", timestamp())
                data?.let { add("data", it) }
            }

            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/notifications")
                .post(json.toString().toRequestBody(JSON))
                .addHeader("apikey", SERVICE_ROLE_KEY)
                .addHeader("Authorization", "Bearer $SERVICE_ROLE_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            adminClient.newCall(request).enqueue(object : Callback {
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

    fun markNotificationRead(notificationId: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("read", true)
            addProperty("updated_at", timestamp())
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/notifications?id=eq.$notificationId")
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        executeRequest(request, callback)
    }

    fun getUserNotifications(userId: String, limit: Int = 50, callback: (JSONArray?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/notifications?user_id=eq.$userId&order=created_at.desc&limit=$limit")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    callback(JSONArray(body))
                } else {
                    callback(null)
                }
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // ============================================================
    // NEW METHODS FOR TRIP, GPS, STATUS, AND DEPARTURE
    // ============================================================

    fun createTrip(jeepneyId: String, route: String, passengers: Int, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("jeepney_id", jeepneyId)
            addProperty("route", route)
            addProperty("status", "in_progress")
            addProperty("passengers", passengers)
            addProperty("started_at", timestamp())
        }
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/trips")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    fun getLatestGps(jeepneyId: String, callback: (Pair<Double, Double>?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/gps_tracking?jeepney_id=eq.$jeepneyId&order=recorded_at.desc&limit=1&select=latitude,longitude")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        val lat = obj.optDouble("latitude")
                        val lon = obj.optDouble("longitude")
                        if (!lat.isNaN() && !lon.isNaN()) {
                            callback(Pair(lat, lon))
                            return@executeRequestWithBody
                        }
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    /**
     * Calls the 'depart_jeepney' RPC to set status to 'en_route', clear queue, and create a trip.
     *
     * IMPORTANT: this is the ONLY safe way to flip a jeepney to 'en_route' while it still
     * holds a queue_position — the jeepneys_enroute_queue_pos_check constraint requires
     * queue_position IS NULL whenever status = 'en_route'. Never PATCH status alone
     * (see updateStatus()) for a jeepney that is currently 'loading'/queued, or the write
     * will violate that constraint, silently fail, and the status will appear to "snap back"
     * to 'loading' the next time it's polled.
     *
     * If the RPC itself doesn't confirm success (network hiccup, RPC missing, etc.) this
     * falls back to a manual PATCH that clears status + queue_position in the SAME request
     * so the constraint is never violated even in the fallback path.
     */
    fun departJeepney(jeepneyId: String, route: String = "Daraga → Donsol", callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("p_jeepney_id", jeepneyId)
            addProperty("p_route", route)
        }
        executeRpc("depart_jeepney", json) { body ->
            val rpcSucceeded = try {
                body.lowercase().contains("true") || body.lowercase().contains("success")
            } catch (_: Exception) {
                false
            }

            if (rpcSucceeded) {
                callback(true)
            } else {
                Log.w(TAG, "⚠️ depart_jeepney RPC didn't confirm success (body=$body) — applying manual fallback")
                clearQueueAndSetEnRoute(jeepneyId, callback)
            }
        }
    }

    /**
     * Removes a jeepney from the active queue because it left the terminal while
     * still "waiting" (never made it to "loading"). Deliberately does NOT promote
     * the next waiting jeepney — the loading slot at this terminal/bracket is
     * still held by whichever jeepney is actually "loading" (if any), so
     * promoting someone else here would create two "loading" jeepneys at once.
     * The reorder_queue_after_departure trigger compacts everyone else's
     * queue_position once this row's queue_position clears.
     *
     * IMPORTANT: this is NOT the same as skip_waiting_jeepney() — that RPC keeps
     * status = 'waiting' and just pushes the jeep to the back of the queue (e.g.
     * a dispatcher manually skipping its turn while it's still physically
     * present). It must not be used when the jeepney has actually left, or the
     * row stays "waiting" forever and can later be wrongly promoted to "loading".
     */
    fun leaveQueue(jeepneyId: String, callback: (Boolean) -> Unit) {
        clearQueueAndSetEnRoute(jeepneyId, callback)
    }

    /**
     * Atomically clears queue_position and flips status to 'en_route' in ONE
     * patch. Must stay atomic — patching status and queue_position in separate
     * requests re-opens the "flips to en_route then snaps back to loading" bug
     * (jeepneys_enroute_queue_pos_check requires queue_position IS NULL whenever
     * status = 'en_route').
     */
    private fun clearQueueAndSetEnRoute(jeepneyId: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("status", "en_route")
            add("queue_position", JsonNull.INSTANCE)
            addProperty("departed_at", timestamp())
            addProperty("updated_at", timestamp())
        }
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepneyId")
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    fun getCurrentStatus(jeepneyId: String, callback: (String?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?select=status&id=eq.$jeepneyId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        callback(obj.optString("status"))
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getTerminalId(jeepneyId: String, callback: (Int?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?select=terminal_id&id=eq.$jeepneyId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        callback(obj.optInt("terminal_id", -1).takeIf { it != -1 })
                        return@executeRequestWithBody
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun getLoadingStartedAt(jeepneyId: String, callback: (Long?) -> Unit) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?select=loading_started_at&id=eq.$jeepneyId")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        executeRequestWithBody(request) { body ->
            try {
                if (!body.isNullOrEmpty()) {
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val obj = array.getJSONObject(0)
                        val dateStr = obj.optString("loading_started_at")
                        if (dateStr.isNotEmpty()) {
                            val date = utcDateFormat().parse(dateStr)
                            callback(date?.time)
                            return@executeRequestWithBody
                        }
                    }
                }
                callback(null)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // ─── BLOCKING VERSIONS FOR WorkManager ─────────────────────────────

    fun getCurrentStatusBlocking(jeepneyId: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        getCurrentStatus(jeepneyId) { status ->
            result = status
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun getTerminalIdBlocking(jeepneyId: String): Int? {
        var result: Int? = null
        val latch = CountDownLatch(1)
        getTerminalId(jeepneyId) { id ->
            result = id
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun getLatestGpsBlocking(jeepneyId: String): Pair<Double, Double>? {
        var result: Pair<Double, Double>? = null
        val latch = CountDownLatch(1)
        getLatestGps(jeepneyId) { gps ->
            result = gps
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun getLoadingStartedAtBlocking(jeepneyId: String): Long? {
        var result: Long? = null
        val latch = CountDownLatch(1)
        getLoadingStartedAt(jeepneyId) { timestamp ->
            result = timestamp
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun departJeepneyBlocking(jeepneyId: String, route: String = "Daraga → Donsol"): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        departJeepney(jeepneyId, route) { success ->
            result = success
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }
}
package com.surendramaran.Jeepqs.services

import android.content.Context
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
import java.util.concurrent.TimeUnit

class SupabaseService(
    private val context: Context
) {

    companion object {
        private const val SUPABASE_URL = "https://mfztenjrwtfjsgebmvda.supabase.co"
        private const val SUPABASE_KEY = "sb_publishable_goOnWdfw3tacBtKYBE7ZFA_JLhgM9vb"
        private const val SERVICE_ROLE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1menRlbmpyd3RmanNnZWJtdmRhIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4MjcxOTgxMSwiZXhwIjoyMDk4Mjk1ODExfQ.Xs96smUK9DGSzDBrsehHaJKnYaSrDROJWMRnXVUM3jE"
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

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
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
                onSuccess("[]")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string() ?: "[]"
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
        val driverName: String
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

    fun getJeepneyWithDriver(callback: (JeepneyWithDriver?) -> Unit) {
        if (jeepId.isNullOrEmpty()) {
            callback(null)
            return
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId&select=plate_number,jeep_name,bracket,capacity,current_occupancy,driver_name,status")
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
                        val info = JeepneyWithDriver(
                            plateNumber = obj.optString("plate_number", "UNKNOWN"),
                            jeepName = obj.optString("jeep_name", ""),
                            bracket = obj.optInt("bracket", 1),
                            capacity = obj.optInt("capacity", 24),
                            occupancy = obj.optInt("current_occupancy", 0),
                            driverName = obj.optString("driver_name", "Not Assigned")
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
                    callback(0, "inactive")
                }
            } catch (_: Exception) {
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
        getUsers(role = "commuter", terminalId = terminalId, callback = callback)
    }

    fun getDispatchers(callback: (JSONArray?) -> Unit) {
        getUsers(role = "dispatcher", callback = callback)
    }

    fun getDrivers(callback: (JSONArray?) -> Unit) {
        getUsers(role = "driver", callback = callback)
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
        userId: String,
        title: String,
        message: String,
        type: String,
        data: JsonObject? = null,
        callback: (Boolean) -> Unit = {}
    ) {
        try {
            val json = JsonObject().apply {
                addProperty("user_id", userId)
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
}
package com.surendramaran.Jeepqs.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.surendramaran.Jeepqs.settings.DeviceConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class SupabaseService(
    private val context: Context
) {

    companion object {
        private const val TAG = "Supabase"
        private const val SUPABASE_URL = "https://mfztenjrwtfjsgebmvda.supabase.co"
        private const val SUPABASE_KEY = "sb_publishable_goOnWdfw3tacBtKYBE7ZFA_JLhgM9vb"
    }

    private val jeepId
        get() = DeviceConfig.jeepId(context)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
    }

    private fun patchDoorCounts(json: JsonObject, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) { callback(false); return }
        val url = "$SUPABASE_URL/rest/v1/door_counts?jeep_id=eq.$jeepId"
        executePatch(url, json, callback)
    }

    private fun patchJeepneys(json: JsonObject, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) { callback(false); return }
        val url = "$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId"
        executePatch(url, json, callback)
    }

    private fun executePatch(url: String, json: JsonObject, callback: (Boolean) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    private fun insertDoorCounts(json: JsonObject, callback: (Boolean) -> Unit) {
        val url = "$SUPABASE_URL/rest/v1/door_counts"
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        executeRequest(request, callback)
    }

    private fun executeRequest(request: Request, callback: (Boolean) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(false) }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                response.close()
                callback(success)
            }
        })
    }

    fun upsertDoorCounts(front: Int, rear: Int, callback: (Boolean) -> Unit) {
        if (jeepId.isNullOrEmpty()) { callback(false); return }

        val checkRequest = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/door_counts?jeep_id=eq.$jeepId&select=id")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(checkRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(false) }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                response.close()
                try {
                    val exists = !body.isNullOrEmpty() && JSONArray(body).length() > 0
                    val json = JsonObject().apply {
                        addProperty("front_count", front)
                        addProperty("rear_count", rear)
                        addProperty("updated_at", timestamp())
                    }
                    if (exists) patchDoorCounts(json, callback)
                    else {
                        json.addProperty("jeep_id", jeepId)
                        insertDoorCounts(json, callback)
                    }
                } catch (_: Exception) { callback(false) }
            }
        })
    }

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
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/jeepneys?id=eq.$jeepId&select=id,plate_number,jeep_name,bracket,capacity,current_occupancy,driver_name")
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(null) }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    callback(null)
                    response.close()
                    return
                }
                try {
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
                        Log.d(TAG, "✅ Jeepney info: ${info.jeepName} (${info.plateNumber}), Driver: ${info.driverName}, Occ: ${info.occupancy}")
                        callback(info)
                    } else callback(null)
                } catch (_: Exception) { callback(null) }
                response.close()
            }
        })
    }

    fun getJeepneyWithDriver(callback: (JeepneyWithDriver?) -> Unit) {
        getJeepInfo { jeepneyInfo ->
            if (jeepneyInfo == null) {
                Log.e(TAG, "❌ Failed to get jeepney info")
                callback(null)
                return@getJeepInfo
            }

            val result = JeepneyWithDriver(
                plateNumber = jeepneyInfo.plateNumber,
                jeepName = jeepneyInfo.jeepName,
                driverName = jeepneyInfo.driverName,
                bracket = jeepneyInfo.bracket,
                capacity = jeepneyInfo.capacity,
                occupancy = jeepneyInfo.occupancy
            )
            Log.d(TAG, "✅ JeepneyWithDriver: ${result.jeepName} (${result.plateNumber}), Driver: ${result.driverName}, Occ: ${result.occupancy}")
            callback(result)
        }
    }

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
                } else callback(0, "inactive")
            } catch (_: Exception) { callback(0, "inactive") }
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
    fun sendGpsTracking(
        latitude: Double,
        longitude: Double,
        speed: Double = 0.0,
        heading: Double = 0.0,
        callback: (Boolean) -> Unit
    ) {
        if (jeepId.isNullOrEmpty()) {
            Log.e(TAG, " No jeep ID")
            callback(false)
            return
        }

        Log.d(TAG, " Sending GPS to tracking table: $latitude, $longitude")

        val json = JsonObject().apply {
            addProperty("jeepney_id", jeepId)
            addProperty("latitude", latitude)
            addProperty("longitude", longitude)
            addProperty("speed", speed)
            addProperty("heading", heading)
            addProperty("recorded_at", timestamp())
        }

        val url = "$SUPABASE_URL/rest/v1/gps_tracking"
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        executeRequest(request, callback)
    }
    // ─── ADD TO QUEUE WITH TERMINAL ──────────────────────────────────
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
                } else callback(0, "inactive")
            } catch (_: Exception) { callback(0, "inactive") }
        }
    }

    fun getNextInQueue(terminalId: Int, bracket: Int, callback: (JeepneyInfo?) -> Unit) {
        val url = "$SUPABASE_URL/rest/v1/rpc/get_next_in_queue?p_terminal_id=$terminalId&p_bracket=$bracket"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                response.close()

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
                        } else callback(null)
                    } else callback(null)
                } catch (_: Exception) { callback(null) }
            }
        })
    }
    // ─── INSERT NOTIFICATION ──────────────────────────────────────────
    fun insertNotification(
        userId: String,
        title: String,
        message: String,
        type: String,
        data: JsonObject? = null,
        callback: (Boolean) -> Unit = { }
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

                if (data != null) {
                    add("data", data)
                }
            }

            val url = "$SUPABASE_URL/rest/v1/notifications"
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON))
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            executeRequest(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting notification: ${e.message}")
            callback(false)
        }
    }

    // ─── GET USERS BY ROLE OR TERMINAL ──────────────────────────────
    fun getUsers(
        role: String? = null,
        terminalId: Int? = null,
        bracket: Int? = null,
        callback: (JSONArray?) -> Unit
    ) {
        var url = "$SUPABASE_URL/rest/v1/users?select=id,phone_number,name,role,preferred_terminal,preferred_bracket,fcm_token&is_active=eq.true"

        if (role != null) {
            url += "&role=eq.$role"
        }
        if (terminalId != null) {
            url += "&preferred_terminal=eq.$terminalId"
        }
        if (bracket != null) {
            url += "&preferred_bracket=eq.$bracket"
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to get users: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                response.close()

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
        })
    }

    // ─── GET USER BY PHONE NUMBER ────────────────────────────────────
    fun getUserByPhone(phoneNumber: String, callback: (JSONObject?) -> Unit) {
        val url = "$SUPABASE_URL/rest/v1/users?phone_number=eq.$phoneNumber&select=id,name,role,preferred_terminal,preferred_bracket"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                response.close()

                try {
                    if (!body.isNullOrEmpty()) {
                        val array = JSONArray(body)
                        if (array.length() > 0) {
                            callback(array.getJSONObject(0))
                        } else {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                } catch (_: Exception) {
                    callback(null)
                }
            }
        })
    }

    // ─── MARK NOTIFICATION AS READ ──────────────────────────────────
    fun markNotificationRead(notificationId: String, callback: (Boolean) -> Unit) {
        val json = JsonObject().apply {
            addProperty("read", true)
            addProperty("updated_at", timestamp())
        }

        val url = "$SUPABASE_URL/rest/v1/notifications?id=eq.$notificationId"
        val request = Request.Builder()
            .url(url)
            .patch(json.toString().toRequestBody(JSON))
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        executeRequest(request, callback)
    }

    // ─── GET USER NOTIFICATIONS ──────────────────────────────────────
    fun getUserNotifications(userId: String, limit: Int = 50, callback: (JSONArray?) -> Unit) {
        val url = "$SUPABASE_URL/rest/v1/notifications?user_id=eq.$userId&order=created_at.desc&limit=$limit"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                response.close()

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
            override fun onFailure(call: Call, e: IOException) { onSuccess("[]") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "[]"
                response.close()
                onSuccess(body)
            }
        })
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
}
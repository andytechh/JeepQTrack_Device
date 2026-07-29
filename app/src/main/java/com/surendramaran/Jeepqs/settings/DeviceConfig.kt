package com.surendramaran.Jeepqs.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object DeviceConfig {
    private const val TAG = "DeviceConfig"
    private const val PREF_NAME = "device_config"

    // Keys
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_ROLE = "role"
    private const val KEY_DOOR = "door"
    private const val KEY_JEEP_ID = "jeep_id"
    private const val KEY_PLATE_NUMBER = "plate_number"
    private const val KEY_JEEP_NAME = "jeep_name"

    private lateinit var prefs: SharedPreferences
    private var isInitialized = false

    // ─── INIT ──────────────────────────────────────────────────────────
    fun init(context: Context) {
        if (!isInitialized) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            isInitialized = true
            Log.d(TAG, "✅ DeviceConfig initialized")
            Log.d(TAG, "Current config: ${getConfigSummary()}")
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("DeviceConfig not initialized. Call init(context) first.")
        }
    }

    // ─── CONFIGURATION STATUS ────────────────────────────────────────
    fun isConfigured(): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_CONFIGURED, false)
    }

    fun setConfigured(configured: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_CONFIGURED, configured).apply()
    }

    // ─── ROLE ──────────────────────────────────────────────────────────
    fun getRole(): String {
        ensureInitialized()
        val role = prefs.getString(KEY_ROLE, "PRIMARY") ?: "PRIMARY"
        Log.d(TAG, "📋 Role: $role")
        return role
    }

    fun setRole(role: String) {
        ensureInitialized()
        prefs.edit().putString(KEY_ROLE, role).apply()
        Log.d(TAG, "📋 Role set to: $role")
    }

    fun isPrimary(): Boolean {
        return getRole() == "PRIMARY"
    }

    fun isSecondary(): Boolean {
        return getRole() == "SECONDARY"
    }

    // ─── DOOR ──────────────────────────────────────────────────────────
    fun getDoor(): String {
        ensureInitialized()
        return prefs.getString(KEY_DOOR, "FRONT") ?: "FRONT"
    }

    fun setDoor(door: String) {
        ensureInitialized()
        prefs.edit().putString(KEY_DOOR, door).apply()
        Log.d(TAG, "🚪 Door set to: $door")
    }

    fun isFrontDoor(): Boolean {
        return getDoor() == "FRONT"
    }

    fun isRearDoor(): Boolean {
        return getDoor() == "REAR"
    }

    // ─── JEEP ID ───────────────────────────────────────────────────────
    fun getJeepId(): String? {
        ensureInitialized()
        val id = prefs.getString(KEY_JEEP_ID, null)
        if (id != null && !isValidUUID(id)) {
            Log.w(TAG, "⚠️ Invalid Jeep ID format: $id")
            return null
        }
        return id
    }

    fun setJeepId(jeepId: String) {
        ensureInitialized()
        prefs.edit().putString(KEY_JEEP_ID, jeepId).apply()
        Log.d(TAG, "🚌 Jeep ID set to: $jeepId")
    }

    // ─── PLATE NUMBER ─────────────────────────────────────────────────
    fun getPlateNumber(): String? {
        ensureInitialized()
        return prefs.getString(KEY_PLATE_NUMBER, null)
    }

    fun setPlateNumber(plateNumber: String) {
        ensureInitialized()
        prefs.edit().putString(KEY_PLATE_NUMBER, plateNumber).apply()
        Log.d(TAG, "📝 Plate number set to: $plateNumber")
    }

    // ─── JEEP NAME ────────────────────────────────────────────────────
    fun getJeepName(): String? {
        ensureInitialized()
        return prefs.getString(KEY_JEEP_NAME, null)
    }

    fun setJeepName(jeepName: String) {
        ensureInitialized()
        prefs.edit().putString(KEY_JEEP_NAME, jeepName).apply()
        Log.d(TAG, "🚌 Jeep name set to: $jeepName")
    }

    // ─── SAVE ALL CONFIG ─────────────────────────────────────────────
    fun saveConfig(role: String, door: String, jeepId: String, plateNumber: String? = null, jeepName: String? = null) {
        ensureInitialized()
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_ROLE, role)
            .putString(KEY_DOOR, door)
            .putString(KEY_JEEP_ID, jeepId)
            .apply()

        if (plateNumber != null) {
            setPlateNumber(plateNumber)
        }
        if (jeepName != null) {
            setJeepName(jeepName)
        }

        Log.d(TAG, "💾 Configuration saved:")
        Log.d(TAG, "  Role: $role")
        Log.d(TAG, "  Door: $door")
        Log.d(TAG, "  Jeep ID: $jeepId")
        Log.d(TAG, "  Plate: $plateNumber")
        Log.d(TAG, "  Name: $jeepName")
    }

    // ─── CLEAR CONFIG ─────────────────────────────────────────────────
    fun clearConfig() {
        ensureInitialized()
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, false)
            .putString(KEY_ROLE, "PRIMARY")
            .putString(KEY_DOOR, "FRONT")
            .putString(KEY_JEEP_ID, null)
            .putString(KEY_PLATE_NUMBER, null)
            .putString(KEY_JEEP_NAME, null)
            .apply()
        Log.d(TAG, "🗑️ Configuration cleared")
    }

    // ─── GET CONFIG SUMMARY ──────────────────────────────────────────
    fun getConfigSummary(): String {
        ensureInitialized()
        return """
            Device Config:
            - Configured: ${isConfigured()}
            - Role: ${getRole()}
            - Door: ${getDoor()}
            - Jeep ID: ${getJeepId() ?: "Not set"}
            - Plate: ${getPlateNumber() ?: "Not set"}
            - Name: ${getJeepName() ?: "Not set"}
        """.trimIndent()
    }

    // ─── VALIDATION ──────────────────────────────────────────────────
    fun isValidUUID(uuid: String): Boolean {
        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")
        return uuidRegex.matches(uuid)
    }

    // ─── BACKWARDS COMPATIBILITY (Static functions with Context) ────
    // Keep these for compatibility with existing code

    @Deprecated("Use getJeepId() instead", ReplaceWith("getJeepId()"))
    fun jeepId(context: Context): String? {
        init(context)
        return getJeepId()
    }

    @Deprecated("Use getRole() instead", ReplaceWith("getRole()"))
    fun role(context: Context): String? {
        init(context)
        return getRole()
    }

    @Deprecated("Use getDoor() instead", ReplaceWith("getDoor()"))
    fun door(context: Context): String? {
        init(context)
        return getDoor()
    }

    @Deprecated("Use getPlateNumber() instead", ReplaceWith("getPlateNumber()"))
    fun plateNumber(context: Context): String? {
        init(context)
        return getPlateNumber()
    }

    @Deprecated("Use getJeepName() instead", ReplaceWith("getJeepName()"))
    fun jeepName(context: Context): String? {
        init(context)
        return getJeepName()
    }
}
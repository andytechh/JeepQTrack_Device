package com.surendramaran.Jeepqs.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object DeviceConfig {

    private const val TAG = "DeviceConfig"
    private const val PREF_NAME = "device_config"

    // ============================================================
    // FIXED HTTP SMS SENDER NUMBERS
    // ============================================================
    //
    // Device 1 -> 09938901689
    // Device 2 -> 09244508563
    //
    // These are the SIM/mobile numbers registered with httpSMS.
    // The user selects Device 1 or Device 2 in Setup.
    // The app automatically uses the corresponding number.
    // ============================================================

    private const val DEVICE_1_SMS_NUMBER = "+639938901689"
    private const val DEVICE_2_SMS_NUMBER = "+639244508563"

    // ============================================================
    // KEYS
    // ============================================================

    private const val KEY_CONFIGURED = "configured"
    private const val KEY_ROLE = "role"
    private const val KEY_DOOR = "door"
    private const val KEY_JEEP_ID = "jeep_id"
    private const val KEY_PLATE_NUMBER = "plate_number"
    private const val KEY_JEEP_NAME = "jeep_name"
    private const val KEY_TERMINAL_ID = "terminal_id"
    private const val KEY_BRACKET = "bracket"

    // Selected physical SMS device
    private const val KEY_DEVICE_NUMBER = "device_number"

    // Kept for compatibility with existing SMSService code.
    // These are no longer entered manually in Setup.
    private const val KEY_HTTPSMS_FROM_1 = "httpsms_from_number_1"
    private const val KEY_HTTPSMS_FROM_2 = "httpsms_from_number_2"

    private lateinit var prefs: SharedPreferences
    private var isInitialized = false

    // ============================================================
    // INIT
    // ============================================================

    fun init(context: Context) {
        if (!isInitialized) {
            prefs = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
            )

            isInitialized = true

            Log.d(TAG, "DeviceConfig initialized")
            Log.d(TAG, "Current config: ${getConfigSummary()}")
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            throw IllegalStateException(
                "DeviceConfig not initialized. Call init(context) first."
            )
        }
    }

    // ============================================================
    // CONFIGURATION STATUS
    // ============================================================

    fun isConfigured(): Boolean {
        ensureInitialized()
        return prefs.getBoolean(KEY_CONFIGURED, false)
    }

    fun setConfigured(configured: Boolean) {
        ensureInitialized()

        prefs.edit()
            .putBoolean(KEY_CONFIGURED, configured)
            .apply()
    }

    // ============================================================
    // PHYSICAL DEVICE
    // ============================================================

    /**
     * Returns the selected physical device number.
     *
     * 1 = Device 1
     * 2 = Device 2
     */
    fun getDeviceNumber(): Int {
        ensureInitialized()

        return prefs.getInt(KEY_DEVICE_NUMBER, 1)
    }

    /**
     * Saves the selected physical device number.
     *
     * Only 1 or 2 are accepted.
     */
    fun setDeviceNumber(deviceNumber: Int) {
        ensureInitialized()

        if (deviceNumber != 1 && deviceNumber != 2) {
            Log.w(
                TAG,
                "Invalid device number $deviceNumber. Using Device 1."
            )

            prefs.edit()
                .putInt(KEY_DEVICE_NUMBER, 1)
                .apply()

            return
        }

        prefs.edit()
            .putInt(KEY_DEVICE_NUMBER, deviceNumber)
            .apply()

        Log.d(
            TAG,
            "Physical device set to: Device $deviceNumber"
        )

        Log.d(
            TAG,
            "SMS sender: ${getHttpSmsSenderNumber()}"
        )
    }

    fun isDevice1(): Boolean {
        return getDeviceNumber() == 1
    }

    fun isDevice2(): Boolean {
        return getDeviceNumber() == 2
    }

    /**
     * Returns the fixed httpSMS sender number for the
     * currently selected physical device.
     */
    fun getHttpSmsSenderNumber(): String {
        return when (getDeviceNumber()) {
            2 -> DEVICE_2_SMS_NUMBER
            else -> DEVICE_1_SMS_NUMBER
        }
    }

    /**
     * Returns the fixed sender number for a specific device.
     */
    fun getHttpSmsSenderNumber(deviceNumber: Int): String {
        return when (deviceNumber) {
            2 -> DEVICE_2_SMS_NUMBER
            else -> DEVICE_1_SMS_NUMBER
        }
    }

    // ============================================================
    // CONTEXT CONVENIENCE METHODS
    // ============================================================

    fun deviceNumber(context: Context): Int {
        init(context)
        return getDeviceNumber()
    }

    fun httpSmsSenderNumber(context: Context): String {
        init(context)
        return getHttpSmsSenderNumber()
    }

    // ============================================================
    // ROLE
    // ============================================================

    fun getRole(): String {
        ensureInitialized()

        val role = prefs.getString(
            KEY_ROLE,
            "PRIMARY"
        ) ?: "PRIMARY"

        Log.d(TAG, "Role: $role")

        return role
    }

    fun setRole(role: String) {
        ensureInitialized()

        prefs.edit()
            .putString(KEY_ROLE, role)
            .apply()

        Log.d(TAG, "Role set to: $role")
    }

    fun isPrimary(): Boolean {
        return getRole() == "PRIMARY"
    }

    fun isSecondary(): Boolean {
        return getRole() == "SECONDARY"
    }

    // ============================================================
    // DOOR
    // ============================================================

    fun getDoor(): String {
        ensureInitialized()

        return prefs.getString(
            KEY_DOOR,
            "FRONT"
        ) ?: "FRONT"
    }

    fun setDoor(door: String) {
        ensureInitialized()

        prefs.edit()
            .putString(KEY_DOOR, door)
            .apply()

        Log.d(TAG, "Door set to: $door")
    }

    fun isFrontDoor(): Boolean {
        return getDoor() == "FRONT"
    }

    fun isRearDoor(): Boolean {
        return getDoor() == "REAR"
    }

    // ============================================================
    // JEEP ID
    // ============================================================

    fun getJeepId(): String? {
        ensureInitialized()

        val id = prefs.getString(
            KEY_JEEP_ID,
            null
        )

        if (id != null && !isValidUUID(id)) {
            Log.w(
                TAG,
                "Invalid Jeep ID format: $id"
            )

            return null
        }

        return id
    }

    fun setJeepId(jeepId: String) {
        ensureInitialized()

        prefs.edit()
            .putString(KEY_JEEP_ID, jeepId)
            .apply()

        Log.d(TAG, "Jeep ID set to: $jeepId")
    }

    // ============================================================
    // PLATE NUMBER
    // ============================================================

    fun getPlateNumber(): String? {
        ensureInitialized()

        return prefs.getString(
            KEY_PLATE_NUMBER,
            null
        )
    }

    fun setPlateNumber(plateNumber: String) {
        ensureInitialized()

        prefs.edit()
            .putString(KEY_PLATE_NUMBER, plateNumber)
            .apply()

        Log.d(
            TAG,
            "Plate number set to: $plateNumber"
        )
    }

    // ============================================================
    // JEEP NAME
    // ============================================================

    fun getJeepName(): String? {
        ensureInitialized()

        return prefs.getString(
            KEY_JEEP_NAME,
            null
        )
    }

    fun setJeepName(jeepName: String) {
        ensureInitialized()

        prefs.edit()
            .putString(KEY_JEEP_NAME, jeepName)
            .apply()

        Log.d(
            TAG,
            "Jeep name set to: $jeepName"
        )
    }

    // ============================================================
    // TERMINAL & BRACKET
    // ============================================================

    fun getInt(key: String): Int? {
        ensureInitialized()

        return prefs
            .getInt(key, -1)
            .takeIf { it != -1 }
    }

    fun getTerminalId(): Int? {
        return getInt(KEY_TERMINAL_ID)
    }

    fun getBracket(): Int? {
        return getInt(KEY_BRACKET)
    }

    fun setTerminalId(terminalId: Int) {
        ensureInitialized()

        prefs.edit()
            .putInt(KEY_TERMINAL_ID, terminalId)
            .apply()

        Log.d(
            TAG,
            "Terminal ID set to: $terminalId"
        )
    }

    fun setBracket(bracket: Int) {
        ensureInitialized()

        prefs.edit()
            .putInt(KEY_BRACKET, bracket)
            .apply()

        Log.d(
            TAG,
            "Bracket set to: $bracket"
        )
    }

    // ============================================================
    // HTTP SMS COMPATIBILITY
    // ============================================================

    /**
     * Compatibility method.
     *
     * Device 1 always returns:
     * 09938901689
     */
    fun getHttpSmsFromNumber1(): String {
        ensureInitialized()

        return DEVICE_1_SMS_NUMBER
    }

    /**
     * Compatibility method.
     *
     * Device 2 always returns:
     * 09244508563
     */
    fun getHttpSmsFromNumber2(): String {
        ensureInitialized()

        return DEVICE_2_SMS_NUMBER
    }

    /**
     * These setters are retained so existing code that calls them
     * will still compile, but the actual sender numbers remain fixed.
     */
    fun setHttpSmsFromNumber1(phoneNumber: String) {
        ensureInitialized()

        Log.d(
            TAG,
            "Ignoring manual Device 1 SMS number: $phoneNumber"
        )

        Log.d(
            TAG,
            "Device 1 SMS number is fixed to: $DEVICE_1_SMS_NUMBER"
        )
    }

    fun setHttpSmsFromNumber2(phoneNumber: String) {
        ensureInitialized()

        Log.d(
            TAG,
            "Ignoring manual Device 2 SMS number: $phoneNumber"
        )

        Log.d(
            TAG,
            "Device 2 SMS number is fixed to: $DEVICE_2_SMS_NUMBER"
        )
    }

    fun httpSmsFromNumber1(context: Context): String {
        init(context)
        return DEVICE_1_SMS_NUMBER
    }

    fun httpSmsFromNumber2(context: Context): String {
        init(context)
        return DEVICE_2_SMS_NUMBER
    }

    // ============================================================
    // DEVICE SLOT MAPPING  (used by SMSService)
    // ============================================================
    //
    // Slot 0 -> Device 1 -> HTTPSMS_API_KEY_1 -> +639938901689
    // Slot 1 -> Device 2 -> HTTPSMS_API_KEY_2 -> +639244508563
    //
    // Slots are 0-based because SMSService indexes its API keys that
    // way. Device numbers stay 1-based everywhere else in the app.
    //
    // This is the bridge that was missing: before, SMSService picked
    // a slot with a round-robin counter and never looked at the
    // device chosen in Setup.
    // ============================================================

    /**
     * Returns the httpSMS slot index (0 or 1) for the device
     * currently selected in Setup.
     */
    fun getConfiguredSlot(context: Context): Int {
        init(context)
        return if (getDeviceNumber() == 2) 1 else 0
    }

    /**
     * Returns the httpSMS slot index (0 or 1) for the device
     * currently selected in Setup.
     *
     * Requires init(context) to have been called already.
     */
    fun getConfiguredSlot(): Int {
        ensureInitialized()
        return if (getDeviceNumber() == 2) 1 else 0
    }

    /**
     * Returns the fixed sender number for a slot index.
     */
    fun httpSmsFromNumberForSlot(context: Context, slot: Int): String {
        init(context)
        return if (slot == 1) DEVICE_2_SMS_NUMBER else DEVICE_1_SMS_NUMBER
    }

    /**
     * Both sender SIMs, used to make sure the app never SMSes itself.
     */
    fun allSenderNumbers(context: Context): Set<String> {
        init(context)
        return setOf(DEVICE_1_SMS_NUMBER, DEVICE_2_SMS_NUMBER)
    }

    // ============================================================
    // SAVE ALL CONFIGURATION
    // ============================================================

    fun saveConfig(
        role: String,
        door: String,
        jeepId: String,
        deviceNumber: Int = 1,
        plateNumber: String? = null,
        jeepName: String? = null,
        terminalId: Int? = null,
        bracket: Int? = null
    ) {
        ensureInitialized()

        val safeDeviceNumber =
            if (deviceNumber == 2) 2 else 1

        prefs.edit()
            .putBoolean(
                KEY_CONFIGURED,
                true
            )
            .putString(
                KEY_ROLE,
                role
            )
            .putString(
                KEY_DOOR,
                door
            )
            .putString(
                KEY_JEEP_ID,
                jeepId
            )
            .putInt(
                KEY_DEVICE_NUMBER,
                safeDeviceNumber
            )
            .apply()

        plateNumber?.let {
            setPlateNumber(it)
        }

        jeepName?.let {
            setJeepName(it)
        }

        terminalId?.let {
            setTerminalId(it)
        }

        bracket?.let {
            setBracket(it)
        }

        Log.d(TAG, "Configuration saved:")
        Log.d(TAG, "  Device: $safeDeviceNumber")
        Log.d(
            TAG,
            "  SMS Sender: ${getHttpSmsSenderNumber(safeDeviceNumber)}"
        )
        Log.d(TAG, "  Role: $role")
        Log.d(TAG, "  Door: $door")
        Log.d(TAG, "  Jeep ID: $jeepId")
        Log.d(TAG, "  Plate: $plateNumber")
        Log.d(TAG, "  Name: $jeepName")
        Log.d(TAG, "  Terminal: $terminalId")
        Log.d(TAG, "  Bracket: $bracket")
    }

    // ============================================================
    // CLEAR CONFIGURATION
    // ============================================================

    fun clearConfig() {
        ensureInitialized()

        prefs.edit()
            .putBoolean(
                KEY_CONFIGURED,
                false
            )
            .putString(
                KEY_ROLE,
                "PRIMARY"
            )
            .putString(
                KEY_DOOR,
                "FRONT"
            )
            .putString(
                KEY_JEEP_ID,
                null
            )
            .putString(
                KEY_PLATE_NUMBER,
                null
            )
            .putString(
                KEY_JEEP_NAME,
                null
            )
            .putInt(
                KEY_TERMINAL_ID,
                -1
            )
            .putInt(
                KEY_BRACKET,
                -1
            )
            .putInt(
                KEY_DEVICE_NUMBER,
                1
            )
            .apply()

        Log.d(TAG, "Configuration cleared")
        Log.d(
            TAG,
            "SMS sender mapping remains fixed:"
        )
        Log.d(
            TAG,
            "Device 1 = $DEVICE_1_SMS_NUMBER"
        )
        Log.d(
            TAG,
            "Device 2 = $DEVICE_2_SMS_NUMBER"
        )
    }

    // ============================================================
    // CONFIG SUMMARY
    // ============================================================

    fun getConfigSummary(): String {
        ensureInitialized()

        val device = getDeviceNumber()

        return """
            Device Config:
            - Configured: ${isConfigured()}
            - Physical Device: Device $device
            - SMS Sender: ${getHttpSmsSenderNumber(device)}
            - Role: ${getRole()}
            - Door: ${getDoor()}
            - Jeep ID: ${getJeepId() ?: "Not set"}
            - Plate: ${getPlateNumber() ?: "Not set"}
            - Name: ${getJeepName() ?: "Not set"}
            - Terminal ID: ${getTerminalId() ?: "Not set"}
            - Bracket: ${getBracket() ?: "Not set"}
        """.trimIndent()
    }

    // ============================================================
    // VALIDATION
    // ============================================================

    fun isValidUUID(uuid: String): Boolean {
        val uuidRegex = Regex(
            "^[0-9a-fA-F]{8}-" +
                    "[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{12}$"
        )

        return uuidRegex.matches(uuid)
    }

    // ============================================================
    // BACKWARDS COMPATIBILITY
    // ============================================================

    @Deprecated(
        "Use getJeepId() instead",
        ReplaceWith("getJeepId()")
    )
    fun jeepId(context: Context): String? {
        init(context)
        return getJeepId()
    }

    @Deprecated(
        "Use getRole() instead",
        ReplaceWith("getRole()")
    )
    fun role(context: Context): String? {
        init(context)
        return getRole()
    }

    @Deprecated(
        "Use getDoor() instead",
        ReplaceWith("getDoor()")
    )
    fun door(context: Context): String? {
        init(context)
        return getDoor()
    }

    @Deprecated(
        "Use getPlateNumber() instead",
        ReplaceWith("getPlateNumber()")
    )
    fun plateNumber(context: Context): String? {
        init(context)
        return getPlateNumber()
    }

    @Deprecated(
        "Use getJeepName() instead",
        ReplaceWith("getJeepName()")
    )
    fun jeepName(context: Context): String? {
        init(context)
        return getJeepName()
    }
}
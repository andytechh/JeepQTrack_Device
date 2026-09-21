package com.surendramaran.Jeepqs.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.surendramaran.Jeepqs.services.SMSService
import com.surendramaran.Jeepqs.services.SupabaseService
import com.surendramaran.Jeepqs.workers.LoadingCheckWorker

/**
 * Manages both terminal geofences:
 * Donsol <-> Daraga.
 *
 * ENTER:
 *   arrived -> grace period -> queue
 *
 * QUEUE:
 *   loading or waiting
 *
 * EXIT:
 *   en_route + queue release through departJeepney()
 */
class GeofenceManager(
    private val context: Context,
    private val supabase: SupabaseService,
    private val jeepneyId: String,
    private var bracket: Int,
    terminalId: Int,
    private val onStatusChanged: (String) -> Unit
) {

    companion object {
        private const val TAG = "GeofenceManager"

        @Volatile
        var activeInstance: GeofenceManager? = null
            private set

        val TERMINALS = mapOf(
            1 to Pair(12.9032, 123.59425),
            2 to Pair(13.14769, 123.71216)
        )

        val TERMINAL_NAMES = mapOf(
            1 to "Donsol Terminal",
            2 to "Daraga Terminal"
        )

        const val GEOFENCE_RADIUS = 50.0

        // Set false before production/release.
        private const val DEBUG_TESTING_MODE = false

        val LOADING_DURATION: Long =
            if (DEBUG_TESTING_MODE) 2L else 30L

        val ALERT_THRESHOLD: Long =
            if (DEBUG_TESTING_MODE) 1L else 25L

        val ARRIVAL_GRACE_MINUTES: Long =
            if (DEBUG_TESTING_MODE) 1L else 1L

        private val WAITING_TIMEOUT: Long =
            if (DEBUG_TESTING_MODE) 1L else 1L

        val CHECK_INTERVAL: Long =
            if (DEBUG_TESTING_MODE) 15L else 15L

        init {
            if (DEBUG_TESTING_MODE) {
                Log.w(
                    TAG,
                    "DEBUG_TESTING_MODE ON — " +
                            "LOADING_DURATION=${LOADING_DURATION}m " +
                            "ALERT_THRESHOLD=${ALERT_THRESHOLD}m " +
                            "ARRIVAL_GRACE=${ARRIVAL_GRACE_MINUTES}m " +
                            "WAITING_TIMEOUT=${WAITING_TIMEOUT}m " +
                            "CHECK_INTERVAL=${CHECK_INTERVAL}s"
                )
            }
        }

        fun requestIdForTerminal(terminalId: Int): String =
            "terminal_${terminalId}_geofence"

        fun terminalIdFromRequestId(requestId: String): Int? =
            Regex("terminal_(\\d+)_geofence")
                .find(requestId)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
    }

    private val geofencingClient =
        LocationServices.getGeofencingClient(context)

    private val smsService =
        SMSService(context, supabase)

    private val handler =
        Handler(Looper.getMainLooper())

    private var isGeofenceRegistered = false

    private var currentStatus = "inactive"

    private var isDataLoaded = false

    private var cachedPlateNumber = "UNKNOWN"

    private var cachedJeepName = ""

    private var cachedDriverName = "Driver"

    private var cachedOccupancy = 0

    private var currentTerminalId: Int = terminalId

    private var loadingStartTime: Long = 0

    private var isInsideTerminal = false

    private var alertSentAt25 = false

    private var loadingCheckRunnable: Runnable? = null

    private var isLoadingSlotActive = false

    private var arrivalRunnable: Runnable? = null

    private var waitingRunnable: Runnable? = null


    // ================================================================
    // PUBLIC METHODS
    // ================================================================

    fun updateJeepneyData(
        plate: String,
        jeepName: String,
        driver: String,
        occupancy: Int
    ) {
        cachedPlateNumber = plate
        cachedJeepName = jeepName
        cachedDriverName = driver
        cachedOccupancy = occupancy
        isDataLoaded = true
    }

    fun updateBracket(newBracket: Int) {
        if (bracket != newBracket) {
            Log.d(
                TAG,
                "bracket corrected: $bracket -> $newBracket"
            )

            bracket = newBracket
        }
    }

    /**
     * Restore the local state from the actual Supabase jeepney row.
     */
    fun syncState(
        status: String,
        terminalId: Int,
        loadingStartedAtMillis: Long?
    ) {
        Log.d(
            TAG,
            "syncState status=$status " +
                    "terminalId=$terminalId " +
                    "loadingStartedAt=$loadingStartedAtMillis"
        )

        currentTerminalId = terminalId
        currentStatus = status

        onStatusChanged(status)

        when (status) {

            "loading" -> {
                loadingStartTime =
                    loadingStartedAtMillis
                        ?: System.currentTimeMillis()

                alertSentAt25 = false

                isLoadingSlotActive = true

                startLoadingCheck()

                LoadingCheckWorker.schedule(context)
            }

            "waiting" -> {
                startWaitingTimer()
            }
        }
    }

    fun updateOccupancy(occupancy: Int) {

        cachedOccupancy = occupancy

        if (occupancy >= 20) {

            smsService.notifyOccupancyAlert(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName(currentTerminalId),
                occupancy = occupancy,
                capacity = 24,
                terminalId = currentTerminalId,
                bracket = bracket
            )

            supabase.insertNotificationAdmin(
                title = "Jeepney Nearly Full",
                message =
                    "$cachedJeepName ($cachedPlateNumber) " +
                            "is at $occupancy/24 passengers.",
                type = "occupancy"
            ) { success ->
                Log.d(
                    TAG,
                    "notification insert (occupancy) success=$success"
                )
            }
        }
    }


    // ================================================================
    // GEOFENCE
    // ================================================================

    fun startGeofence() {

        if (!hasLocationPermission()) {
            Log.w(
                TAG,
                "Location permission is not granted"
            )
            return
        }

        activeInstance = this

        val geofences = TERMINALS.map { (id, latLng) ->

            Geofence.Builder()
                .setRequestId(requestIdForTerminal(id))
                .setCircularRegion(
                    latLng.first,
                    latLng.second,
                    GEOFENCE_RADIUS.toFloat()
                )
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                            Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setExpirationDuration(
                    Geofence.NEVER_EXPIRE
                )
                .build()
        }

        val geofencingRequest =
            GeofencingRequest.Builder()
                .setInitialTrigger(
                    GeofencingRequest.INITIAL_TRIGGER_ENTER
                )
                .addGeofences(geofences)
                .build()

        val pendingIntent =
            GeofencePendingIntent.getPendingIntent(context)

        geofencingClient
            .addGeofences(
                geofencingRequest,
                pendingIntent
            )
            .addOnSuccessListener {

                isGeofenceRegistered = true

                Log.d(
                    TAG,
                    "Geofences registered for terminals: ${TERMINALS.keys}"
                )
            }
            .addOnFailureListener { e ->

                isGeofenceRegistered = false

                Log.e(
                    TAG,
                    "Geofence registration FAILED: ${e.message}",
                    e
                )
            }
    }

    fun stopGeofence() {

        if (activeInstance === this) {
            activeInstance = null
        }

        if (!isGeofenceRegistered) {
            return
        }

        val pendingIntent =
            GeofencePendingIntent.getPendingIntent(context)

        geofencingClient.removeGeofences(pendingIntent)

        isGeofenceRegistered = false

        cancelAllTimers()

        stopLoadingCheck()
    }

    fun onGeofenceTransition(
        transitionType: Int,
        requestId: String
    ) {

        val terminalId =
            terminalIdFromRequestId(requestId)

        Log.d(
            TAG,
            "onGeofenceTransition " +
                    "type=$transitionType " +
                    "requestId=$requestId " +
                    "parsedTerminalId=$terminalId " +
                    "currentStatus=$currentStatus " +
                    "currentTerminalId=$currentTerminalId"
        )

        if (terminalId == null) {

            Log.e(
                TAG,
                "Could not parse terminalId from requestId=$requestId"
            )

            return
        }

        when (transitionType) {

            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                onEnterTerminal(terminalId)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                onExitTerminal(terminalId)
            }
        }
    }


    // ================================================================
    // SIMULATION
    // ================================================================

    fun simulateEnterTerminal() {
        onEnterTerminal(currentTerminalId)
    }

    fun simulateExitTerminal() {
        onExitTerminal(currentTerminalId)
    }

    fun simulateWaitingTimeout() {
        supabase.skipWaitingJeepney(jeepneyId) { }
    }

    fun simulateLoadingComplete() {

        if (
            currentStatus == "loading" ||
            isLoadingSlotActive
        ) {
            releaseQueueSlot()
        }
    }

    fun getCurrentStatus(): String =
        currentStatus

    fun getCurrentTerminal(): String =
        terminalName(currentTerminalId)

    fun getTerminalId(): Int =
        currentTerminalId


    // ================================================================
    // ENTER TERMINAL
    // ================================================================

    private fun onEnterTerminal(
        enteredTerminalId: Int
    ) {

        Log.d(
            TAG,
            "onEnterTerminal($enteredTerminalId) " +
                    "isDataLoaded=$isDataLoaded " +
                    "currentStatus=$currentStatus " +
                    "currentTerminalId=$currentTerminalId"
        )

        isInsideTerminal = true

        if (
            enteredTerminalId == currentTerminalId &&
            (
                    currentStatus == "loading" ||
                            currentStatus == "waiting"
                    )
        ) {

            Log.d(
                TAG,
                "Already $currentStatus at this terminal; " +
                        "not resetting to arrived"
            )

            return
        }

        currentTerminalId = enteredTerminalId

        if (!isDataLoaded) {

            Log.w(
                TAG,
                "Jeep data not loaded — retrying in 1 second"
            )

            handler.postDelayed(
                {
                    onEnterTerminal(enteredTerminalId)
                },
                1000
            )

            return
        }

        currentStatus = "arrived"

        supabase.updateStatus("arrived") { }

        onStatusChanged("arrived")

        // Current centralized SMS flow.
        smsService.notifyArrival(
            jeepneyId = jeepneyId,
            plateNumber = cachedPlateNumber,
            jeepName = cachedJeepName,
            driverName = cachedDriverName,
            terminalName = terminalName(enteredTerminalId),
            terminalId = enteredTerminalId,
            bracket = bracket
        )

        // In-app staff notification.
        supabase.insertNotificationAdmin(
            title = "Jeepney Arrived",
            message =
                "$cachedJeepName ($cachedPlateNumber) " +
                        "arrived at ${terminalName(enteredTerminalId)}.",
            type = "arrival"
        ) { success ->

            Log.d(
                TAG,
                "notification insert (arrival) success=$success"
            )
        }

        arrivalRunnable?.let {
            handler.removeCallbacks(it)
        }

        arrivalRunnable =
            Runnable {
                finalizeArrival(enteredTerminalId)
            }

        handler.postDelayed(
            arrivalRunnable!!,
            ARRIVAL_GRACE_MINUTES * 60_000
        )
    }


    // ================================================================
    // FINALIZE ARRIVAL / JOIN QUEUE
    // ================================================================

    private fun finalizeArrival(
        terminalId: Int
    ) {

        Log.d(
            TAG,
            "finalizeArrival($terminalId) " +
                    "isInsideTerminal=$isInsideTerminal " +
                    "currentStatus=$currentStatus"
        )

        if (
            !isInsideTerminal ||
            (
                    currentStatus != "arrived" &&
                            currentStatus != "waiting"
                    )
        ) {

            Log.w(
                TAG,
                "finalizeArrival aborted — " +
                        "jeep left terminal or status changed"
            )

            return
        }

        supabase.addToQueueWithBracketAndTerminal(
            jeepneyId = jeepneyId,
            bracket = bracket,
            terminalId = terminalId
        ) { position, status ->

            Log.d(
                TAG,
                "Queue RPC result: " +
                        "position=$position status=$status"
            )

            currentStatus = status

            onStatusChanged(status)

            when (status) {

                "loading" -> {

                    loadingStartTime =
                        System.currentTimeMillis()

                    alertSentAt25 = false

                    isLoadingSlotActive = true

                    smsService.notifyLoadingStarted(
                        jeepneyId = jeepneyId,
                        plateNumber = cachedPlateNumber,
                        jeepName = cachedJeepName,
                        driverName = cachedDriverName,
                        terminalName = terminalName(terminalId),
                        terminalId = terminalId,
                        bracket = bracket
                    )

                    supabase.insertNotificationAdmin(
                        title = "Loading Started",
                        message =
                            "$cachedJeepName ($cachedPlateNumber) " +
                                    "is now loading at " +
                                    "${terminalName(terminalId)}.",
                        type = "status"
                    ) { success ->

                        Log.d(
                            TAG,
                            "notification insert (loading started) " +
                                    "success=$success"
                        )
                    }

                    startLoadingCheck()

                    LoadingCheckWorker.schedule(context)
                }

                "waiting" -> {
                    startWaitingTimer()
                }
            }
        }
    }


    // ================================================================
    // EXIT TERMINAL
    // ================================================================

    private fun onExitTerminal(
        exitedTerminalId: Int
    ) {

        Log.d(
            TAG,
            "onExitTerminal($exitedTerminalId) " +
                    "currentTerminalId=$currentTerminalId " +
                    "currentStatus=$currentStatus " +
                    "isLoadingSlotActive=$isLoadingSlotActive"
        )

        if (
            exitedTerminalId != currentTerminalId
        ) {

            Log.w(
                TAG,
                "Exit ignored — terminal does not match"
            )

            return
        }

        isInsideTerminal = false

        when (currentStatus) {

            // --------------------------------------------------------
            // LOADING -> DEPARTED
            // --------------------------------------------------------

            "loading" -> {

                isLoadingSlotActive = false

                stopLoadingCheck()

                LoadingCheckWorker.cancel(context)

                currentStatus = "en_route"

                onStatusChanged("en_route")

                val route =
                    if (exitedTerminalId == 1) {
                        "Donsol → Daraga"
                    } else {
                        "Daraga → Donsol"
                    }

                supabase.departJeepney(
                    jeepneyId,
                    route
                ) { success ->

                    Log.d(
                        TAG,
                        "departJeepney (on exit) success=$success"
                    )
                }

                // Centralized departure SMS.
                // Sender selection is handled inside SMSService.
                smsService.notifyDeparture(
                    jeepneyId = jeepneyId,
                    plateNumber = cachedPlateNumber,
                    jeepName = cachedJeepName,
                    driverName = cachedDriverName,
                    terminalName = terminalName(exitedTerminalId),
                    terminalId = exitedTerminalId,
                    bracket = bracket
                )

                supabase.insertNotificationAdmin(
                    title = "Jeepney Departed",
                    message =
                        "$cachedJeepName ($cachedPlateNumber) " +
                                "departed from " +
                                "${terminalName(exitedTerminalId)} — $route.",
                    type = "dispatch"
                ) { success ->

                    Log.d(
                        TAG,
                        "notification insert (departure) success=$success"
                    )
                }
            }


            // --------------------------------------------------------
            // ARRIVED -> EN ROUTE
            // --------------------------------------------------------

            "arrived" -> {

                arrivalRunnable?.let {
                    handler.removeCallbacks(it)
                }

                currentStatus = "en_route"

                onStatusChanged("en_route")

                supabase.updateStatus("en_route") { }
            }


            // --------------------------------------------------------
            // WAITING -> LEAVE QUEUE
            // --------------------------------------------------------

            "waiting" -> {

                supabase.leaveQueue(
                    jeepneyId
                ) { success ->

                    Log.d(
                        TAG,
                        "leaveQueue (exited while waiting) " +
                                "success=$success"
                    )
                }

                currentStatus = "en_route"

                onStatusChanged("en_route")

                cancelAllTimers()
            }


            else -> {

                Log.d(
                    TAG,
                    "Exit ignored for status=$currentStatus"
                )
            }
        }
    }


    // ================================================================
    // LOADING PERIODIC CHECK
    // ================================================================

    private fun startLoadingCheck() {

        stopLoadingCheck()

        loadingCheckRunnable =
            object : Runnable {

                override fun run() {

                    checkLoadingStatus()

                    if (isLoadingSlotActive) {

                        handler.postDelayed(
                            this,
                            CHECK_INTERVAL * 1000
                        )
                    }
                }
            }

        handler.post(
            loadingCheckRunnable!!
        )
    }

    private fun stopLoadingCheck() {

        loadingCheckRunnable?.let {
            handler.removeCallbacks(it)
        }

        loadingCheckRunnable = null
    }

    private fun checkLoadingStatus() {

        if (!isLoadingSlotActive) {

            Log.d(
                TAG,
                "Loading slot inactive; stopping checks"
            )

            stopLoadingCheck()

            return
        }

        val elapsedMinutes =
            (
                    System.currentTimeMillis() -
                            loadingStartTime
                    ) / 60000

        Log.d(
            TAG,
            "checkLoadingStatus " +
                    "elapsedMinutes=$elapsedMinutes " +
                    "isInsideTerminal=$isInsideTerminal " +
                    "currentStatus=$currentStatus"
        )

        // Heads-up alert.
        if (
            elapsedMinutes >= ALERT_THRESHOLD &&
            !alertSentAt25
        ) {

            alertSentAt25 = true

            smsService.notifyLoadingAlert(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName(currentTerminalId),
                minutes = ALERT_THRESHOLD,
                terminalId = currentTerminalId,
                bracket = bracket
            )

            supabase.insertNotificationAdmin(
                title = "Loading Time Alert",
                message =
                    "$cachedJeepName ($cachedPlateNumber) " +
                            "has been loading for " +
                            "$ALERT_THRESHOLD min at " +
                            "${terminalName(currentTerminalId)}.",
                type = "status"
            ) { success ->

                Log.d(
                    TAG,
                    "notification insert (loading alert) " +
                            "success=$success"
                )
            }
        }

        // Safety net. Normal departure is handled by geofence EXIT.
        if (
            elapsedMinutes >= LOADING_DURATION &&
            !isInsideTerminal
        ) {
            releaseQueueSlot()
        }
    }


    // ================================================================
    // RELEASE QUEUE SLOT
    // ================================================================

    private fun releaseQueueSlot() {

        Log.d(
            TAG,
            "releaseQueueSlot() firing — " +
                    "jeepneyId=$jeepneyId " +
                    "terminalId=$currentTerminalId"
        )

        isLoadingSlotActive = false

        stopLoadingCheck()

        LoadingCheckWorker.cancel(context)

        val route =
            if (currentTerminalId == 1) {
                "Donsol → Daraga"
            } else {
                "Daraga → Donsol"
            }

        supabase.departJeepney(
            jeepneyId,
            route
        ) { success ->

            Log.d(
                TAG,
                "departJeepney RPC (safety-net release) " +
                        "success=$success"
            )
        }
    }


    // ================================================================
    // WAITING TIMER
    // ================================================================

    private fun startWaitingTimer() {

        waitingRunnable?.let {
            handler.removeCallbacks(it)
        }

        waitingRunnable =
            Runnable {

                supabase.getNextInQueue(
                    currentTerminalId,
                    bracket
                ) { nextJeepney ->

                    if (
                        nextJeepney != null &&
                        nextJeepney.id != jeepneyId
                    ) {

                        val newPosition =
                            nextJeepney.queuePosition ?: 1

                        supabase.insertNotificationAdmin(
                            title = "Queue Update",
                            message =
                                "$cachedJeepName ($cachedPlateNumber) " +
                                        "is now #$newPosition in queue at " +
                                        "${terminalName(currentTerminalId)}.",
                            type = "queue"
                        ) { success ->

                            Log.d(
                                TAG,
                                "notification insert (queue update) " +
                                        "success=$success"
                            )
                        }

                        startWaitingTimer()

                    } else {

                        finalizeArrival(
                            currentTerminalId
                        )
                    }
                }
            }

        handler.postDelayed(
            waitingRunnable!!,
            WAITING_TIMEOUT * 60_000
        )
    }


    // ================================================================
    // HELPERS
    // ================================================================

    private fun cancelAllTimers() {

        stopLoadingCheck()

        LoadingCheckWorker.cancel(context)

        arrivalRunnable?.let {
            handler.removeCallbacks(it)
        }

        waitingRunnable?.let {
            handler.removeCallbacks(it)
        }

        handler.removeCallbacksAndMessages(null)
    }

    private fun terminalName(
        id: Int
    ): String =
        TERMINAL_NAMES[id] ?: "Terminal $id"

    private fun hasLocationPermission(): Boolean {

        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
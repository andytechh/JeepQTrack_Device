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
import com.surendramaran.Jeepqs.workers.ArrivalCheckWorker
import com.surendramaran.Jeepqs.workers.LoadingCheckWorker

/**
 * Manages both terminal geofences:
 * Donsol <-> Daraga.
 *
 * Terminal coordinates + radius are NOT hardcoded here anymore. They come from
 * TerminalRepository (Supabase `terminals` -> local cache -> built-in defaults),
 * and the geofences are re-registered whenever the admin changes them.
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

        // Computed from TerminalRepository so any other file reading these still compiles.
        val TERMINALS: Map<Int, Pair<Double, Double>>
            get() = TerminalRepository.all().mapValues { Pair(it.value.lat, it.value.lng) }

        val TERMINAL_NAMES: Map<Int, String>
            get() = TerminalRepository.all().mapValues { it.value.name }

        fun radiusFor(terminalId: Int): Double =
            (TerminalRepository.get(terminalId)?.radiusM ?: 100f).toDouble()

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
            if (DEBUG_TESTING_MODE) 1L else 1L

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

    // Called by TerminalRepository (main thread) when the admin changes a terminal.
    private val terminalListener: (Map<Int, TerminalRepository.Terminal>) -> Unit = {
        Log.i(TAG, "Terminal config changed -> re-registering geofences")
        reloadGeofences()
    }


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

            "arrived" -> {
                // Process may have been restarted while this jeep was
                // sitting in "arrived" — the in-memory arrivalRunnable
                // from before is gone. Re-arm the durable watchdog so a
                // stranded arrival still gets picked up even without a
                // fresh geofence ENTER event.
                ArrivalCheckWorker.schedule(context)
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

        // cache -> immediate fetch -> poll every 60s; listener fires only on real changes.
        TerminalRepository.addListener(terminalListener)
        TerminalRepository.start(context, supabase)

        registerGeofences()
    }

    private fun registerGeofences() {

        if (!hasLocationPermission()) return

        val geofences = TerminalRepository.all().values.map { t ->

            Geofence.Builder()
                .setRequestId(requestIdForTerminal(t.id))
                .setCircularRegion(
                    t.lat,
                    t.lng,
                    t.radiusM
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
                // If the phone is ALREADY inside a (new) geofence, fire ENTER immediately.
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
                    "Geofences registered: ${TerminalRepository.all().values}"
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

    /**
     * Terminal config changed: drop the old circles and register the new ones.
     */
    private fun reloadGeofences() {

        // Not registered yet: startGeofence()/registerGeofences() will read the latest values.
        if (!isGeofenceRegistered) return

        // The old circles are gone, so an EXIT will never arrive for them.
        // INITIAL_TRIGGER_ENTER sets this back to true if we're inside a new circle.
        isInsideTerminal = false

        geofencingClient
            .removeGeofences(GeofencePendingIntent.getPendingIntent(context))
            .addOnCompleteListener { registerGeofences() }
    }

    fun stopGeofence() {

        TerminalRepository.removeListener(terminalListener)
        TerminalRepository.stop()

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

        // Stamps arrived_at fresh (not just status), so the durable
        // ArrivalCheckWorker watchdog below has a real per-lap timestamp
        // to check against — see SupabaseService.setArrived().
        supabase.setArrived { }

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

        // Durable backup for the Handler timer above. If the app process
        // dies or finalizeArrival() silently aborts (isInsideTerminal
        // flipped false from GPS jitter, etc.), this worker re-derives
        // state from Supabase and forces the queue join once the grace
        // period has genuinely elapsed — mirroring LoadingCheckWorker's
        // role as a backup for the "loading" phase's Handler timers.
        ArrivalCheckWorker.schedule(
            context,
            initialDelaySeconds = ARRIVAL_GRACE_MINUTES * 60
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

            // Deliberately NOT cancelling ArrivalCheckWorker here — if this
            // abort was caused by a spurious GPS blip while the jeep is
            // genuinely still at the terminal in Supabase's eyes, the
            // worker is the safety net that will still pick it back up.

            return
        }

        supabase.addToQueueWithBracketAndTerminal(
            jeepneyId = jeepneyId,
            bracket = bracket,
            terminalId = terminalId
        ) { position, status ->

            // The queue join succeeded via the in-memory path — the
            // durable watchdog scheduled in onEnterTerminal() is no
            // longer needed for this arrival.
            ArrivalCheckWorker.cancel(context)

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

                // The jeep genuinely left before ever joining the queue —
                // the watchdog scheduled in onEnterTerminal() should not
                // fire and try to queue it after the fact.
                ArrivalCheckWorker.cancel(context)

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

        ArrivalCheckWorker.cancel(context)

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
        TerminalRepository.name(id)

    private fun hasLocationPermission(): Boolean {

        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
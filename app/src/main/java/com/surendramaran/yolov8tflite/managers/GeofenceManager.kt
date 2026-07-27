package com.surendramaran.Jeepqs.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.surendramaran.Jeepqs.services.SMSService
import com.surendramaran.Jeepqs.services.SupabaseService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GeofenceManager(
    private val context: Context,
    private val supabase: SupabaseService,
    private val jeepneyId: String,
    private val bracket: Int,
    private val terminalId: Int, // 1 = Donsol, 2 = Daraga
    private val onStatusChanged: (String) -> Unit
) {

    companion object {
        private const val TAG = "GeofenceManager"

        // Terminal locations
        private val TERMINALS = mapOf(
            1 to Pair(12.9032, 123.59425),  // Donsol Terminal
            2 to Pair(13.14769, 123.71216)   // Daraga Terminal
        )

        private val TERMINAL_NAMES = mapOf(
            1 to "Donsol Terminal",
            2 to "Daraga Terminal"
        )

        private const val GEOFENCE_RADIUS = 200.0 // meters
        private const val LOADING_DURATION = 30L // minutes
        private const val WAITING_TIMEOUT = 5L // minutes
    }

    private val geofencingClient = LocationServices.getGeofencingClient(context)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var isGeofenceRegistered = false
    private var currentStatus = "inactive"
    private var isDataLoaded = false

    // FIXED: Pass supabase to SMSService
    private val smsService = SMSService(context, supabase)

    private var cachedPlateNumber: String = "UNKNOWN"
    private var cachedJeepName: String = ""
    private var cachedDriverName: String = "Driver"
    private var cachedOccupancy: Int = 0

    // Get terminal location
    private val terminalLatLng = TERMINALS[terminalId] ?: Pair(12.9032, 123.59425)
    private val terminalName = TERMINAL_NAMES[terminalId] ?: "Terminal $terminalId"

    private fun getPlateNumber(): String = cachedPlateNumber
    private fun getJeepName(): String = cachedJeepName
    private fun getDriverName(): String = cachedDriverName
    private fun getOccupancy(): Int = cachedOccupancy

    fun updateJeepneyData(plate: String, jeepName: String, driver: String, occupancy: Int) {
        cachedPlateNumber = plate
        cachedJeepName = jeepName
        cachedDriverName = driver
        cachedOccupancy = occupancy
        isDataLoaded = true
        Log.d(TAG, "✅ Jeepney data updated for $terminalName: $jeepName ($plate)")
    }

    fun updateOccupancy(occupancy: Int) {
        cachedOccupancy = occupancy
        Log.d(TAG, "✅ Occupancy updated: $occupancy")

        // Check if occupancy exceeds 80% and send alert
        if (occupancy >= 20) { // 20 out of 24 = 83%
            smsService.notifyOccupancyAlert(
                jeepneyId = jeepneyId,
                plateNumber = getPlateNumber(),
                jeepName = getJeepName(),
                driverName = getDriverName(),
                terminalName = terminalName,
                occupancy = occupancy,
                capacity = 24,
                terminalId = terminalId,
                bracket = bracket
            )
        }
    }

    fun startGeofence() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ No location permission")
            return
        }

        val (lat, lng) = terminalLatLng
        Log.d(TAG, "📍 Starting geofence for $terminalName at ($lat, $lng)")

        val geofence = Geofence.Builder()
            .setRequestId("terminal_${terminalId}_geofence")
            .setCircularRegion(lat, lng, GEOFENCE_RADIUS.toFloat())
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = GeofencePendingIntent.getPendingIntent(context)

        geofencingClient.addGeofences(geofencingRequest, pendingIntent)
            .addOnSuccessListener {
                isGeofenceRegistered = true
                Log.d(TAG, "✅ Geofence registered for $terminalName")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Geofence failed for $terminalName: ${e.message}")
            }
    }

    fun stopGeofence() {
        if (!isGeofenceRegistered) return
        val pendingIntent = GeofencePendingIntent.getPendingIntent(context)
        geofencingClient.removeGeofences(pendingIntent)
        isGeofenceRegistered = false
        cancelAllTimers()
        Log.d(TAG, "🛑 Geofence stopped for $terminalName")
    }

    // ─── SIMULATE FOR TESTING ──────────────────────────────────────
    fun simulateEnterTerminal() {
        Log.d(TAG, "🔧 MANUAL: Simulating ENTER $terminalName")
        onEnterTerminal()
    }

    fun simulateExitTerminal() {
        Log.d(TAG, "🔧 MANUAL: Simulating EXIT $terminalName")
        onExitTerminal()
    }

    fun simulateWaitingTimeout() {
        Log.d(TAG, "🔧 MANUAL: Simulating WAITING TIMEOUT")
        supabase.skipWaitingJeepney(jeepneyId) { success ->
            Log.d(TAG, "Skip result: $success")
        }
    }

    fun simulateLoadingComplete() {
        Log.d(TAG, "🔧 MANUAL: Simulating LOADING COMPLETE")
        onStatusChanged("loading_complete")
    }

    fun getCurrentStatus(): String = currentStatus
    fun getCurrentTerminal(): String = terminalName
    fun getTerminalId(): Int = terminalId

    // ─── ENTER TERMINAL ─────────────────────────────────────────────
    private fun onEnterTerminal() {
        Log.d(TAG, "🚪 Jeepney entered $terminalName - Bracket: $bracket")

        if (!isDataLoaded) {
            Log.d(TAG, "⏳ Data not loaded yet, retrying...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onEnterTerminal()
            }, 1000)
            return
        }

        Log.d(TAG, "📊 CACHED DATA:")
        Log.d(TAG, "   Plate: $cachedPlateNumber")
        Log.d(TAG, "   JeepName: $cachedJeepName")
        Log.d(TAG, "   Driver: $cachedDriverName")
        Log.d(TAG, "   Occupancy: $cachedOccupancy")
        Log.d(TAG, "   Terminal: $terminalName (ID: $terminalId)")

        // Add to queue with terminal
        supabase.addToQueueWithBracketAndTerminal(
            jeepneyId = jeepneyId,
            bracket = bracket,
            terminalId = terminalId
        ) { position, status ->
            currentStatus = status
            Log.d(TAG, "📋 Queue: Position $position, Status: $status")

            // ─── NOTIFY ALL USERS ──────────────────────────────────────
            // Notify all commuters and dispatchers about arrival
            smsService.notifyArrival(
                jeepneyId = jeepneyId,
                plateNumber = getPlateNumber(),
                jeepName = getJeepName(),
                driverName = getDriverName(),
                terminalName = terminalName,
                terminalId = terminalId,
                bracket = bracket
            )

            // Also send direct SMS to dispatcher
            val dispatcherPhone = "+639918139617"
            smsService.sendArrivalNotification(
                jeepneyId = jeepneyId,
                plateNumber = getPlateNumber(),
                jeepName = getJeepName(),
                driverName = getDriverName(),
                queuePosition = position,
                terminalName = terminalName,
                phoneNumber = dispatcherPhone
            )

            when (status) {
                "loading" -> startLoadingTimer()
                "waiting" -> startWaitingTimer()
            }
            onStatusChanged(status)
        }
    }

    // ─── EXIT TERMINAL ─────────────────────────────────────────────
    private fun onExitTerminal() {
        Log.d(TAG, "🚪 Jeepney exited $terminalName")

        supabase.removeFromQueue(jeepneyId) { success ->
            if (success) {
                Log.d(TAG, "✅ Removed from queue")
                currentStatus = "en_route"
                onStatusChanged("en_route")

                // ─── NOTIFY ALL USERS ──────────────────────────────────
                smsService.notifyDeparture(
                    jeepneyId = jeepneyId,
                    plateNumber = getPlateNumber(),
                    jeepName = getJeepName(),
                    driverName = getDriverName(),
                    terminalName = terminalName,
                    terminalId = terminalId,
                    bracket = bracket
                )

                // Also send direct SMS to dispatcher
                val dispatcherPhone = "+639918139617"
                smsService.sendDispatchConfirmation(
                    jeepneyId = jeepneyId,
                    plateNumber = getPlateNumber(),
                    jeepName = getJeepName(),
                    driverName = getDriverName(),
                    occupancy = getOccupancy(),
                    terminalName = terminalName,
                    phoneNumber = dispatcherPhone
                )
            }
        }
        cancelAllTimers()
    }

    // ─── TIMERS ─────────────────────────────────────────────────────
    private fun startLoadingTimer() {
        Log.d(TAG, "⏱️ Loading timer: $LOADING_DURATION minutes at $terminalName")

        supabase.updateStatus("loading") { success ->
            Log.d(TAG, "Status updated to loading: $success")
            if (success) {
                // ─── NOTIFY ALL USERS ──────────────────────────────────
                smsService.notifyLoadingStarted(
                    jeepneyId = jeepneyId,
                    plateNumber = getPlateNumber(),
                    jeepName = getJeepName(),
                    driverName = getDriverName(),
                    terminalName = terminalName,
                    terminalId = terminalId,
                    bracket = bracket
                )

                // Also send direct SMS to dispatcher
                smsService.sendLoadingNotification(
                    jeepneyId = jeepneyId,
                    plateNumber = getPlateNumber(),
                    jeepName = getJeepName(),
                    driverName = getDriverName(),
                    terminalName = terminalName,
                    duration = LOADING_DURATION,
                    phoneNumber = "+639918139617"
                )
            }
        }

        scheduler.schedule({
            Log.d(TAG, "⏰ Loading complete at $terminalName! Ready to depart.")

            // ─── NOTIFY ALL USERS ──────────────────────────────────
            smsService.notifyLoadingComplete(
                jeepneyId = jeepneyId,
                plateNumber = getPlateNumber(),
                jeepName = getJeepName(),
                driverName = getDriverName(),
                terminalName = terminalName,
                occupancy = getOccupancy(),
                terminalId = terminalId,
                bracket = bracket
            )

            // Also send direct SMS to dispatcher
            smsService.sendDepartureNotification(
                jeepneyId = jeepneyId,
                plateNumber = getPlateNumber(),
                jeepName = getJeepName(),
                driverName = getDriverName(),
                terminalName = terminalName,
                phoneNumber = "+639918139617"
            )

            onStatusChanged("loading_complete")
        }, LOADING_DURATION, TimeUnit.MINUTES)
    }

    private fun startWaitingTimer() {
        Log.d(TAG, "⏱️ Waiting timer: $WAITING_TIMEOUT minutes at $terminalName")

        supabase.updateStatus("waiting") { success ->
            Log.d(TAG, "Status updated to waiting: $success")
        }

        scheduler.schedule({
            Log.d(TAG, "⏰ Waiting timeout at $terminalName! Checking next in queue")

            // Check if there's a higher priority jeepney
            supabase.getNextInQueue(terminalId, bracket) { nextJeepney ->
                if (nextJeepney != null && nextJeepney.id != jeepneyId) {
                    Log.d(TAG, "🔄 Higher priority jeepney found: ${nextJeepney.plateNumber}")

                    // Notify driver about queue position change
                    smsService.notifyQueueUpdate(
                        jeepneyId = jeepneyId,
                        plateNumber = getPlateNumber(),
                        jeepName = getJeepName(),
                        driverName = getDriverName(),
                        terminalName = terminalName,
                        newPosition = nextJeepney.queuePosition ?: 1,
                        terminalId = terminalId,
                        bracket = bracket
                    )

                    // Move to waiting and let higher priority go first
                    supabase.skipWaitingJeepney(jeepneyId) { success ->
                        Log.d(TAG, "Skip: $success")
                        // Re-enter queue at the back
                        supabase.addToQueueWithBracketAndTerminal(
                            jeepneyId = jeepneyId,
                            bracket = bracket,
                            terminalId = terminalId
                        ) { position, status ->
                            Log.d(TAG, "Re-queued at position: $position")
                            currentStatus = status
                            if (status == "waiting") {
                                startWaitingTimer()
                            }
                        }
                    }
                } else {
                    // Still first in queue, keep waiting
                    startWaitingTimer()
                }
            }
        }, WAITING_TIMEOUT, TimeUnit.MINUTES)
    }

    private fun cancelAllTimers() {
        scheduler.shutdownNow()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
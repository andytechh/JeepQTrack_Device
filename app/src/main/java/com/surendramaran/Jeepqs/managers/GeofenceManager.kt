package com.surendramaran.Jeepqs.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    private val terminalId: Int,
    private val onStatusChanged: (String) -> Unit
) {

    companion object {
        private val TERMINALS = mapOf(
            1 to Pair(12.9032, 123.59425),
            2 to Pair(13.14769, 123.71216)
        )

        private val TERMINAL_NAMES = mapOf(
            1 to "Donsol Terminal",
            2 to "Daraga Terminal"
        )

        private const val GEOFENCE_RADIUS = 200.0
        private const val LOADING_DURATION = 30L
        private const val WAITING_TIMEOUT = 5L
    }

    private val geofencingClient = LocationServices.getGeofencingClient(context)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val smsService = SMSService(context, supabase)

    private var isGeofenceRegistered = false
    private var currentStatus = "inactive"
    private var isDataLoaded = false

    private var cachedPlateNumber = "UNKNOWN"
    private var cachedJeepName = ""
    private var cachedDriverName = "Driver"
    private var cachedOccupancy = 0

    private val terminalLatLng = TERMINALS[terminalId] ?: Pair(12.9032, 123.59425)
    private val terminalName = TERMINAL_NAMES[terminalId] ?: "Terminal $terminalId"

    fun updateJeepneyData(plate: String, jeepName: String, driver: String, occupancy: Int) {
        cachedPlateNumber = plate
        cachedJeepName = jeepName
        cachedDriverName = driver
        cachedOccupancy = occupancy
        isDataLoaded = true
    }

    fun updateOccupancy(occupancy: Int) {
        cachedOccupancy = occupancy
        if (occupancy >= 20) {
            smsService.notifyOccupancyAlert(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName,
                occupancy = occupancy,
                capacity = 24,
                terminalId = terminalId,
                bracket = bracket
            )
        }
    }

    fun startGeofence() {
        if (!hasLocationPermission()) return

        val (lat, lng) = terminalLatLng

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
            }
            .addOnFailureListener {
                isGeofenceRegistered = false
            }
    }

    fun stopGeofence() {
        if (!isGeofenceRegistered) return
        val pendingIntent = GeofencePendingIntent.getPendingIntent(context)
        geofencingClient.removeGeofences(pendingIntent)
        isGeofenceRegistered = false
        cancelAllTimers()
    }

    // ─── SIMULATION METHODS ──────────────────────────────────────────

    fun simulateEnterTerminal() {
        onEnterTerminal()
    }

    fun simulateExitTerminal() {
        onExitTerminal()
    }

    fun simulateWaitingTimeout() {
        supabase.skipWaitingJeepney(jeepneyId) { }
    }

    fun simulateLoadingComplete() {
        onStatusChanged("loading_complete")
    }

    fun getCurrentStatus(): String = currentStatus
    fun getCurrentTerminal(): String = terminalName
    fun getTerminalId(): Int = terminalId

    // ─── ENTER TERMINAL ─────────────────────────────────────────────

    private fun onEnterTerminal() {
        if (!isDataLoaded) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onEnterTerminal()
            }, 1000)
            return
        }

        supabase.addToQueueWithBracketAndTerminal(
            jeepneyId = jeepneyId,
            bracket = bracket,
            terminalId = terminalId
        ) { position, status ->
            currentStatus = status

            smsService.notifyArrival(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName,
                terminalId = terminalId,
                bracket = bracket
            )

            val dispatcherPhone = "+639244508563"
            smsService.sendArrivalNotification(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                queuePosition = position,
                terminalName = terminalName,
                phoneNumber = dispatcherPhone
            )

            supabase.getDriverIdFromJeepney(jeepneyId) { driverId ->
                if (driverId != null) {
                    supabase.getUserById(driverId) { user ->
                        if (user != null) {
                            smsService.notifySingleUser(
                                userId = user.optString("id"),
                                phoneNumber = user.optString("phone_number"),
                                expoToken = user.optString("expo_push_token"),
                                displayName = user.optString("display_name", "Driver"),
                                jeepneyId = jeepneyId,
                                plateNumber = cachedPlateNumber,
                                jeepName = cachedJeepName,
                                driverName = cachedDriverName,
                                terminalName = terminalName,
                                eventType = "arrival"
                            )
                        }
                    }
                }
            }

            when (status) {
                "loading" -> startLoadingTimer()
                "waiting" -> startWaitingTimer()
            }
            onStatusChanged(status)
        }
    }

    // ─── EXIT TERMINAL ─────────────────────────────────────────────

    private fun onExitTerminal() {
        supabase.removeFromQueue(jeepneyId) { success ->
            if (success) {
                currentStatus = "en_route"
                onStatusChanged("en_route")

                smsService.notifyDeparture(
                    jeepneyId = jeepneyId,
                    plateNumber = cachedPlateNumber,
                    jeepName = cachedJeepName,
                    driverName = cachedDriverName,
                    terminalName = terminalName,
                    terminalId = terminalId,
                    bracket = bracket
                )

                val dispatcherPhone = "+639244508563"
                smsService.sendDispatchConfirmation(
                    jeepneyId = jeepneyId,
                    plateNumber = cachedPlateNumber,
                    jeepName = cachedJeepName,
                    driverName = cachedDriverName,
                    occupancy = cachedOccupancy,
                    terminalName = terminalName,
                    phoneNumber = dispatcherPhone
                )
            }
        }
        cancelAllTimers()
    }

    // ─── TIMERS ─────────────────────────────────────────────────────

    private fun startLoadingTimer() {
        supabase.updateStatus("loading") { success ->
            if (success) {
                smsService.notifyLoadingStarted(
                    jeepneyId = jeepneyId,
                    plateNumber = cachedPlateNumber,
                    jeepName = cachedJeepName,
                    driverName = cachedDriverName,
                    terminalName = terminalName,
                    terminalId = terminalId,
                    bracket = bracket
                )

                smsService.sendLoadingNotification(
                    jeepneyId = jeepneyId,
                    plateNumber = cachedPlateNumber,
                    jeepName = cachedJeepName,
                    driverName = cachedDriverName,
                    terminalName = terminalName,
                    duration = LOADING_DURATION,
                    phoneNumber = "+639244508563"
                )
            }
        }

        scheduler.schedule({
            smsService.notifyLoadingComplete(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName,
                occupancy = cachedOccupancy,
                terminalId = terminalId,
                bracket = bracket
            )

            smsService.sendDepartureNotification(
                jeepneyId = jeepneyId,
                plateNumber = cachedPlateNumber,
                jeepName = cachedJeepName,
                driverName = cachedDriverName,
                terminalName = terminalName,
                phoneNumber = "+639244508563"
            )

            onStatusChanged("loading_complete")
        }, LOADING_DURATION, TimeUnit.MINUTES)
    }

    private fun startWaitingTimer() {
        supabase.updateStatus("waiting") { }

        scheduler.schedule({
            supabase.getNextInQueue(terminalId, bracket) { nextJeepney ->
                if (nextJeepney != null && nextJeepney.id != jeepneyId) {
                    smsService.sendQueueUpdate(
                        jeepneyId = jeepneyId,
                        plateNumber = cachedPlateNumber,
                        jeepName = cachedJeepName,
                        driverName = cachedDriverName,
                        terminalName = terminalName,
                        newPosition = nextJeepney.queuePosition ?: 1,
                        phoneNumber = "+639244508563"
                    )

                    supabase.skipWaitingJeepney(jeepneyId) { success ->
                        if (success) {
                            supabase.addToQueueWithBracketAndTerminal(
                                jeepneyId = jeepneyId,
                                bracket = bracket,
                                terminalId = terminalId
                            ) { position, status ->
                                currentStatus = status
                                if (status == "waiting") {
                                    startWaitingTimer()
                                }
                            }
                        }
                    }
                } else {
                    startWaitingTimer()
                }
            }
        }, WAITING_TIMEOUT, TimeUnit.MINUTES)
    }

    // ─── HELPERS ─────────────────────────────────────────────────────

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
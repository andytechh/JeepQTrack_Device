// app/src/main/java/com/surendramaran/Jeepqs/workers/LoadingCheckWorker.kt
package com.surendramaran.Jeepqs.workers

import android.content.Context
import android.location.Location
import androidx.work.*
import com.surendramaran.Jeepqs.managers.GeofenceManager
import com.surendramaran.Jeepqs.services.SMSService
import com.surendramaran.Jeepqs.services.SupabaseService
import com.surendramaran.Jeepqs.settings.DeviceConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Backup for GeofenceManager's in-memory Handler timers.
 *
 * If the app process dies while a jeepney is "loading", the Handler-based
 * checks in GeofenceManager die with it. This worker re-derives state from
 * Supabase (status + loading_started_at + last GPS fix) every minute and:
 *   - sends the pre-30-min SMS alert once
 *   - departs the jeepney at the 30-min mark IF it's no longer near the terminal
 *
 * It reschedules itself (one-shot + re-enqueue) rather than using
 * PeriodicWorkRequest, because Android enforces a 15-min minimum interval
 * for periodic work, which is too coarse for a 30-min window with a 5-min
 * warning built in.
 */
class LoadingCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "loading_check_worker"
        private const val PREFS = "loading_check_prefs"
        private const val KEY_ALERT_SENT = "alert_sent_for_start_"

        /** Call this once loading starts (and again on app boot if status is still "loading"). */
        fun schedule(context: Context, initialDelaySeconds: Long = GeofenceManager.CHECK_INTERVAL) {
            val request = OneTimeWorkRequestBuilder<LoadingCheckWorker>()
                .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    override fun doWork(): Result {
        DeviceConfig.init(applicationContext) // safe to call repeatedly; no-op if already initialized
        val jeepneyId = DeviceConfig.getJeepId() ?: return Result.success()
        val supabase = SupabaseService(applicationContext)

        val status = supabase.getCurrentStatusBlocking(jeepneyId)
        if (status != "loading") {
            // Nothing to enforce right now; GeofenceManager (if alive) owns the rest of the lifecycle.
            return Result.success()
        }

        val startedAt = supabase.getLoadingStartedAtBlocking(jeepneyId)
            ?: return rescheduleAndSucceed()

        val elapsedMinutes = (System.currentTimeMillis() - startedAt) / 60000
        val terminalId = supabase.getTerminalIdBlocking(jeepneyId) ?: 1
        val isInside = isNearTerminal(supabase, jeepneyId, terminalId)

        if (elapsedMinutes >= GeofenceManager.ALERT_THRESHOLD &&
            elapsedMinutes < GeofenceManager.LOADING_DURATION &&
            !alertAlreadySent(startedAt)
        ) {
            sendAlertBlocking(supabase, jeepneyId, terminalId)
            markAlertSent(startedAt)
        }

        if (elapsedMinutes >= GeofenceManager.LOADING_DURATION && !isInside) {
            val route = if (terminalId == 1) "Donsol → Daraga" else "Daraga → Donsol"
            supabase.departJeepneyBlocking(jeepneyId, route)
            return Result.success() // done — don't reschedule, next "arrived" cycle restarts the chain
        }

        return rescheduleAndSucceed()
    }

    private fun rescheduleAndSucceed(): Result {
        schedule(applicationContext)
        return Result.success()
    }

    private fun isNearTerminal(supabase: SupabaseService, jeepneyId: String, terminalId: Int): Boolean {
        val terminalLatLng = GeofenceManager.TERMINALS[terminalId] ?: return true // fail safe
        val gps = supabase.getLatestGpsBlocking(jeepneyId) ?: return true // fail safe: no fix, assume still there
        val result = FloatArray(1)
        Location.distanceBetween(gps.first, gps.second, terminalLatLng.first, terminalLatLng.second, result)
        return result[0] <= GeofenceManager.GEOFENCE_RADIUS
    }

    private fun alertAlreadySent(loadingStartedAt: Long): Boolean {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ALERT_SENT + loadingStartedAt, false)
    }

    private fun markAlertSent(loadingStartedAt: Long) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALERT_SENT + loadingStartedAt, true)
            .apply()
    }

    /** Blocks (bounded) so the alert goes out before this WorkManager job is considered finished. */
    private fun sendAlertBlocking(supabase: SupabaseService, jeepneyId: String, terminalId: Int) {
        val latch = CountDownLatch(1)
        supabase.getJeepInfo { info ->
            if (info != null) {
                val smsService = SMSService(applicationContext, supabase)
                val terminalName = GeofenceManager.TERMINAL_NAMES[terminalId] ?: "Terminal $terminalId"
                smsService.notifyLoadingAlert(
                    jeepneyId = jeepneyId,
                    plateNumber = info.plateNumber,
                    jeepName = info.jeepName,
                    driverName = info.driverName,
                    terminalName = terminalName,
                    minutes = GeofenceManager.ALERT_THRESHOLD,
                    terminalId = terminalId,
                    bracket = info.bracket
                )
            }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
    }
}

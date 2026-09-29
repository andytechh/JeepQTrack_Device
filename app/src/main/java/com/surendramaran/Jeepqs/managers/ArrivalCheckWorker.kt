// app/src/main/java/com/surendramaran/Jeepqs/workers/ArrivalCheckWorker.kt
package com.surendramaran.Jeepqs.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.surendramaran.Jeepqs.managers.GeofenceManager
import com.surendramaran.Jeepqs.services.SupabaseService
import com.surendramaran.Jeepqs.settings.DeviceConfig
import java.util.concurrent.TimeUnit

/**
 * Backup for GeofenceManager's in-memory arrivalRunnable.
 *
 * finalizeArrival() silently aborts if isInsideTerminal has flipped false
 * (GPS jitter near the geofence edge) or the app process was killed/Doze-
 * throttled before the 1-minute Handler callback fired — and there was no
 * retry. A jeep could get permanently stuck at status="arrived", never
 * joining the queue, regardless of what any other jeep at the terminal
 * was doing. This worker re-derives state from Supabase every ~20s while
 * a jeep is "arrived" and forces the queue join once the grace period has
 * genuinely elapsed, independent of whether the Handler ever fires.
 *
 * TEMP: heavily logged (TAG = "ArrivalCheckWorker") while diagnosing why
 * a jeep is still getting stuck. Once confirmed working, trim back to the
 * usual sparse logging.
 */
class ArrivalCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "ArrivalCheckWorker"
        private const val UNIQUE_WORK_NAME = "arrival_check_worker"
        private const val POLL_INTERVAL_SECONDS = 20L

        /** Call this whenever status is set to "arrived" (and on app boot if it's still "arrived"). */
        fun schedule(context: Context, initialDelaySeconds: Long = POLL_INTERVAL_SECONDS) {
            Log.d(TAG, "schedule() called, initialDelaySeconds=$initialDelaySeconds")

            val request = OneTimeWorkRequestBuilder<ArrivalCheckWorker>()
                .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            val op = WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            // .result is a ListenableFuture<Operation.State.SUCCESS> — logging
            // whether the enqueue itself was even accepted by WorkManager.
            op.result.addListener(
                {
                    try {
                        Log.d(TAG, "schedule() enqueue result: ${op.result.get()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "schedule() enqueue FAILED: ${e.message}", e)
                    }
                },
                { it.run() }
            )
        }

        fun cancel(context: Context) {
            Log.d(TAG, "cancel() called")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "doWork() STARTED — runAttemptCount=$runAttemptCount")

        DeviceConfig.init(applicationContext)
        val jeepneyId = DeviceConfig.getJeepId()
        val bracket = DeviceConfig.getBracket() ?: 1
        val terminalId = DeviceConfig.getTerminalId() ?: 1

        Log.d(TAG, "doWork() config — jeepneyId=$jeepneyId bracket=$bracket terminalId=$terminalId")

        if (jeepneyId.isNullOrEmpty()) {
            Log.w(TAG, "doWork() aborting — jeepneyId is null/empty")
            return Result.success()
        }

        val supabase = SupabaseService(applicationContext)

        val (status, arrivedAt) = supabase.getStatusAndArrivedAtBlocking(jeepneyId)
        Log.d(TAG, "doWork() fetched — status=$status arrivedAt=$arrivedAt")

        if (status != "arrived") {
            Log.d(TAG, "doWork() status is '$status', not 'arrived' — nothing to do, returning success")
            return Result.success()
        }

        if (arrivedAt == null) {
            Log.w(TAG, "doWork() status IS 'arrived' but arrivedAt is null — rescheduling to retry")
            return rescheduleAndSucceed()
        }

        val elapsedMs = System.currentTimeMillis() - arrivedAt
        val graceMs = GeofenceManager.ARRIVAL_GRACE_MINUTES * 60_000

        Log.d(TAG, "doWork() elapsedMs=$elapsedMs graceMs=$graceMs (elapsed >= grace: ${elapsedMs >= graceMs})")

        if (elapsedMs < graceMs) {
            Log.d(TAG, "doWork() grace period not yet elapsed — rescheduling")
            return rescheduleAndSucceed()
        }

        Log.d(TAG, "doWork() grace elapsed and still 'arrived' — forcing queue join now: " +
                "jeepneyId=$jeepneyId bracket=$bracket terminalId=$terminalId")

        val result = supabase.addToQueueWithBracketAndTerminalBlocking(jeepneyId, bracket, terminalId)

        Log.d(TAG, "doWork() addToQueueWithBracketAndTerminalBlocking result=$result")

        if (result == null) {
            Log.w(TAG, "doWork() RPC returned null (network hiccup?) — rescheduling")
            return rescheduleAndSucceed()
        }

        Log.d(TAG, "doWork() SUCCESS — jeep queued as status=${result.second} position=${result.first}")
        return Result.success()
    }

    private fun rescheduleAndSucceed(): Result {
        schedule(applicationContext)
        return Result.success()
    }
}
// app/src/main/java/com/surendramaran/Jeepqs/managers/GeofencePendingIntent.kt
package com.surendramaran.Jeepqs.managers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.surendramaran.Jeepqs.services.GeofenceBroadcastReceiver

object GeofencePendingIntent {

    private var pendingIntent: PendingIntent? = null

    fun getPendingIntent(context: Context): PendingIntent {
        if (pendingIntent == null) {
            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return pendingIntent!!
    }
}
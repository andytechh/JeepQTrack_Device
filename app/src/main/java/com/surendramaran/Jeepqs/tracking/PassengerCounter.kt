package com.surendramaran.Jeepqs.tracking

import android.util.Log

class PassengerCounter {

    private val countedBoarded = mutableSetOf<Int>()
    private val countedExited = mutableSetOf<Int>()

    var boarded = 0
        private set

    var exited = 0
        private set

    var frontBoarded = 0
        private set
    var rearBoarded = 0
        private set
    var frontExited = 0
        private set
    var rearExited = 0
        private set

    val inside: Int
        get() = boarded - exited

    // Two lines with a gap between them. A track only counts as boarding
    // once it has been seen OUTSIDE (<= LINE_OUTER) at some point and is
    // later seen INSIDE (>= LINE_INNER), however many frames that takes -
    // and vice versa for exiting. A person standing/seated in the gap
    // won't flip-flop the count.
    private val LINE_OUTER = 0.45f
    private val LINE_INNER = 0.65f

    private enum class Zone { OUTSIDE, DEAD, INSIDE }

    // Persists each track's last known zone across frames. This is the
    // key fix: the previous version only compared this frame's cy against
    // last frame's cy directly, which required a person to cross the
    // entire OUTER->INNER gap in a single frame update to be counted -
    // something normal, gradual walking motion essentially never does.
    // Tracking zone state instead means "was outside at some point, is
    // now inside" is caught correctly regardless of how many frames the
    // crossing took.
    private val lastZone = mutableMapOf<Int, Zone>()

    private fun zoneOf(cy: Float): Zone = when {
        cy <= LINE_OUTER -> Zone.OUTSIDE
        cy >= LINE_INNER -> Zone.INSIDE
        else -> Zone.DEAD
    }

    fun update(tracks: List<CentroidTracker.Track>, door: String) {
        for (track in tracks) {

            // Require a few consecutive matched frames before trusting this
            // track at all - filters single-frame noise (hands, misfires).
            if (track.confirmedFrames < CentroidTracker.CONFIRM_FRAMES) continue

            val currentZone = zoneOf(track.cy)

            // A track first seen already inside the cabin is very likely a
            // re-acquired existing passenger (lost briefly to occlusion,
            // given a new id), not someone freshly boarding. Seed its zone
            // without triggering a transition, and mark it already-counted
            // so it isn't double counted later.
            if (track.justCreated) {
                lastZone[track.id] = currentZone
                if (currentZone == Zone.INSIDE) {
                    countedBoarded.add(track.id)
                }
                continue
            }

            val previousZone = lastZone[track.id] ?: currentZone

            Log.d("TRACK", "ID=${track.id} zone=$previousZone->$currentZone cy=${track.cy}")

            if (previousZone == Zone.OUTSIDE && currentZone == Zone.INSIDE) {
                Log.d("TRACK", "BOARDING ID=${track.id}")
                if (!countedBoarded.contains(track.id)) {
                    countedBoarded.add(track.id)
                    boarded++
                    if (door == "FRONT") frontBoarded++ else rearBoarded++
                }
            } else if (previousZone == Zone.INSIDE && currentZone == Zone.OUTSIDE) {
                Log.d("TRACK", "EXITING ID=${track.id}")
                if (!countedExited.contains(track.id)) {
                    countedExited.add(track.id)
                    exited++
                    if (door == "FRONT") frontExited++ else rearExited++
                }
            }

            lastZone[track.id] = currentZone
        }

        // Drop zone memory for tracks that no longer exist (lost to
        // CentroidTracker's MAX_MISSED pruning), so this map doesn't grow
        // unbounded over a long session.
        val currentIds = tracks.map { it.id }.toSet()
        lastZone.keys.retainAll(currentIds)
    }

    fun reset() {
        countedBoarded.clear()
        countedExited.clear()
        lastZone.clear()
        boarded = 0
        exited = 0
        frontBoarded = 0
        rearBoarded = 0
        frontExited = 0
        rearExited = 0
    }
}
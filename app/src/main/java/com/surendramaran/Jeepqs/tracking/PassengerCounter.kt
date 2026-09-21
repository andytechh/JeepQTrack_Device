package com.surendramaran.Jeepqs.tracking

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Counts boarding/exiting.
 *
 * Position "p" runs 0..1 along the walking direction: 0 = OUTSIDE, 1 = INSIDE.
 *
 * A count needs a real ORIGIN: the track must have been clearly seen on one
 * side first (outsideSeen / insideSeen). A track that is born in the middle
 * band, or as a huge box directly under the camera, has no origin and can
 * NOT count in either direction. (The previous version let such tracks
 * count whichever way they drifted next - that produced phantom exits.)
 *
 * Two ways to count:
 *  1. CROSSING: seen outside, now clearly inside  -> BOARD (mirror for EXIT).
 *  2. LOST AT DOOR: the camera often cannot see a person directly under it.
 *     If a track that came from one side reaches the centre and is then lost
 *     for LOST_FRAMES frames, count the passage in that direction.
 */
class PassengerCounter {

    // ------------------------------------------------------------------
    // CALIBRATION
    // Phone top toward jeep, bottom toward outside, walking up/down the image:
    //   TRAVEL_ALONG_Y = true, OUTSIDE_IS_LOW = false
    // ------------------------------------------------------------------
    private val TRAVEL_ALONG_Y = true
    private val OUTSIDE_IS_LOW = false

    private val LINE = 0.50f
    private val HYSTERESIS = 0.03f      // must be this far past the line to cross
    private val ORIGIN_MARGIN = 0.05f   // how far to a side counts as "seen there"
    private val CENTER_BAND = 0.20f     // |p - LINE| within this = "at the door"
    private val GIANT_SIZE = 0.85f      // box bigger than this = centre unreliable
    private val LOST_FRAMES = 4         // frames missing before "lost at door" rule

    private class State {
        var lo = 1e9f
        var hi = -1e9f
        var centerReached = false
    }

    private val states = mutableMapOf<Int, State>()

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
        get() = (boarded - exited).coerceAtLeast(0)

    private fun position(track: CentroidTracker.Track): Float {
        val raw = if (TRAVEL_ALONG_Y) track.cy else track.cx
        return if (OUTSIDE_IS_LOW) raw else 1f - raw
    }

    private fun countBoard(door: String, why: String, id: Int) {
        Log.d("COUNT", "BOARD ($why) id=$id")
        boarded++
        if (door == "FRONT") frontBoarded++ else rearBoarded++
    }

    private fun countExit(door: String, why: String, id: Int) {
        Log.d("COUNT", "EXIT ($why) id=$id")
        exited++
        if (door == "FRONT") frontExited++ else rearExited++
    }

    fun update(tracks: List<CentroidTracker.Track>, door: String) {
        for (track in tracks) {
            val st = states.getOrPut(track.id) { State() }
            val confirmed = track.confirmedFrames >= CentroidTracker.CONFIRM_FRAMES

            // ---------- track currently NOT matched this frame ----------
            if (track.missed > 0) {
                if (track.missed >= LOST_FRAMES && st.centerReached && confirmed) {
                    val outsideSeen = st.lo <= LINE - ORIGIN_MARGIN
                    val insideSeen = st.hi >= LINE + ORIGIN_MARGIN
                    if (outsideSeen && !insideSeen) {
                        countBoard(door, "lost at door", track.id)
                    } else if (insideSeen && !outsideSeen) {
                        countExit(door, "lost at door", track.id)
                    }
                    // Origin is unknown after a loss; start fresh.
                    st.lo = 1e9f
                    st.hi = -1e9f
                    st.centerReached = false
                }
                continue
            }

            // ---------- matched this frame ----------
            val box = track.box
            val giant = box != null && (box.w > GIANT_SIZE || box.h > GIANT_SIZE)
            if (giant) {
                // Person is right under the camera: centre is meaningless,
                // but it does tell us they reached the door.
                st.centerReached = true
                Log.d("TRACK", "id=${track.id} GIANT box w=${"%.2f".format(box!!.w)} h=${"%.2f".format(box.h)}")
            }

            val p = position(track)
            st.lo = min(st.lo, p)
            st.hi = max(st.hi, p)
            if (abs(p - LINE) <= CENTER_BAND || (st.lo < LINE && st.hi > LINE)) {
                st.centerReached = true
            }

            Log.d(
                "TRACK",
                "id=${track.id} cx=${"%.2f".format(track.cx)} cy=${"%.2f".format(track.cy)} " +
                        "p=${"%.2f".format(p)} lo=${"%.2f".format(st.lo)} hi=${"%.2f".format(st.hi)} " +
                        "frames=${track.confirmedFrames}"
            )

            if (!confirmed) continue

            val outsideSeen = st.lo <= LINE - ORIGIN_MARGIN
            val insideSeen = st.hi >= LINE + ORIGIN_MARGIN

            if (outsideSeen && p >= LINE + HYSTERESIS) {
                countBoard(door, "crossed", track.id)
                st.lo = p
                st.hi = p
                st.centerReached = false
            } else if (insideSeen && p <= LINE - HYSTERESIS) {
                countExit(door, "crossed", track.id)
                st.lo = p
                st.hi = p
                st.centerReached = false
            }
        }

        // Forget state for tracks the tracker has already pruned.
        val liveIds = tracks.map { it.id }.toSet()
        states.keys.retainAll(liveIds)
    }

    fun reset() {
        states.clear()
        boarded = 0
        exited = 0
        frontBoarded = 0
        rearBoarded = 0
        frontExited = 0
        rearExited = 0
    }
}
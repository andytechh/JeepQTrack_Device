package com.surendramaran.Jeepqs.tracking

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PassengerCounter {

    private val TRAVEL_ALONG_Y = true

    // Flipped from false -> true: on this top-mounted camera, "outside"
    // (the street/step) reads as the LOW end of the position value and
    // "inside" (the vehicle interior) reads as the HIGH end. This was
    // backwards before, which is why boarding was logging as exited.
    private val OUTSIDE_IS_LOW = true

    private val LINE = 0.50f
    private val HYSTERESIS = 0.08f
    private val ORIGIN_MARGIN = 0.07f
    private val CENTER_BAND = 0.10f
    private val GIANT_SIZE = 0.85f
    private val LOST_FRAMES = 8
    private val MIN_TRAVEL = 0.12f

    // Was 1. A crossing now has to hold true for 2 consecutive frames
    // before it's confirmed - cheap extra guard against a single jittery
    // frame (a pose wobble, a brief false box) triggering a count.
    private val CONFIRM_CROSS_FRAMES = 2

    // Reject tracks whose detections were weak on average - these are
    // the ones most likely to be a false positive (bag, pole, seat) rather
    // than a real passenger.
    //
    // Must stay BELOW Detector.CONFIDENCE_THRESHOLD (0.20F) or every
    // crossing gets silently dropped again. 0.35f leaves margin under
    // that. Re-tune using real avgConf values from the "COUNT" logcat
    // trace: set it a bit below what real people show, and above what
    // hand/bag false positives show, once you have both from real footage.
    private val MIN_AVG_CONFIDENCE = 0.35f

    // NEW - separate, stricter persistence requirement just for counting
    // eligibility (tracking/drawing still only needs CentroidTracker's
    // own CONFIRM_FRAMES). A hand or bag passing quickly through frame
    // is much less likely to stay tracked this long than an actual
    // person walking through the door. Tune up/down based on how many
    // frames real crossings vs. false positives actually last in your
    // logs.
    private val MIN_CONFIRMED_FRAMES_FOR_COUNT = 5

    private val TREND_WINDOW = 5
    private val MIN_TREND_SLOPE = 0.02f

    private class State {
        var lo = 1e9f
        var hi = -1e9f
        var centerReached = false
        var pendingDir = 0
        var pendingStreak = 0
        var confSum = 0f
        var confCount = 0
        val recentP = ArrayDeque<Float>()   // last few positions, oldest first
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

    private fun trendSlope(st: State): Float {
        if (st.recentP.size < 2) return 0f
        val first = st.recentP.first()
        val last = st.recentP.last()
        return (last - first) / (st.recentP.size - 1)
    }

    private fun countBoard(door: String, why: String, id: Int, avgConf: Float) {
        Log.d("COUNT", "BOARD ($why) id=$id avgConf=${"%.2f".format(avgConf)}")
        boarded++
        if (door == "FRONT") frontBoarded++ else rearBoarded++
    }

    private fun countExit(door: String, why: String, id: Int, avgConf: Float) {
        Log.d("COUNT", "EXIT ($why) id=$id avgConf=${"%.2f".format(avgConf)}")
        exited++
        if (door == "FRONT") frontExited++ else rearExited++
    }

    fun update(tracks: List<CentroidTracker.Track>, door: String) {
        for (track in tracks) {
            val st = states.getOrPut(track.id) { State() }
            val confirmed = track.confirmedFrames >= CentroidTracker.CONFIRM_FRAMES
            val countEligible = track.confirmedFrames >= MIN_CONFIRMED_FRAMES_FOR_COUNT

            if (track.missed > 0) {
                if (track.missed >= LOST_FRAMES && st.centerReached && countEligible
                    && (st.hi - st.lo) >= MIN_TRAVEL
                ) {
                    val avgConf = if (st.confCount > 0) st.confSum / st.confCount else 0f
                    if (avgConf >= MIN_AVG_CONFIDENCE) {
                        val outsideSeen = st.lo <= LINE - ORIGIN_MARGIN
                        val insideSeen = st.hi >= LINE + ORIGIN_MARGIN
                        if (outsideSeen && !insideSeen) {
                            countBoard(door, "lost at door", track.id, avgConf)
                        } else if (insideSeen && !outsideSeen) {
                            countExit(door, "lost at door", track.id, avgConf)
                        } else {
                            Log.d(
                                "COUNT",
                                "UNRESOLVED id=${track.id} lo=${"%.2f".format(st.lo)} " +
                                        "hi=${"%.2f".format(st.hi)} outsideSeen=$outsideSeen insideSeen=$insideSeen"
                            )
                        }
                    } else {
                        Log.d("COUNT", "DROPPED id=${track.id} low avg confidence ${"%.2f".format(avgConf)}")
                    }
                    st.lo = 1e9f
                    st.hi = -1e9f
                    st.centerReached = false
                    st.pendingDir = 0
                    st.pendingStreak = 0
                    st.recentP.clear()
                }
                continue
            }

            val box = track.box
            val giant = box != null && (box.w > GIANT_SIZE || box.h > GIANT_SIZE)
            if (giant) st.centerReached = true

            box?.let {
                st.confSum += it.cnf
                st.confCount++
            }

            val p = position(track)
            st.lo = min(st.lo, p)
            st.hi = max(st.hi, p)
            if (abs(p - LINE) <= CENTER_BAND || (st.lo < LINE && st.hi > LINE)) {
                st.centerReached = true
            }

            st.recentP.addLast(p)
            if (st.recentP.size > TREND_WINDOW) st.recentP.removeFirst()

            if (!confirmed) continue

            val avgConf = if (st.confCount > 0) st.confSum / st.confCount else 0f
            val confOk = avgConf >= MIN_AVG_CONFIDENCE

            val outsideSeen = st.lo <= LINE - ORIGIN_MARGIN
            val insideSeen = st.hi >= LINE + ORIGIN_MARGIN
            val traveled = (st.hi - st.lo) >= MIN_TRAVEL
            val slope = trendSlope(st)

            // DEBUG TRACE - filter logcat by tag "COUNT". Now also shows
            // countEligible so you can see whether a false positive (hand/
            // bag) is being rejected by the frame-persistence gate before
            // it ever reaches the direction/confidence checks.
            Log.d(
                "COUNT",
                "id=${track.id} p=${"%.2f".format(p)} lo=${"%.2f".format(st.lo)} " +
                        "hi=${"%.2f".format(st.hi)} avgConf=${"%.2f".format(avgConf)} " +
                        "confOk=$confOk countEligible=$countEligible traveled=$traveled slope=${"%.2f".format(slope)}"
            )

            if (!countEligible) continue

            val wantsBoard = confOk && outsideSeen && traveled &&
                    p >= LINE + HYSTERESIS && slope >= MIN_TREND_SLOPE
            val wantsExit = confOk && insideSeen && traveled &&
                    p <= LINE - HYSTERESIS && slope <= -MIN_TREND_SLOPE

            if (wantsBoard) {
                if (st.pendingDir != 1) { st.pendingDir = 1; st.pendingStreak = 0 }
                st.pendingStreak++
                if (st.pendingStreak >= CONFIRM_CROSS_FRAMES) {
                    countBoard(door, "crossed", track.id, avgConf)
                    // Reset to the CENTER LINE, not the current position.
                    // Resetting to `p` (past the line already) made both
                    // "insideSeen"/"outsideSeen" trivially true again right
                    // after a count, so a passenger pausing at the door
                    // (paying fare, tapping a card) could get double-counted
                    // from ordinary jitter. Resetting to LINE forces a real,
                    // fresh excursion past ORIGIN_MARGIN before the next
                    // count can fire.
                    st.lo = LINE; st.hi = LINE; st.centerReached = false
                    st.pendingDir = 0; st.pendingStreak = 0
                    st.recentP.clear()
                }
            } else if (wantsExit) {
                if (st.pendingDir != -1) { st.pendingDir = -1; st.pendingStreak = 0 }
                st.pendingStreak++
                if (st.pendingStreak >= CONFIRM_CROSS_FRAMES) {
                    countExit(door, "crossed", track.id, avgConf)
                    st.lo = LINE; st.hi = LINE; st.centerReached = false
                    st.pendingDir = 0; st.pendingStreak = 0
                    st.recentP.clear()
                }
            } else {
                st.pendingDir = 0
                st.pendingStreak = 0
            }
        }

        val liveIds = tracks.map { it.id }.toSet()
        states.keys.retainAll(liveIds)
    }

    fun reset() {
        states.clear()
        boarded = 0; exited = 0
        frontBoarded = 0; rearBoarded = 0
        frontExited = 0; rearExited = 0
    }
}
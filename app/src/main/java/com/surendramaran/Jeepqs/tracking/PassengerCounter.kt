package com.surendramaran.Jeepqs.tracking

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PassengerCounter {

    private val TRAVEL_ALONG_Y = true
    private val OUTSIDE_IS_LOW = true

    private val LINE = 0.50f
    private val HYSTERESIS = 0.08f
    private val ORIGIN_MARGIN = 0.08f   // was 0.10 - less runway needed on each side of the line
    private val CENTER_BAND = 0.10f
    private val GIANT_SIZE = 0.85f
    private val LOST_FRAMES = 8
    private val MIN_TRAVEL = 0.12f      // was 0.15
    private val CONFIRM_CROSS_FRAMES = 1

    // Reject tracks whose detections were weak on average - these are
    // the ones most likely to be a false positive (bag, pole, seat) rather
    // than a real passenger.
    //
    // NOTE: this must stay BELOW Detector.CONFIDENCE_THRESHOLD (0.40F) or
    // every single crossing gets silently dropped - a detection that was
    // good enough to be drawn and tracked would never be good enough to be
    // counted. Was 0.55f, which sat *above* the detector's own gate and
    // created a dead zone (0.40-0.55) where boxes tracked fine but nothing
    // ever counted. Re-tune this using the avgConf values in the "COUNT"
    // logcat trace below once you've watched a few real crossings with the
    // new model - set it a bit below the lowest avgConf a genuine person
    // produces, not an arbitrary carryover number.
    private val MIN_AVG_CONFIDENCE = 0.55f

    // Require the track's recent motion to actually point the way it
    // claims to be crossing, not just "current position is past the line".
    // A short position history smoothed into a trend rejects single-frame
    // jitter/ID-swap jumps that the wider tracker gate now allows through.
    private val TREND_WINDOW = 5
    private val MIN_TREND_SLOPE = 0.02f   // per-frame average movement needed, in the crossing direction

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

    // Average forward-direction movement per frame over the recent window.
    // Positive = moving toward "inside" (p increasing), negative = toward "outside".
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

            if (track.missed > 0) {
                if (track.missed >= LOST_FRAMES && st.centerReached && confirmed
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
                            // NEW - tells us exactly why a real crossing didn't
                            // register: either it never reached far enough on
                            // one side (calibration/FOV issue), or it reached
                            // BOTH sides (ambiguous - direction logic issue).
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

            // DEBUG TRACE - filter logcat by tag "COUNT" while a person
            // walks through the door to see exactly which gate is failing
            // per frame (confidence, travel distance, direction/slope).
            // Safe to remove once counting is confirmed working reliably;
            // cheap to leave in otherwise since it's a single log call.
            Log.d(
                "COUNT",
                "id=${track.id} p=${"%.2f".format(p)} lo=${"%.2f".format(st.lo)} " +
                        "hi=${"%.2f".format(st.hi)} avgConf=${"%.2f".format(avgConf)} " +
                        "confOk=$confOk traveled=$traveled slope=${"%.2f".format(slope)}"
            )

            val wantsBoard = confOk && outsideSeen && traveled &&
                    p >= LINE + HYSTERESIS && slope >= MIN_TREND_SLOPE
            val wantsExit = confOk && insideSeen && traveled &&
                    p <= LINE - HYSTERESIS && slope <= -MIN_TREND_SLOPE

            if (wantsBoard) {
                if (st.pendingDir != 1) { st.pendingDir = 1; st.pendingStreak = 0 }
                st.pendingStreak++
                if (st.pendingStreak >= CONFIRM_CROSS_FRAMES) {
                    countBoard(door, "crossed", track.id, avgConf)
                    st.lo = p; st.hi = p; st.centerReached = false
                    st.pendingDir = 0; st.pendingStreak = 0
                    st.recentP.clear()
                }
            } else if (wantsExit) {
                if (st.pendingDir != -1) { st.pendingDir = -1; st.pendingStreak = 0 }
                st.pendingStreak++
                if (st.pendingStreak >= CONFIRM_CROSS_FRAMES) {
                    countExit(door, "crossed", track.id, avgConf)
                    st.lo = p; st.hi = p; st.centerReached = false
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
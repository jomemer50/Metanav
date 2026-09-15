package com.metanav.core

import kotlin.math.roundToInt

/**
 * Decides *whether* to speak and *what* to say. The goal is to be helpful without nagging:
 * announce a new obstacle once, again if it gets a meter closer or becomes urgent, and remind at
 * most twice if it just sits there. A global cooldown keeps advisories from piling up.
 */
class AdvisoryPolicy(private val config: ReasonerConfig) {
    private var lastSpokenAtMs: Long = NEVER
    private var lastSpokenUrgency: Urgency = Urgency.NONE
    private var lastAlertTrackId: Int = -1
    private var lastAlertAtMs: Long = NEVER
    private var clearPending = false
    private var lastClearAtMs: Long = NEVER

    fun reset() {
        lastSpokenAtMs = NEVER
        lastSpokenUrgency = Urgency.NONE
        lastAlertTrackId = -1
        lastAlertAtMs = NEVER
        lastClearAtMs = NEVER
        clearPending = false
    }

    fun decide(
        nowMs: Long,
        candidate: Track?,
        direction: Direction,
        corridorBlocked: Boolean,
    ): Advisory? {
        if (candidate == null) {
            return maybeClear(nowMs, corridorBlocked)
        }
        val z = candidate.distanceMeters
        val urgency = if (z < config.urgentDistanceMeters) Urgency.STOP else Urgency.CAUTION
        val reason = speakReason(candidate, z, urgency, nowMs) ?: return null

        // New obstacles and escalations to STOP are what the user needs to hear right away; only
        // repeat announcements about a known obstacle wait out the long cooldown.
        val cooldown = when {
            reason == Reason.NEW -> config.newObstacleCooldownMs
            urgency == Urgency.STOP -> config.urgentCooldownMs
            else -> config.cooldownMs
        }
        if (since(nowMs, lastSpokenAtMs) < cooldown) return null

        candidate.announcedCount++
        candidate.lastAnnouncedDistance = z
        candidate.lastAnnouncedAtMs = nowMs
        candidate.lastAnnouncedUrgency = urgency
        if (reason == Reason.REMINDER) candidate.reminders++
        lastSpokenAtMs = nowMs
        lastSpokenUrgency = urgency
        lastAlertTrackId = candidate.id
        lastAlertAtMs = nowMs
        clearPending = config.announceClear

        return Advisory(
            text = phrase(candidate.label, z, urgency, direction),
            urgency = urgency,
            label = candidate.label,
            distanceMeters = z,
            direction = direction,
        )
    }

    private enum class Reason { NEW, CLOSER, ESCALATED, REMINDER }

    private fun speakReason(track: Track, z: Float, urgency: Urgency, nowMs: Long): Reason? {
        if (track.announcedCount == 0) return Reason.NEW
        if (urgency == Urgency.STOP && track.lastAnnouncedUrgency != Urgency.STOP) return Reason.ESCALATED
        if (z <= track.lastAnnouncedDistance - config.reannounceCloserBy) return Reason.CLOSER
        val sinceLast = since(nowMs, track.lastAnnouncedAtMs)
        if (sinceLast >= config.reminderIntervalMs &&
            track.reminders < config.maxReminders &&
            z <= track.lastAnnouncedDistance + 0.3f
        ) return Reason.REMINDER
        return null
    }

    private fun maybeClear(nowMs: Long, corridorBlocked: Boolean): Advisory? {
        if (!clearPending || corridorBlocked) return null
        // Wait a beat so a momentary gap does not produce "clear" followed by the same obstacle.
        if (since(nowMs, lastAlertAtMs) < 1500) return null
        if (since(nowMs, lastClearAtMs) < 5000) { clearPending = false; return null }
        clearPending = false
        lastClearAtMs = nowMs
        lastSpokenAtMs = nowMs
        lastSpokenUrgency = Urgency.NONE
        return Advisory("Path clear", Urgency.NONE, "", Float.POSITIVE_INFINITY, Direction.NONE)
    }

    fun phrase(label: String, meters: Float, urgency: Urgency, direction: Direction): String {
        val what = if (label.isBlank()) Labels.GENERIC else label
        val move = when (direction) {
            Direction.LEFT -> "Move left."
            Direction.RIGHT -> "Move right."
            Direction.STOP -> "Stop."
            Direction.NONE -> ""
        }
        return if (urgency == Urgency.STOP) {
            val tail = if (direction == Direction.LEFT || direction == Direction.RIGHT) " $move" else ""
            "Stop. $what very close.$tail"
        } else {
            val tail = if (move.isNotEmpty()) " $move" else ""
            "$what ahead, ${distancePhrase(meters, config.units)}.$tail"
        }
    }

    companion object {
        /** Sentinel for "has not happened yet"; timestamps are never negative. */
        const val NEVER: Long = -1L

        /** Milliseconds elapsed since [then], or a huge number when it never happened. */
        fun since(nowMs: Long, then: Long): Long = if (then < 0) Long.MAX_VALUE / 2 else nowMs - then

        fun distancePhrase(meters: Float, units: Units): String {
            if (meters < 1.2f) return "very close"
            return when (units) {
                Units.METERS -> {
                    val halves = (meters * 2f).roundToInt()
                    val whole = halves / 2
                    val half = halves % 2 == 1
                    when {
                        whole == 0 -> "half a meter"
                        half && whole == 1 -> "1 and a half meters"
                        half -> "$whole and a half meters"
                        whole == 1 -> "1 meter"
                        else -> "$whole meters"
                    }
                }
                Units.FEET -> {
                    val feet = (meters * 3.28084f).roundToInt().coerceAtLeast(1)
                    if (feet == 1) "1 foot" else "$feet feet"
                }
            }
        }

        fun shortDistance(meters: Float, units: Units): String = when (units) {
            Units.METERS -> String.format(java.util.Locale.US, "%.1f m", meters)
            Units.FEET -> "${(meters * 3.28084f).roundToInt()} ft"
        }
    }
}

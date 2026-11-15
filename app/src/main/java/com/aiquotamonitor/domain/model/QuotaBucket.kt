package com.aiquotamonitor.domain.model

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Single source of truth for the reset countdown used by the widgets, the hero
 * card, and the bucket detail sheet.
 *
 * Truncates (never rounds) and never carries across a boundary, so `59m 1s`
 * renders `"59m"` (not `"1h"`), `23h 59m 1s` renders `"23h 59m"` (not `"1d"`),
 * and a sub-day window never emits `"0d"`. Returns `""` when the timestamp is
 * absent or unparseable, and `"Now"` once the window has elapsed.
 */
fun formatResetCountdownFromIso(
    resetsAt: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
    includeMinutesUnderDay: Boolean = true
): String {
    if (resetsAt.isNullOrBlank()) return ""
    val resetInstant = try {
        Instant.parse(resetsAt)
    } catch (_: Exception) {
        return ""
    }
    val seconds = Duration.between(Instant.ofEpochMilli(nowEpochMs), resetInstant).seconds
    if (seconds <= 0L) return "Now"

    val days = seconds / 86_400L
    val hours = (seconds % 86_400L) / 3_600L
    val minutes = (seconds % 3_600L) / 60L

    return when {
        days >= 1L -> if (hours > 0L) "${days}d ${hours}h" else "${days}d"
        hours >= 1L -> if (includeMinutesUnderDay && minutes > 0L) "${hours}h ${minutes}m" else "${hours}h"
        else -> "${minutes.coerceAtLeast(1L)}m"
    }
}

@Serializable
data class QuotaBucket(
    val id: String,
    val group: String,
    val label: String,
    val windowType: String,
    val windowDurationHours: Int,
    val remainingPercent: Double,
    val usedPercent: Double,
    val resetsAt: String? = null,
    val elapsedPercent: Double = 0.0,
    val paceDeltaPercent: Double = 0.0,
    val burnRatePerHour: Double = 0.0,
    val projectedRemainingAtReset: Double = 0.0,
    val status: String = "healthy"
) {
    /**
     * Value depending on chosen polarity
     */
    fun displayPercent(polarity: QuotaPolarity): Double = when (polarity) {
        QuotaPolarity.REMAINING -> remainingPercent
        QuotaPolarity.USED -> usedPercent
    }

    /**
     * Hours until scheduled reset timestamp
     */
    val hoursUntilReset: Double?
        get() = try {
            val at = resetsAt ?: return null
            val instant = java.time.Instant.parse(at)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(now, instant)
            if (duration.isNegative) 0.0 else duration.toMinutes() / 60.0
        } catch (_: Exception) {
            null
        }

    /**
     * Compact countdown such as `"5d 19h"`, `"3h 22m"`, `"23h"`, `"59m"`, or `"Now"`.
     */
    fun formatResetCountdown(
        nowEpochMs: Long = System.currentTimeMillis(),
        includeMinutesUnderDay: Boolean = true
    ): String = formatResetCountdownFromIso(
        resetsAt = resetsAt,
        nowEpochMs = nowEpochMs,
        includeMinutesUnderDay = includeMinutesUnderDay
    )

    /**
     * Ultra-compact provider-prefixed header, e.g. `"AGY ARGON"` or `"GROK 4.7"`.
     */
    fun shortHeaderLabel(): String = when (id) {
        "gemini-argon-wtu-weekly" -> "AGY ARGON"
        "gemini-5h" -> "AGY FLASH"
        "gemini-weekly" -> "AGY GEMINI"
        "3p-5h", "3p-weekly" -> "AGY 3P"
        "opencode-rolling", "opencode-weekly", "opencode-monthly" -> "OPENCODE"
        "grok-weekly-credits" -> "GROK 4.7"
        "grok-bot-weekly" -> "GROK BOT"
        else -> when {
            id.startsWith("gemini") || id.startsWith("3p") -> "AGY"
            id.startsWith("opencode") -> "OPENCODE"
            id.startsWith("grok") -> "GROK"
            else -> "QUOTA"
        }
    }

    /**
     * Compact window descriptor, e.g. `"7d Wk"`, `"5h Roll"`, or `"30d Mo"`.
     */
    fun shortWindowLabel(): String = when (id) {
        "gemini-argon-wtu-weekly",
        "gemini-weekly",
        "3p-weekly",
        "opencode-weekly",
        "grok-weekly-credits",
        "grok-bot-weekly" -> "7d Wk"
        "gemini-5h", "3p-5h", "opencode-rolling" -> "5h Roll"
        "opencode-monthly" -> "30d Mo"
        else -> "${windowDurationHours}h"
    }

    /**
     * Human-readable wide-deck row label, e.g. `"Argon Weekly"` or `"5-Hour Rolling"`.
     */
    fun shortWideRowLabel(): String = when (id) {
        "gemini-argon-wtu-weekly" -> "Argon Weekly"
        "gemini-5h" -> "Gemini 5h Roll"
        "gemini-weekly" -> "Gemini Weekly"
        "3p-5h" -> "Claude/GPT 5h"
        "3p-weekly" -> "Claude/GPT 7d"
        "opencode-rolling" -> "5-Hour Rolling"
        "opencode-weekly" -> "Weekly Pool"
        "opencode-monthly" -> "Monthly Pool"
        "grok-weekly-credits" -> "Grok 4.7 Weekly"
        "grok-bot-weekly" -> "Grok Bot Weekly"
        else -> label
    }
}

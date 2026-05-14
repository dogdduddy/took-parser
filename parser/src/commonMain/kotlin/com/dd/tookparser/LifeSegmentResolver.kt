package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus

class LifeSegmentResolver(
    private val schedule: LifeSchedule,
    private val isPremium: Boolean = false,
    private val nowProvider: NowProvider = { Clock.System.now() },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    fun resolve(input: String): SegmentMatch? {
        val now = nowProvider()
        val today = now.toLocalDateTime(timeZone)
        val dayOfWeek = today.dayOfWeek

        val allPatterns = LifeSegment.entries
            .flatMap { seg -> seg.triggerPatterns.map { p -> seg to p } }
            .sortedByDescending { (_, p) -> p.length }

        for ((segment, pattern) in allPatterns) {
            val index = input.indexOf(pattern)
            if (index == -1) continue

            val resolvedTime = resolveTime(segment) ?: continue

            val offset = TimeOffsetParser.parse(input)
            val finalHour: Int
            val finalMinute: Int
            if (offset != null) {
                val totalMinutes = resolvedTime.hour * 60 + resolvedTime.minute + offset.offsetMinutes
                val adjusted = if (totalMinutes < 0) totalMinutes + 24 * 60 else totalMinutes
                finalHour = (adjusted / 60) % 24
                finalMinute = adjusted % 60
            } else {
                finalHour = resolvedTime.hour
                finalMinute = resolvedTime.minute
            }

            val resolvedDate: LocalDate = if (segment.activeDays.isNotEmpty() && dayOfWeek !in segment.activeDays) {
                findNextActiveDay(today.date, segment.activeDays)
            } else {
                today.date
            }

            val scheduledAt = LocalDateTime(resolvedDate, LocalTime(finalHour, finalMinute, 0))
                .toInstant(timeZone)
                .toEpochMilliseconds()

            var title = extractTitle(input, pattern, index)
            if (offset != null) {
                title = TimeOffsetParser.removeOffset(title, offset.matchedText)
                if (title.isBlank()) title = input.trim()
            }

            return SegmentMatch(
                segment = segment,
                matchedPattern = pattern,
                matchRange = index until (index + pattern.length),
                scheduledAt = scheduledAt,
                title = title,
                isPremiumAccuracy = isPremium && segment.isPremium,
            )
        }
        return null
    }

    fun containsTrigger(input: String): Boolean =
        LifeSegment.entries.any { segment ->
            segment.triggerPatterns.any { pattern -> input.contains(pattern) }
        }

    fun stripTrigger(input: String): String {
        val allPatterns = LifeSegment.entries
            .flatMap { seg -> seg.triggerPatterns.map { p -> seg to p } }
            .sortedByDescending { (_, p) -> p.length }
        for ((_, pattern) in allPatterns) {
            val index = input.indexOf(pattern)
            if (index != -1) return extractTitle(input, pattern, index)
        }
        return input
    }

    private fun resolveTime(segment: LifeSegment): HourMinute? {
        val hasCommute = isPremium && schedule.commuteMinutes > 0

        return when (segment) {
            LifeSegment.WAKE_UP -> if (isPremium) {
                HourMinute(schedule.wakeUpHour, schedule.wakeUpMinute)
            } else {
                subtractMinutes(schedule.workStartHour, schedule.workStartMinute, 30)
            }

            LifeSegment.COMMUTE_TO_WORK -> if (hasCommute) {
                subtractMinutes(schedule.workStartHour, schedule.workStartMinute, schedule.commuteMinutes)
            } else {
                subtractMinutes(schedule.workStartHour, schedule.workStartMinute, 30)
            }

            LifeSegment.WORK_START -> HourMinute(schedule.workStartHour, schedule.workStartMinute)

            LifeSegment.MORNING -> {
                val morning = addMinutes(schedule.workStartHour, schedule.workStartMinute, 60)
                if (morning.hour >= 12) HourMinute(11, 0) else morning
            }

            LifeSegment.LUNCH -> if (isPremium) {
                HourMinute(schedule.lunchHour, schedule.lunchMinute)
            } else {
                HourMinute(12, 0)
            }

            LifeSegment.AFTERNOON -> HourMinute(14, 0)

            LifeSegment.WORK_END -> HourMinute(schedule.workEndHour, schedule.workEndMinute)

            LifeSegment.COMMUTE_HOME -> HourMinute(schedule.workEndHour, schedule.workEndMinute)

            LifeSegment.ARRIVE_HOME -> if (hasCommute) {
                addMinutes(schedule.workEndHour, schedule.workEndMinute, schedule.commuteMinutes)
            } else {
                addMinutes(schedule.workEndHour, schedule.workEndMinute, 30)
            }

            LifeSegment.DINNER -> if (isPremium) {
                HourMinute(schedule.dinnerHour, schedule.dinnerMinute)
            } else {
                addMinutes(schedule.workEndHour, schedule.workEndMinute, 60)
            }

            LifeSegment.BEDTIME -> if (isPremium) {
                HourMinute(schedule.bedtimeHour, schedule.bedtimeMinute)
            } else {
                HourMinute(23, 0)
            }
        }
    }

    private fun extractTitle(input: String, pattern: String, matchIndex: Int): String {
        var result = input
        val endIndex = matchIndex + pattern.length
        var extendedEnd = endIndex
        while (extendedEnd < result.length && result[extendedEnd] == ' ') {
            extendedEnd++
        }
        result = result.removeRange(matchIndex, extendedEnd)
        result = result.replace(Regex("\\s+"), " ").trim()
        return if (result.isBlank()) input.trim() else result
    }

    private data class HourMinute(val hour: Int, val minute: Int)

    private fun addMinutes(hour: Int, minute: Int, add: Int): HourMinute {
        val total = hour * 60 + minute + add
        return HourMinute((total / 60) % 24, total % 60)
    }

    private fun subtractMinutes(hour: Int, minute: Int, sub: Int): HourMinute {
        var total = hour * 60 + minute - sub
        if (total < 0) total += 24 * 60
        return HourMinute((total / 60) % 24, total % 60)
    }

    private fun findNextActiveDay(from: LocalDate, activeDays: Set<DayOfWeek>): LocalDate {
        var date = from.plus(DatePeriod(days = 1))
        repeat(7) {
            if (date.dayOfWeek in activeDays) return date
            date = date.plus(DatePeriod(days = 1))
        }
        return from.plus(DatePeriod(days = 1))
    }
}

data class SegmentMatch(
    val segment: LifeSegment,
    val matchedPattern: String,
    val matchRange: IntRange,
    val scheduledAt: Long,
    val title: String,
    val isPremiumAccuracy: Boolean,
)

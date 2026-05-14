package com.dd.tookparser

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

object RecurrenceCalculator {

    /**
     * Returns the next scheduled time for the rule, strictly after [from].
     * Used both in TimeParser (first occurrence) and TaskRepository (next after completion).
     */
    fun nextOccurrence(
        rule: RecurrenceRule,
        from: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long {
        val fromLdt = from.toLocalDateTime(timeZone)
        val baseDate = fromLdt.date
        val ruleTime = LocalTime(rule.hour, rule.minute, 0)

        return when (rule.type) {
            RecurrenceType.DAILY -> nextDaily(baseDate, ruleTime, from, rule, timeZone)
            RecurrenceType.WEEKLY -> nextWeekly(baseDate, ruleTime, from, rule, timeZone)
            RecurrenceType.MONTHLY -> nextMonthly(baseDate, ruleTime, from, rule, timeZone)
        }
    }

    private fun nextDaily(
        baseDate: LocalDate,
        ruleTime: LocalTime,
        from: Instant,
        rule: RecurrenceRule,
        timeZone: TimeZone,
    ): Long {
        val todayTarget = LocalDateTime(baseDate, ruleTime).toInstant(timeZone)
        val targetDate = if (todayTarget <= from) {
            baseDate.plus(DatePeriod(days = rule.interval))
        } else {
            baseDate
        }
        return LocalDateTime(targetDate, ruleTime).toInstant(timeZone).toEpochMilliseconds()
    }

    private fun nextWeekly(
        baseDate: LocalDate,
        ruleTime: LocalTime,
        from: Instant,
        rule: RecurrenceRule,
        timeZone: TimeZone,
    ): Long {
        if (rule.daysOfWeek.isEmpty()) {
            val target = LocalDateTime(baseDate.plus(DatePeriod(days = 7 * rule.interval)), ruleTime)
            return target.toInstant(timeZone).toEpochMilliseconds()
        }

        for (offset in 0..6) {
            val checkDate = baseDate.plus(DatePeriod(days = offset))
            val checkInstant = LocalDateTime(checkDate, ruleTime).toInstant(timeZone)
            if (checkDate.dayOfWeek in rule.daysOfWeek && checkInstant > from) {
                return checkInstant.toEpochMilliseconds()
            }
        }

        // Fallback: interval weeks ahead, find next active day
        var date = baseDate.plus(DatePeriod(days = 7 * rule.interval))
        while (date.dayOfWeek !in rule.daysOfWeek) {
            date = date.plus(DatePeriod(days = 1))
        }
        return LocalDateTime(date, ruleTime).toInstant(timeZone).toEpochMilliseconds()
    }

    private fun nextMonthly(
        baseDate: LocalDate,
        ruleTime: LocalTime,
        from: Instant,
        rule: RecurrenceRule,
        timeZone: TimeZone,
    ): Long {
        val targetDay = rule.dayOfMonth.coerceIn(1, 28)
        var targetDate = LocalDate(baseDate.year, baseDate.month, targetDay)
        val targetInstant = LocalDateTime(targetDate, ruleTime).toInstant(timeZone)
        if (targetInstant <= from) {
            targetDate = targetDate.plus(DatePeriod(months = rule.interval))
            targetDate = LocalDate(targetDate.year, targetDate.month, targetDay)
        }
        return LocalDateTime(targetDate, ruleTime).toInstant(timeZone).toEpochMilliseconds()
    }
}

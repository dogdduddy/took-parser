package com.dd.tookparser

import kotlinx.datetime.DayOfWeek

data class RecurrenceRule(
    val type: RecurrenceType,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int = 0,
    val hour: Int,
    val minute: Int,
)

enum class RecurrenceType {
    DAILY, WEEKLY, MONTHLY,
}

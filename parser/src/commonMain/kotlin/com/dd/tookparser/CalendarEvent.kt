package com.dd.tookparser

data class CalendarEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val calendarName: String?,
)

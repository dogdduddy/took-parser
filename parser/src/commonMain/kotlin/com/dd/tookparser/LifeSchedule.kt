package com.dd.tookparser

data class LifeSchedule(
    // === 무료 ===
    val workStartHour: Int = 9,
    val workStartMinute: Int = 0,
    val workEndHour: Int = 18,
    val workEndMinute: Int = 0,
    val isConfigured: Boolean = false,

    // === 프리미엄 ===
    val commuteMinutes: Int = 0,
    val lunchHour: Int = 12,
    val lunchMinute: Int = 0,
    val dinnerHour: Int = 19,
    val dinnerMinute: Int = 30,
    val wakeUpHour: Int = 8,
    val wakeUpMinute: Int = 0,
    val bedtimeHour: Int = 23,
    val bedtimeMinute: Int = 0,
)

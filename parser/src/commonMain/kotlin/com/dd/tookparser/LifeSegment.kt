package com.dd.tookparser

import kotlinx.datetime.DayOfWeek

private val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

enum class LifeSegment(
    val displayName: String,
    val triggerPatterns: List<String>,
    val isPremium: Boolean,
    val activeDays: Set<DayOfWeek>,
) {
    WAKE_UP(
        displayName = "기상",
        triggerPatterns = listOf("일어나서", "일어나면", "눈 뜨면", "기상 후", "기상하고"),
        isPremium = true,
        activeDays = emptySet(),
    ),
    COMMUTE_TO_WORK(
        displayName = "출근길",
        triggerPatterns = listOf("출근하는 길에", "출근하면서", "출근길에", "출근 전에", "출근전에", "출근 전", "출근전"),
        isPremium = true,
        activeDays = WEEKDAYS,
    ),
    WORK_START(
        displayName = "출근",
        triggerPatterns = listOf("회사 도착하면", "회사 가서", "출근하면", "출근하고", "출근해서", "출근 때", "출근때", "출근"),
        isPremium = false,
        activeDays = WEEKDAYS,
    ),
    MORNING(
        displayName = "오전",
        triggerPatterns = listOf("오전 중으로", "오전 중에", "오전에"),
        isPremium = false,
        activeDays = emptySet(),
    ),
    LUNCH(
        displayName = "점심",
        triggerPatterns = listOf("점심 먹기 전에", "점심시간에", "점심 먹고", "점심에", "점심"),
        isPremium = true,
        activeDays = emptySet(),
    ),
    AFTERNOON(
        displayName = "오후",
        triggerPatterns = listOf("오후 중으로", "오후 중에", "오후에"),
        isPremium = false,
        activeDays = emptySet(),
    ),
    WORK_END(
        displayName = "퇴근",
        triggerPatterns = listOf("퇴근하면서", "퇴근하면", "퇴근해서", "퇴근"),
        isPremium = false,
        activeDays = WEEKDAYS,
    ),
    COMMUTE_HOME(
        displayName = "퇴근길",
        triggerPatterns = listOf("집 가는 길에", "집 가면서", "퇴근길에"),
        isPremium = true,
        activeDays = WEEKDAYS,
    ),
    ARRIVE_HOME(
        displayName = "집 도착",
        triggerPatterns = listOf("집 도착해서", "집 도착하면", "집에 가서", "집 가서", "퇴근하고"),
        isPremium = true,
        activeDays = WEEKDAYS,
    ),
    DINNER(
        displayName = "저녁 식사",
        triggerPatterns = listOf("저녁 먹기 전에", "저녁밥 먹고", "저녁 먹고"),
        isPremium = true,
        activeDays = emptySet(),
    ),
    BEDTIME(
        displayName = "취침 전",
        triggerPatterns = listOf("잠자기 전에", "잠들기 전에", "잠들기 전", "자기 전에", "자기전에", "자기 전"),
        isPremium = true,
        activeDays = emptySet(),
    );
}

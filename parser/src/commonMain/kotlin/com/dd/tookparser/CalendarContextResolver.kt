package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

class CalendarContextResolver(
    private val eventFinder: (keywords: List<String>, hoursAhead: Int) -> CalendarEvent?,
    private val nowProvider: NowProvider = { Clock.System.now() },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    companion object {
        private const val BEFORE_OFFSET_MILLIS = 30L * 60 * 1000

        private enum class EventTiming { BEFORE, AFTER }

        private data class TriggerPattern(val regex: Regex, val timing: EventTiming)

        private val TRIGGER_PATTERNS = listOf(
            // BEFORE (긴 패턴 우선)
            TriggerPattern(Regex("(.+?)\\s*시작하기\\s*전에"), EventTiming.BEFORE),
            TriggerPattern(Regex("(.+?)\\s*시작\\s*전에"), EventTiming.BEFORE),
            TriggerPattern(Regex("(.+?)\\s*가기\\s*전에"), EventTiming.BEFORE),
            TriggerPattern(Regex("(.+?)\\s*하기\\s*전에"), EventTiming.BEFORE),
            TriggerPattern(Regex("(.+?)\\s*전에"), EventTiming.BEFORE),
            // AFTER (긴 패턴 우선)
            TriggerPattern(Regex("(.+?)\\s*끝나고\\s*나서"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*끝나고"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*끝나면"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*끝난\\s*후에?"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*후에"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*다녀와서"), EventTiming.AFTER),
            TriggerPattern(Regex("(.+?)\\s*다녀오고"), EventTiming.AFTER),
        )
    }

    private data class PatternDetection(
        val pattern: String,
        val keywords: List<String>,
        val timing: EventTiming,
        val remainingTitle: String,
    )

    fun resolve(input: String): CalendarContextMatch? {
        val detection = detectPattern(input) ?: return null

        val event = eventFinder(detection.keywords, 24)
            ?: return CalendarContextMatch(
                title = detection.remainingTitle,
                scheduledAt = null,
                matchedPattern = detection.pattern,
                calendarEvent = null,
                failReason = "캘린더에서 '${detection.keywords.first()}'을(를) 찾지 못했어요",
            )

        val offset = TimeOffsetParser.parse(input)
        val scheduledAt = calculateTimeWithOffset(event, detection.timing, offset)
        if (scheduledAt <= nowProvider().toEpochMilliseconds()) {
            return CalendarContextMatch(
                title = detection.remainingTitle,
                scheduledAt = null,
                matchedPattern = detection.pattern,
                calendarEvent = event,
                failReason = "해당 일정의 시간이 이미 지났어요",
            )
        }

        var finalTitle = detection.remainingTitle
        if (offset != null) {
            finalTitle = TimeOffsetParser.removeOffset(finalTitle, offset.matchedText)
            if (finalTitle.isBlank()) finalTitle = input.trim()
        }

        return CalendarContextMatch(
            title = finalTitle,
            scheduledAt = scheduledAt,
            matchedPattern = detection.pattern,
            calendarEvent = event,
            failReason = null,
        )
    }

    private fun detectPattern(input: String): PatternDetection? {
        for (tp in TRIGGER_PATTERNS) {
            val match = tp.regex.find(input) ?: continue
            val keywordPart = match.groupValues[1].trim()
            if (keywordPart.isBlank()) continue
            val keywords = extractSearchKeywords(keywordPart)
            if (keywords.isEmpty()) continue
            val remaining = input.removeRange(match.range).replace(Regex("\\s+"), " ").trim()
            val title = if (remaining.isBlank()) input.trim() else remaining
            return PatternDetection(
                pattern = match.value,
                keywords = keywords,
                timing = tp.timing,
                remainingTitle = title,
            )
        }
        return null
    }

    private fun extractSearchKeywords(raw: String): List<String> {
        val keywords = mutableListOf<String>()
        keywords.add(raw)
        val verbStemmed = raw
            .replace(Regex("(만나|가|하|보|받)기$"), "")
            .replace(Regex("(만나|가|하|보|받)러$"), "")
            .trim()
        if (verbStemmed.isNotBlank() && verbStemmed != raw) keywords.add(verbStemmed)
        raw.split(" ").filter { it.length >= 2 }.forEach { word ->
            if (word !in keywords) keywords.add(word)
        }
        return keywords
    }

    private fun calculateTimeWithOffset(
        event: CalendarEvent,
        timing: EventTiming,
        offset: TimeOffsetParser.OffsetResult?,
    ): Long = when (timing) {
        EventTiming.BEFORE -> {
            val offsetMs = if (offset != null) {
                offset.offsetMinutes.toLong() * 60 * 1000
            } else {
                -BEFORE_OFFSET_MILLIS
            }
            event.startMillis + offsetMs
        }
        EventTiming.AFTER -> {
            val endTime = if (event.endMillis > 0) event.endMillis else event.startMillis + 60 * 60 * 1000
            if (offset != null) endTime + offset.offsetMinutes.toLong() * 60 * 1000 else endTime
        }
    }
}

data class CalendarContextMatch(
    val title: String,
    val scheduledAt: Long?,
    val matchedPattern: String,
    val calendarEvent: CalendarEvent?,
    val failReason: String?,
)

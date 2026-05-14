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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TimeParser(
    private val nowProvider: NowProvider = { Clock.System.now() },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    companion object {
        private val TIME_KEYWORD_HOUR = mapOf(
            "새벽" to 3,
            "아침" to 8,
            "오전" to 10,
            "점심" to 12,
            "낮" to 13,
            "오후" to 15,
            "저녁" to 19,
            "밤" to 21,
        )

        private val FALLBACK_PATTERNS = listOf(
            Regex("""퇴근\s*후"""),
            Regex("""퇴근하고"""),
            Regex("""점심\s*먹고"""),
            Regex("""밥\s*먹고"""),
            Regex("""식사\s*후"""),
            Regex("""회의\s*끝나고"""),
            Regex("""회의\s*후"""),
            Regex("""수업\s*끝나고"""),
            Regex("""수업\s*후"""),
            Regex("""일\s*끝나고"""),
            Regex("""업무\s*후"""),
        )

        // 숫자 기반 명시적 시간 표현 (키워드 단독 제외)
        private val EXPLICIT_NUMERIC_TIME = Regex("""\d+\s*(?:분|시간)\s*(?:뒤|후)|\d{1,2}\s*시""")

        private val DAY_OF_WEEK_MAP = mapOf(
            "월" to DayOfWeek.MONDAY,
            "화" to DayOfWeek.TUESDAY,
            "수" to DayOfWeek.WEDNESDAY,
            "목" to DayOfWeek.THURSDAY,
            "금" to DayOfWeek.FRIDAY,
            "토" to DayOfWeek.SATURDAY,
            "일" to DayOfWeek.SUNDAY,
        )

        private const val DEFAULT_HOUR = 10
        private const val DEFAULT_MINUTE = 0

        private val TIME_PARTICLES = listOf(
            "안에", "까지는", "까지", "부터는", "부터",
            "에는", "에도", "에서", "쯤에", "에", "쯤",
        )
    }

    private val recurrencePatterns: List<Pair<Regex, (MatchResult) -> RecurrenceRule>> = listOf(
        // "매일"
        Regex("매일") to { _ ->
            RecurrenceRule(type = RecurrenceType.DAILY, interval = 1, hour = 9, minute = 0)
        },
        // "주말마다", "매 주말"
        Regex("주말마다|주말에\\s*매번|매\\s*주말") to { _ ->
            RecurrenceRule(
                type = RecurrenceType.WEEKLY, interval = 1,
                daysOfWeek = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                hour = 10, minute = 0,
            )
        },
        // "격주 X요일"
        Regex("격주\\s*(월|화|수|목|금|토|일)\\s*요?일?") to { m ->
            RecurrenceRule(
                type = RecurrenceType.WEEKLY, interval = 2,
                daysOfWeek = setOf(dayOfWeekFromName(m.groupValues[1])),
                hour = 10, minute = 0,
            )
        },
        // "매주 X요일"
        Regex("매주\\s*(월|화|수|목|금|토|일)\\s*요?일?") to { m ->
            RecurrenceRule(
                type = RecurrenceType.WEEKLY, interval = 1,
                daysOfWeek = setOf(dayOfWeekFromName(m.groupValues[1])),
                hour = 10, minute = 0,
            )
        },
        // "평일마다", "매 평일"
        Regex("평일마다|매\\s*평일|평일에\\s*매번") to { _ ->
            RecurrenceRule(
                type = RecurrenceType.WEEKLY, interval = 1,
                daysOfWeek = setOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                ),
                hour = 9, minute = 0,
            )
        },
        // "매달 N일", "매월 N일"
        Regex("매(?:달|월)\\s*(\\d{1,2})\\s*일") to { m ->
            val day = m.groupValues[1].toInt()
            RecurrenceRule(
                type = RecurrenceType.MONTHLY, interval = 1,
                dayOfMonth = day.coerceIn(1, 28),
                hour = 10, minute = 0,
            )
        },
    )

    /**
     * @param segmentResolver 생활 구간 리졸버. null이면 구간 매칭을 건너뜀.
     * @param calendarResolver 캘린더 컨텍스트 리졸버. null이면 캘린더 매칭을 건너뜀.
     */
    fun parse(
        input: String,
        segmentResolver: LifeSegmentResolver? = null,
        calendarResolver: CalendarContextResolver? = null,
    ): TimeParseResult {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return TimeParseResult.Buffered(trimmed)

        val now = nowProvider()

        // 0단계: 반복 키워드 감지
        val recurrenceResult = detectRecurrence(trimmed)
        val textToParse = recurrenceResult?.second ?: trimmed
        val recurrenceRule = recurrenceResult?.first

        // 1. FALLBACK_PATTERNS 먼저 확인 (키워드 탐욕 매칭 방지)
        val fallbackMatch = FALLBACK_PATTERNS.firstNotNullOfOrNull { it.find(textToParse) }

        if (fallbackMatch != null) {
            if (segmentResolver != null) {
                // 입력에 숫자 기반 명시적 시간이 있으면 그 시간이 우선
                // 단, 생활 구간 키워드가 함께 있으면 구간 매칭이 우선 (오프셋 파싱을 위해)
                val hasSegmentKeyword = segmentResolver.containsTrigger(textToParse)
                if (EXPLICIT_NUMERIC_TIME.containsMatchIn(textToParse) && !hasSegmentKeyword) {
                    val dateExtraction = extractDate(textToParse, now)
                    val timeExtraction = extractTime(textToParse, now)
                    if (timeExtraction != null) {
                        val result = buildResult(now, dateExtraction, timeExtraction)
                        val rawTitle = extractTitle(
                            textToParse,
                            dateExtraction?.second,
                            findTimeMatchInInput(textToParse, timeExtraction.hour, timeExtraction.minute),
                        )
                        val finalTitle = segmentResolver.stripTrigger(rawTitle)
                        return TimeParseResult.Scheduled(
                            title = finalTitle.ifBlank { trimmed },
                            scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                            recurrenceRule = recurrenceRule?.copy(
                                hour = result.hour,
                                minute = result.minute,
                            ),
                        )
                    }
                }
                // 명시적 시간 없음 → 생활 구간 매칭
                val segmentMatch = segmentResolver.resolve(textToParse)
                if (segmentMatch != null) {
                    val segLdt = Instant.fromEpochMilliseconds(segmentMatch.scheduledAt).toLocalDateTime(timeZone)
                    return TimeParseResult.Scheduled(
                        title = segmentMatch.title.ifBlank { trimmed },
                        scheduledAt = segmentMatch.scheduledAt,
                        confidence = if (segmentMatch.isPremiumAccuracy) 0.95f else 0.8f,
                        recurrenceRule = recurrenceRule?.copy(
                            hour = segLdt.hour,
                            minute = segLdt.minute,
                        ),
                    )
                }
            }
            // 8단계: 캘린더 컨텍스트 매칭
            if (calendarResolver != null) {
                val calMatch = calendarResolver.resolve(textToParse)
                if (calMatch != null) {
                    return if (calMatch.scheduledAt != null) {
                        TimeParseResult.Scheduled(
                            title = calMatch.title.ifBlank { trimmed },
                            scheduledAt = calMatch.scheduledAt,
                            confidence = 0.85f,
                            recurrenceRule = recurrenceRule,
                        )
                    } else {
                        TimeParseResult.BufferedWithHint(
                            title = calMatch.title.ifBlank { trimmed },
                            hint = calMatch.failReason ?: "",
                        )
                    }
                }
            }
            return TimeParseResult.NeedsFallback(textToParse, fallbackMatch.value)
        }

        // 2. 날짜/시간 파싱
        val dateExtraction = extractDate(textToParse, now)
        val timeExtraction = extractTime(textToParse, now)

        if (dateExtraction != null && timeExtraction != null) {
            // 날짜 + 시간 둘 다 있음
            val result = buildResult(now, dateExtraction, timeExtraction)
            val title = extractTitle(textToParse, dateExtraction.second,
                findTimeMatchInInput(textToParse, timeExtraction.hour, timeExtraction.minute))
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(
                    hour = result.hour,
                    minute = result.minute,
                ),
            )
        }

        if (dateExtraction != null && timeExtraction == null) {
            // 날짜만 있고 시간 없음 → 생활구간으로 시간 추출 시도
            if (segmentResolver != null) {
                val segmentMatch = segmentResolver.resolve(textToParse)
                if (segmentMatch != null) {
                    val segLdt = Instant.fromEpochMilliseconds(segmentMatch.scheduledAt).toLocalDateTime(timeZone)
                    val combined = LocalDateTime(
                        date = dateExtraction.first,
                        time = LocalTime(segLdt.hour, segLdt.minute, 0),
                    ).toInstant(timeZone).toEpochMilliseconds()

                    var title = extractTitle(textToParse, dateExtraction.second, null)
                    title = segmentResolver.stripTrigger(title)

                    return TimeParseResult.Scheduled(
                        title = title.ifBlank { trimmed },
                        scheduledAt = combined,
                        confidence = if (segmentMatch.isPremiumAccuracy) 0.95f else 0.8f,
                        recurrenceRule = recurrenceRule?.copy(
                            hour = segLdt.hour,
                            minute = segLdt.minute,
                        ),
                    )
                }
            }
            // 세그먼트 없으면 기본시간으로 반환
            val result = buildResult(now, dateExtraction, null)
            val title = extractTitle(textToParse, dateExtraction.second, null)
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(
                    hour = result.hour,
                    minute = result.minute,
                ),
            )
        }

        if (timeExtraction != null) {
            // 시간만 있고 날짜 없음
            val result = buildResult(now, null, timeExtraction)
            val title = extractTitle(textToParse, null,
                findTimeMatchInInput(textToParse, timeExtraction.hour, timeExtraction.minute))
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(
                    hour = result.hour,
                    minute = result.minute,
                ),
            )
        }

        // 3. 생활 구간 매칭 (FALLBACK_PATTERNS에 없는 트리거)
        if (segmentResolver != null) {
            val segmentMatch = segmentResolver.resolve(textToParse)
            if (segmentMatch != null) {
                val segLdt = Instant.fromEpochMilliseconds(segmentMatch.scheduledAt).toLocalDateTime(timeZone)
                return TimeParseResult.Scheduled(
                    title = segmentMatch.title.ifBlank { trimmed },
                    scheduledAt = segmentMatch.scheduledAt,
                    confidence = if (segmentMatch.isPremiumAccuracy) 0.95f else 0.8f,
                    recurrenceRule = recurrenceRule?.copy(
                        hour = segLdt.hour,
                        minute = segLdt.minute,
                    ),
                )
            }
        }

        // 8단계: 캘린더 컨텍스트 매칭 (FALLBACK_PATTERNS 외 트리거 — "전에" 등)
        if (calendarResolver != null) {
            val calMatch = calendarResolver.resolve(textToParse)
            if (calMatch != null) {
                return if (calMatch.scheduledAt != null) {
                    TimeParseResult.Scheduled(
                        title = calMatch.title.ifBlank { trimmed },
                        scheduledAt = calMatch.scheduledAt,
                        confidence = 0.85f,
                        recurrenceRule = recurrenceRule,
                    )
                } else {
                    TimeParseResult.BufferedWithHint(
                        title = calMatch.title.ifBlank { trimmed },
                        hint = calMatch.failReason ?: "",
                    )
                }
            }
        }

        // 4. 반복 키워드는 있었지만 시간 표현이 없는 경우 → 규칙의 기본 시각으로 첫 인스턴스 계산
        if (recurrenceRule != null) {
            val nextOccurrence = RecurrenceCalculator.nextOccurrence(recurrenceRule, now, timeZone)
            return TimeParseResult.Scheduled(
                title = textToParse.trim().ifBlank { trimmed },
                scheduledAt = nextOccurrence,
                recurrenceRule = recurrenceRule,
            )
        }

        return TimeParseResult.Buffered(trimmed)
    }

    private fun buildResult(
        now: Instant,
        dateExtraction: Pair<LocalDate, String>?,
        timeExtraction: TimeExtraction?,
    ): LocalDateTime {
        val nowLdt = now.toLocalDateTime(timeZone)

        val targetDate: LocalDate = when {
            dateExtraction != null -> dateExtraction.first
            timeExtraction?.relativeInstant != null -> timeExtraction.relativeInstant.toLocalDateTime(timeZone).date
            else -> nowLdt.date
        }

        val (hour, minute) = when {
            timeExtraction != null -> timeExtraction.hour to timeExtraction.minute
            else -> DEFAULT_HOUR to DEFAULT_MINUTE
        }

        return LocalDateTime(targetDate, LocalTime(hour, minute, 0))
    }

    /** 날짜 표현 추출. Pair<목표LocalDate, 매치된 문자열> */
    private fun extractDate(input: String, now: Instant): Pair<LocalDate, String>? {
        val nowDate = now.toLocalDateTime(timeZone).date

        // Priority 1: 절대 날짜 "4월 25일" 또는 "4/25"
        val absoluteMonthDay = Regex("""(\d{1,2})월\s*(\d{1,2})일""")
        absoluteMonthDay.find(input)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            var targetDate = LocalDate(nowDate.year, month, day)
            if (targetDate < nowDate) targetDate = targetDate.plus(DatePeriod(years = 1))
            return Pair(targetDate, m.value)
        }

        val slashDate = Regex("""(\d{1,2})/(\d{1,2})""")
        slashDate.find(input)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            var targetDate = LocalDate(nowDate.year, month, day)
            if (targetDate < nowDate) targetDate = targetDate.plus(DatePeriod(years = 1))
            return Pair(targetDate, m.value)
        }

        // Priority 2: 상대 날짜
        val relativeDatePattern = Regex("""(글피|모레|내일|오늘)""")
        relativeDatePattern.find(input)?.let { m ->
            val targetDate = when (m.value) {
                "오늘" -> nowDate
                "내일" -> nowDate.plus(DatePeriod(days = 1))
                "모레" -> nowDate.plus(DatePeriod(days = 2))
                "글피" -> nowDate.plus(DatePeriod(days = 3))
                else -> nowDate
            }
            return Pair(targetDate, m.value)
        }

        // Priority 2.5: "이번/다음 달 D일" 또는 "D일" 단독
        // (?!요|간|차|째) → "일요일", "5일간", "3일차", "2일째" 등 비날짜 표현 제외
        val dayOnlyPattern = Regex("""(다음\s*달|이번\s*달)?\s*(\d{1,2})일(?!요|간|차|째)""")
        dayOnlyPattern.find(input)?.let { m ->
            val prefix = m.groupValues[1].trim()
            val day = m.groupValues[2].toInt()
            if (day !in 1..31) return@let

            val targetDate: LocalDate = when {
                prefix.contains("다음") -> {
                    val nextMonth = nowDate.plus(DatePeriod(months = 1))
                    LocalDate(nextMonth.year, nextMonth.month, day)
                }
                prefix.contains("이번") -> {
                    LocalDate(nowDate.year, nowDate.month, day)
                }
                else -> {
                    var d = LocalDate(nowDate.year, nowDate.month, day)
                    if (d < nowDate) d = d.plus(DatePeriod(months = 1))
                    d
                }
            }
            return Pair(targetDate, m.value)
        }

        // Priority 3: 요일 + "이번주"/"다음주"
        val dayOfWeekPattern = Regex("""(다음\s*주|다음주|이번\s*주|이번주)?\s*(월|화|수|목|금|토|일)요일?(까지)?""")
        dayOfWeekPattern.find(input)?.let { m ->
            val prefix = m.groupValues[1].trim()
            val dayKor = m.groupValues[2]
            val targetDow = DAY_OF_WEEK_MAP[dayKor] ?: return@let

            val todayDow = nowDate.dayOfWeek
            var daysToAdd = (targetDow.ordinal - todayDow.ordinal + 7) % 7
            when {
                prefix.contains("다음") -> {
                    if (daysToAdd == 0) daysToAdd = 7
                    else daysToAdd += 7
                }
                prefix.contains("이번") -> {
                    if (daysToAdd == 0) daysToAdd = 7
                }
                else -> {
                    if (daysToAdd == 0) daysToAdd = 7
                }
            }

            return Pair(nowDate.plus(DatePeriod(days = daysToAdd)), m.value)
        }

        // Priority 4: "다음 주" / "이번 주" 단독 (요일 없음)
        val nextWeekPattern = Regex("""다음\s*주""")
        nextWeekPattern.find(input)?.let { m ->
            val todayDow = nowDate.dayOfWeek
            val daysToNextMonday = (DayOfWeek.MONDAY.ordinal - todayDow.ordinal + 7) % 7
            val daysToAdd = if (daysToNextMonday == 0) 7 else daysToNextMonday + 7
            return Pair(nowDate.plus(DatePeriod(days = daysToAdd)), m.value)
        }

        val thisWeekPattern = Regex("""이번\s*주""")
        thisWeekPattern.find(input)?.let { m ->
            val todayDow = nowDate.dayOfWeek
            val daysToFriday = (DayOfWeek.FRIDAY.ordinal - todayDow.ordinal + 7) % 7
            val daysToAdd = if (daysToFriday == 0) 7 else daysToFriday
            return Pair(nowDate.plus(DatePeriod(days = daysToAdd)), m.value)
        }

        return null
    }

    /** 시간 표현 추출 */
    private fun extractTime(input: String, now: Instant): TimeExtraction? {
        val relativeTimePattern = Regex("""(\d+)\s*(분|시간)\s*(?:뒤|후)""")
        relativeTimePattern.find(input)?.let { m ->
            val amount = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            val future = when (unit) {
                "분" -> now + amount.minutes
                "시간" -> now + amount.hours
                else -> now
            }
            val futureLdt = future.toLocalDateTime(timeZone)
            return TimeExtraction(futureLdt.hour, futureLdt.minute, future)
        }

        val contextHourPattern = Regex("""(새벽|아침|오전|점심|낮|오후|저녁|밤)?\s*(\d{1,2})\s*시\s*(반|(\d{1,2})\s*분)?""")
        contextHourPattern.find(input)?.let { m ->
            val context = m.groupValues[1]
            val hour = m.groupValues[2].toInt()
            val minutePart = m.groupValues[3]
            val minute = when {
                minutePart == "반" -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                else -> 0
            }
            val adjustedHour = adjustHour(hour, context)
            return TimeExtraction(adjustedHour, minute)
        }

        val contextOnlyPattern = Regex("""(새벽|아침|오전|점심|낮|오후|저녁|밤)에?\s""")
        contextOnlyPattern.find(input)?.let { m ->
            val context = m.groupValues[1]
            val hour = TIME_KEYWORD_HOUR[context] ?: return@let
            return TimeExtraction(hour, 0)
        }

        return null
    }

    private data class TimeExtraction(
        val hour: Int,
        val minute: Int,
        val relativeInstant: Instant? = null,
    )

    private fun adjustHour(hour: Int, context: String): Int {
        return when {
            hour >= 13 -> hour
            context in listOf("오후", "저녁", "밤", "낮") -> {
                if (hour == 12) 12 else hour + 12
            }
            context in listOf("오전", "아침", "새벽") -> {
                if (hour == 12) 0 else hour
            }
            context == "점심" -> 12
            context.isEmpty() -> {
                if (hour in 1..9) hour + 12 else hour
            }
            else -> hour
        }
    }

    /** 입력에서 시간/날짜 표현을 제거하고 제목만 반환 */
    private fun extractTitle(input: String, dateMatch: String?, timeMatchStr: String?): String {
        var result = input

        val relTime = Regex("""(\d+)\s*(?:분|시간)\s*(?:뒤|후)(?:에|쯤에?|쯤)?""")
        result = relTime.replace(result, "")

        val contextHour = Regex("""(?:새벽|아침|오전|점심|낮|오후|저녁|밤)?\s*\d{1,2}\s*시\s*(?:반|\d{1,2}\s*분)?(?:에|까지|부터|쯤)?""")
        result = contextHour.replace(result, "")

        val contextOnly = Regex("""(?:새벽|아침|오전|점심|낮|오후|저녁|밤)(?:에|에서|쯤)?(?=\s|$)""")
        result = contextOnly.replace(result, "")

        val datePat = Regex(
            """(?:\d{1,2}월\s*\d{1,2}일|\d{1,2}/\d{1,2}|글피|모레|내일|오늘|""" +
            """(?:다음\s*달|이번\s*달)?\s*\d{1,2}일(?!요|간|차|째)|""" +
            """(?:다음\s*주|다음주|이번\s*주|이번주)?\s*(?:월|화|수|목|금|토|일)요일?)""" +
            """(?:까지는?|부터는?|에는?|에도|에서|에|쯤에?|쯤|안에)?"""
        )
        result = datePat.replace(result, "")

        val nextWeekPat = Regex("""(?:다음\s*주|다음주|이번\s*주|이번주)(?:까지는?|부터는?|에는?|에|쯤)?""")
        result = nextWeekPat.replace(result, "")

        val leftoverParticles = Regex("""^\s*(?:안에|쯤에|까지는|까지|부터는|부터|에는|에도|에서|에)(?=\s|$)\s*""")
        result = leftoverParticles.replace(result, "")

        result = result.replace(Regex("""\s+"""), " ").trim()

        return if (result.isBlank()) input.trim() else result
    }

    /** 시간 추출 결과와 매치된 원본 문자열 */
    private fun findTimeMatchInInput(input: String, hour: Int, minute: Int): String? {
        val contextHour = Regex("""(새벽|아침|오전|점심|낮|오후|저녁|밤)?\s*(\d{1,2})\s*시\s*(반|(\d{1,2})\s*분)?""")
        return contextHour.find(input)?.value
    }

    private fun detectRecurrence(input: String): Pair<RecurrenceRule, String>? {
        for ((pattern, ruleBuilder) in recurrencePatterns) {
            val match = pattern.find(input) ?: continue
            val rule = ruleBuilder(match)
            val remaining = input.removeRange(match.range).trim().replace(Regex("\\s+"), " ")
            return Pair(rule, remaining)
        }
        return null
    }
}

private fun dayOfWeekFromName(name: String): DayOfWeek = when (name) {
    "일" -> DayOfWeek.SUNDAY
    "월" -> DayOfWeek.MONDAY
    "화" -> DayOfWeek.TUESDAY
    "수" -> DayOfWeek.WEDNESDAY
    "목" -> DayOfWeek.THURSDAY
    "금" -> DayOfWeek.FRIDAY
    "토" -> DayOfWeek.SATURDAY
    else -> DayOfWeek.MONDAY
}

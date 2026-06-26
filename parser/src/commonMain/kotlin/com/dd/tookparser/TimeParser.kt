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

        /** 캘린더 컨텍스트를 암시하는 키워드 */
        private val CALENDAR_TRIGGER_WORDS = listOf(
            "끝나고", "끝나면", "끝난 후", "다녀와서", "다녀오고",
        )
        private val CALENDAR_TRIGGER_BEFORE = Regex("""\S+\s*(?:전에|후에)""")

        private val WEEKDAY_DAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        private val WEEKEND_DAYS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        private val DAY_CHAR = Regex("[월화수목금토일]")

        // "월, 화, 수" / "월·화·수" / "월요일, 화요일" — 구분자로 2개 이상
        private val WEEKDAY_LIST_REGEX = Regex(
            """(격주|매\s*주)?\s*""" +
            """([월화수목금토일](?:요일)?(?:\s*(?:[,，、·・/]|와|과)\s*[월화수목금토일](?:요일)?)+)"""
        )

        // 평일 / 주말 ('주말농장'류 복합어 오탐을 lookahead로 차단)
        private val WEEKDAY_KEYWORD_REGEX = Regex(
            """(격주|매\s*주|매)?\s*(평일|주말)(마다|에\s*매번)?""" +
            """(?=\s|$|에|까지|부터|[,.]|\d|새벽|아침|오전|점심|낮|오후|저녁|밤)"""
        )

        private const val DEFAULT_RECUR_HOUR = 9
    }

    private val recurrencePatterns: List<Pair<Regex, (MatchResult) -> RecurrenceRule>> = listOf(
        // "매일"
        Regex("매일") to { _ ->
            RecurrenceRule(type = RecurrenceType.DAILY, interval = 1, hour = 9, minute = 0)
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
        // segmentResolver가 없으면 프리미엄으로 간주 (하위 호환)
        val isPremium = segmentResolver?.isPremium ?: true
        val triggers = mutableSetOf<PlusFeature>()

        // ── 다중 요일 / 평일·주말 fan-out (가장 먼저) ──
        detectWeekdaySet(trimmed)?.let { detection ->
            return buildWeekdayFanOut(detection, now, trimmed, segmentResolver)
        }

        // 0단계: 반복 키워드 감지
        val recurrenceResult = detectRecurrence(trimmed)
        val textToParse = recurrenceResult?.second ?: trimmed
        val recurrenceRule = recurrenceResult?.first

        // 무료 유저가 반복 기능을 사용하려 할 때 즉시 차단
        if (recurrenceRule != null && !isPremium) {
            triggers.add(PlusFeature.RECURRENCE)
            return TimeParseResult.Buffered(trimmed, triggeredPlusFeatures = triggers)
        }

        // 주간 반복은 nextOccurrence로 첫 알림 요일을 보장
        if (recurrenceRule != null && recurrenceRule.type == RecurrenceType.WEEKLY) {
            return buildWeeklyResult(textToParse, recurrenceRule, now, trimmed, triggers, segmentResolver)
        }

        // 1. FALLBACK_PATTERNS 먼저 확인 (키워드 탐욕 매칭 방지)
        val fallbackMatch = FALLBACK_PATTERNS.firstNotNullOfOrNull { it.find(textToParse) }

        if (fallbackMatch != null) {
            if (segmentResolver != null) {
                // 명시적 숫자 시간이 있으면 그 시간 우선
                if (EXPLICIT_NUMERIC_TIME.containsMatchIn(textToParse)) {
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
                            triggeredPlusFeatures = triggers,
                        )
                    }
                }
                // 명시적 시간 없음 → 생활 구간 매칭
                val segmentMatch = segmentResolver.resolve(textToParse)
                if (segmentMatch != null) {
                    if (segmentMatch.isBlocked) {
                        triggers.add(PlusFeature.LIFESTYLE_SEGMENT)
                        // 차단됨 → 캘린더 확인으로 fall-through
                    } else {
                        if (segmentMatch.segment.isPremium) triggers.add(PlusFeature.LIFESTYLE_SEGMENT)
                        val segLdt = Instant.fromEpochMilliseconds(segmentMatch.scheduledAt)
                            .toLocalDateTime(timeZone)
                        return TimeParseResult.Scheduled(
                            title = segmentMatch.title.ifBlank { trimmed },
                            scheduledAt = segmentMatch.scheduledAt,
                            confidence = if (segmentMatch.isPremiumAccuracy) 0.95f else 0.8f,
                            recurrenceRule = recurrenceRule?.copy(
                                hour = segLdt.hour,
                                minute = segLdt.minute,
                            ),
                            matchedSegment = segmentMatch.segment,
                            triggeredPlusFeatures = triggers,
                        )
                    }
                }
            }
            // 캘린더 컨텍스트 매칭
            if (calendarResolver != null) {
                val calMatch = calendarResolver.resolve(textToParse)
                if (calMatch != null) {
                    return if (calMatch.scheduledAt != null) {
                        TimeParseResult.Scheduled(
                            title = calMatch.title.ifBlank { trimmed },
                            scheduledAt = calMatch.scheduledAt,
                            confidence = 0.85f,
                            recurrenceRule = recurrenceRule,
                            triggeredPlusFeatures = triggers,
                        )
                    } else {
                        TimeParseResult.BufferedWithHint(
                            title = calMatch.title.ifBlank { trimmed },
                            hint = calMatch.failReason ?: "",
                            triggeredPlusFeatures = triggers,
                        )
                    }
                }
            }
            // 무료 유저 + 캘린더 트리거 단어 포함
            if (!isPremium && containsCalendarTriggerWords(textToParse)) {
                triggers.add(PlusFeature.CALENDAR_CONTEXT)
            }
            if (triggers.isNotEmpty()) {
                return TimeParseResult.Buffered(trimmed, triggeredPlusFeatures = triggers)
            }
            return TimeParseResult.NeedsFallback(textToParse, fallbackMatch.value)
        }

        // ── 비-Fallback 경로 ──────────────────────────────────────────────
        val dateExtraction = extractDate(textToParse, now)
        val hasExplicitNumericTime = EXPLICIT_NUMERIC_TIME.containsMatchIn(textToParse)

        // 명시적 숫자 시간이 없을 때만 세그먼트 우선 시도
        var resolvedSegment: SegmentMatch? = null
        var blockedBySegment = false
        if (segmentResolver != null && !hasExplicitNumericTime) {
            val sm = segmentResolver.resolve(textToParse)
            if (sm != null) {
                if (sm.isBlocked) {
                    triggers.add(PlusFeature.LIFESTYLE_SEGMENT)
                    blockedBySegment = true
                } else {
                    if (sm.segment.isPremium) triggers.add(PlusFeature.LIFESTYLE_SEGMENT)
                    resolvedSegment = sm
                }
            }
        }

        // 세그먼트가 차단됐거나 없을 때만 extractTime 사용
        val timeExtraction = if (resolvedSegment == null && !blockedBySegment) {
            extractTime(textToParse, now)
        } else null

        // 날짜 + 세그먼트
        if (dateExtraction != null && resolvedSegment != null) {
            val segLdt = Instant.fromEpochMilliseconds(resolvedSegment.scheduledAt)
                .toLocalDateTime(timeZone)
            val combined = LocalDateTime(
                date = dateExtraction.first,
                time = LocalTime(segLdt.hour, segLdt.minute, 0),
            ).toInstant(timeZone).toEpochMilliseconds()
            var title = extractTitle(textToParse, dateExtraction.second, null)
            title = segmentResolver!!.stripTrigger(title)
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = combined,
                confidence = if (resolvedSegment.isPremiumAccuracy) 0.95f else 0.8f,
                recurrenceRule = recurrenceRule?.copy(hour = segLdt.hour, minute = segLdt.minute),
                matchedSegment = resolvedSegment.segment,
                triggeredPlusFeatures = triggers,
            )
        }

        // 날짜 + 명시적 시간
        if (dateExtraction != null && timeExtraction != null) {
            val result = buildResult(now, dateExtraction, timeExtraction)
            val title = extractTitle(
                textToParse, dateExtraction.second,
                findTimeMatchInInput(textToParse, timeExtraction.hour, timeExtraction.minute),
            )
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(hour = result.hour, minute = result.minute),
                triggeredPlusFeatures = triggers,
            )
        }

        // 날짜만 있고 시간 없음 (세그먼트 차단됐거나 아예 없음)
        if (dateExtraction != null && resolvedSegment == null && timeExtraction == null) {
            if (blockedBySegment) {
                // 날짜는 있지만 시간을 Plus 기능에서만 얻을 수 있음
                return TimeParseResult.Buffered(trimmed, triggeredPlusFeatures = triggers)
            }
            val result = buildResult(now, dateExtraction, null)
            val title = extractTitle(textToParse, dateExtraction.second, null)
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(hour = result.hour, minute = result.minute),
                triggeredPlusFeatures = triggers,
            )
        }

        // 세그먼트만 있음 (날짜 없음)
        if (resolvedSegment != null) {
            val segLdt = Instant.fromEpochMilliseconds(resolvedSegment.scheduledAt)
                .toLocalDateTime(timeZone)
            return TimeParseResult.Scheduled(
                title = resolvedSegment.title.ifBlank { trimmed },
                scheduledAt = resolvedSegment.scheduledAt,
                confidence = if (resolvedSegment.isPremiumAccuracy) 0.95f else 0.8f,
                recurrenceRule = recurrenceRule?.copy(hour = segLdt.hour, minute = segLdt.minute),
                matchedSegment = resolvedSegment.segment,
                triggeredPlusFeatures = triggers,
            )
        }

        // 시간만 있음 (날짜 없음, 세그먼트 없음)
        if (timeExtraction != null) {
            val result = buildResult(now, null, timeExtraction)
            val title = extractTitle(
                textToParse, null,
                findTimeMatchInInput(textToParse, timeExtraction.hour, timeExtraction.minute),
            )
            return TimeParseResult.Scheduled(
                title = title.ifBlank { trimmed },
                scheduledAt = result.toInstant(timeZone).toEpochMilliseconds(),
                recurrenceRule = recurrenceRule?.copy(hour = result.hour, minute = result.minute),
                triggeredPlusFeatures = triggers,
            )
        }

        // 캘린더 컨텍스트 매칭 (FALLBACK_PATTERNS 외 트리거 — "전에" 등)
        if (calendarResolver != null) {
            val calMatch = calendarResolver.resolve(textToParse)
            if (calMatch != null) {
                return if (calMatch.scheduledAt != null) {
                    TimeParseResult.Scheduled(
                        title = calMatch.title.ifBlank { trimmed },
                        scheduledAt = calMatch.scheduledAt,
                        confidence = 0.85f,
                        recurrenceRule = recurrenceRule,
                        triggeredPlusFeatures = triggers,
                    )
                } else {
                    TimeParseResult.BufferedWithHint(
                        title = calMatch.title.ifBlank { trimmed },
                        hint = calMatch.failReason ?: "",
                        triggeredPlusFeatures = triggers,
                    )
                }
            }
        }
        if (!isPremium && containsCalendarTriggerWords(textToParse)) {
            triggers.add(PlusFeature.CALENDAR_CONTEXT)
        }

        // 반복 키워드는 있었지만 시간 표현이 없는 경우
        // (isPremium=true 케이스만 여기 도달 — !isPremium은 함수 시작에서 이미 반환)
        if (recurrenceRule != null) {
            val nextOccurrence = RecurrenceCalculator.nextOccurrence(recurrenceRule, now, timeZone)
            return TimeParseResult.Scheduled(
                title = textToParse.trim().ifBlank { trimmed },
                scheduledAt = nextOccurrence,
                recurrenceRule = recurrenceRule,
                triggeredPlusFeatures = triggers,
            )
        }

        if (triggers.isNotEmpty()) {
            return TimeParseResult.Buffered(trimmed, triggeredPlusFeatures = triggers)
        }

        return TimeParseResult.Buffered(trimmed)
    }

    private fun buildWeeklyResult(
        textToParse: String,
        rule: RecurrenceRule,
        now: Instant,
        originalTrimmed: String,
        triggers: Set<PlusFeature>,
        segmentResolver: LifeSegmentResolver?,
    ): TimeParseResult {
        // 명시적 시간이 있으면 규칙 시각으로 덮어쓰고, 없으면 규칙 기본 시각 유지
        val timeExtraction = extractTime(textToParse, now)
        val ruleWithTime = if (timeExtraction != null) {
            rule.copy(hour = timeExtraction.hour, minute = timeExtraction.minute)
        } else rule

        val scheduledAt = RecurrenceCalculator.nextOccurrence(ruleWithTime, now, timeZone)

        val timeMatch = timeExtraction?.let { findTimeMatchInInput(textToParse, it.hour, it.minute) }
        var title = extractTitle(textToParse, null, timeMatch)
        if (segmentResolver != null) title = segmentResolver.stripTrigger(title)

        return TimeParseResult.Scheduled(
            title = title.ifBlank { originalTrimmed },
            scheduledAt = scheduledAt,
            confidence = 0.9f,
            recurrenceRule = ruleWithTime,
            triggeredPlusFeatures = triggers,
        )
    }

    private fun buildWeekdayFanOut(
        detection: WeekdaySetDetection,
        now: Instant,
        originalTrimmed: String,
        segmentResolver: LifeSegmentResolver?,
    ): TimeParseResult {
        val textToParse = detection.remaining
        val timeExtraction = extractTime(textToParse, now)
        val hour = timeExtraction?.hour ?: DEFAULT_RECUR_HOUR
        val minute = timeExtraction?.minute ?: 0

        val timeMatch = timeExtraction?.let { findTimeMatchInInput(textToParse, it.hour, it.minute) }
        var title = extractTitle(textToParse, null, timeMatch)
        if (segmentResolver != null) title = segmentResolver.stripTrigger(title)
        val finalTitle = title.ifBlank { originalTrimmed }

        // 요일별 다음 발생 시각 계산 → 가까운 순 정렬
        val occurrences = detection.days.map { day ->
            val perDayRule = RecurrenceRule(
                type = RecurrenceType.WEEKLY,
                interval = detection.interval,
                daysOfWeek = setOf(day),
                hour = hour,
                minute = minute,
            )
            ScheduledOccurrence(
                scheduledAt = RecurrenceCalculator.nextOccurrence(perDayRule, now, timeZone),
                recurrenceRule = if (detection.recurring) perDayRule else null,
            )
        }.sortedBy { it.scheduledAt }

        val first = occurrences.first()
        return TimeParseResult.Scheduled(
            title = finalTitle,
            scheduledAt = first.scheduledAt,
            confidence = 0.9f,
            recurrenceRule = first.recurrenceRule,
            triggeredPlusFeatures = if (detection.recurring) setOf(PlusFeature.RECURRENCE) else emptySet(),
            additionalOccurrences = occurrences.drop(1),
        )
    }

    private fun containsCalendarTriggerWords(input: String): Boolean =
        CALENDAR_TRIGGER_WORDS.any { input.contains(it) } ||
            CALENDAR_TRIGGER_BEFORE.containsMatchIn(input)

    private fun buildResult(
        now: Instant,
        dateExtraction: Pair<LocalDate, String>?,
        timeExtraction: TimeExtraction?,
    ): LocalDateTime {
        val nowLdt = now.toLocalDateTime(timeZone)

        // "X분/시간 뒤" with no explicit date: preserve full sub-minute precision
        if (timeExtraction?.relativeInstant != null && dateExtraction == null) {
            return timeExtraction.relativeInstant.toLocalDateTime(timeZone)
        }

        // AM/PM 미명시 + 날짜 미지정: "가장 가까운 미래" 룰 적용
        if (timeExtraction != null
            && !timeExtraction.isContextExplicit
            && dateExtraction == null
            && timeExtraction.relativeInstant == null
        ) {
            return resolveAmbiguousHour(now, timeExtraction.hour, timeExtraction.minute)
        }

        val targetDate: LocalDate = when {
            dateExtraction != null -> dateExtraction.first
            timeExtraction?.relativeInstant != null -> timeExtraction.relativeInstant.toLocalDateTime(timeZone).date
            else -> nowLdt.date
        }

        val (hour, minute) = when {
            timeExtraction != null -> {
                val h = if (timeExtraction.isContextExplicit) {
                    timeExtraction.hour
                } else {
                    adjustHour(timeExtraction.hour, "")
                }
                h to timeExtraction.minute
            }
            else -> DEFAULT_HOUR to DEFAULT_MINUTE
        }

        return LocalDateTime(targetDate, LocalTime(hour, minute, 0))
    }

    /**
     * AM/PM이 명시되지 않은 "X시" 입력을 가장 가까운 미래 시각으로 해석한다.
     */
    private fun resolveAmbiguousHour(now: Instant, rawHour: Int, minute: Int): LocalDateTime {
        val today = now.toLocalDateTime(timeZone).date
        val tomorrow = today.plus(DatePeriod(days = 1))

        val candidates = buildList {
            when (rawHour) {
                in 1..9 -> {
                    add(LocalDateTime(today, LocalTime(rawHour + 12, minute, 0)))
                    add(LocalDateTime(tomorrow, LocalTime(rawHour + 12, minute, 0)))
                }
                in 10..11 -> {
                    add(LocalDateTime(today, LocalTime(rawHour, minute, 0)))
                    add(LocalDateTime(today, LocalTime(rawHour + 12, minute, 0)))
                    add(LocalDateTime(tomorrow, LocalTime(rawHour, minute, 0)))
                    add(LocalDateTime(tomorrow, LocalTime(rawHour + 12, minute, 0)))
                }
                12 -> {
                    add(LocalDateTime(today, LocalTime(12, minute, 0)))
                    add(LocalDateTime(tomorrow, LocalTime(12, minute, 0)))
                }
            }
        }.sorted()

        return candidates.firstOrNull { it.toInstant(timeZone) > now } ?: candidates.last()
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
            if (context.isEmpty() && hour in 1..12) {
                return TimeExtraction(hour, minute, isContextExplicit = false)
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
        val isContextExplicit: Boolean = true,
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

    private data class WeekdaySetDetection(
        val days: Set<DayOfWeek>,
        val recurring: Boolean,   // 매주/격주/매/마다 마커가 있으면 true
        val interval: Int,        // 1 또는 2(격주)
        val remaining: String,
    )

    private fun detectWeekdaySet(input: String): WeekdaySetDetection? {
        // 평일 / 주말 키워드
        WEEKDAY_KEYWORD_REGEX.find(input)?.let { m ->
            val prefix = m.groupValues[1].replace(" ", "")
            val recurring = prefix.isNotEmpty() || m.groupValues[3].isNotEmpty()
            val interval = if (prefix.contains("격주")) 2 else 1
            val isWeekend = m.groupValues[2] == "주말"
            return WeekdaySetDetection(
                days = if (isWeekend) WEEKEND_DAYS else WEEKDAY_DAYS,
                recurring = recurring,
                interval = interval,
                remaining = input.removeRange(m.range).trim().replace(Regex("\\s+"), " "),
            )
        }
        // 구분자 나열 목록
        WEEKDAY_LIST_REGEX.find(input)?.let { m ->
            val days = extractDaysFromListSpan(m.groupValues[2])
            if (days.size < 2) return@let
            val prefix = m.groupValues[1].replace(" ", "")
            return WeekdaySetDetection(
                days = days,
                recurring = prefix.isNotEmpty(),
                interval = if (prefix.contains("격주")) 2 else 1,
                remaining = input.removeRange(m.range).trim().replace(Regex("\\s+"), " "),
            )
        }
        return null
    }

    private fun extractDaysFromListSpan(span: String): Set<DayOfWeek> =
        DAY_CHAR.findAll(span.replace("요일", ""))   // '일요일'의 일→SUNDAY 오탐 방지
            .mapNotNull { DAY_OF_WEEK_MAP[it.value] }
            .toSet()
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

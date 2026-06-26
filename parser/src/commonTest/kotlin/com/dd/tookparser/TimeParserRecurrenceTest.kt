package com.dd.tookparser

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeParserRecurrenceTest {

    private val parser = TimeParser()
    private val tz = TimeZone.currentSystemDefault()

    /** 첫 일정 + 추가 발생을 하나의 리스트로 */
    private fun allOccurrences(s: TimeParseResult.Scheduled): List<ScheduledOccurrence> =
        listOf(ScheduledOccurrence(s.scheduledAt, s.recurrenceRule)) + s.additionalOccurrences

    /** 모든 발생의 실제 요일 집합 (단발/반복 무관, scheduledAt 기준) */
    private fun occurrenceDays(s: TimeParseResult.Scheduled): Set<DayOfWeek> =
        allOccurrences(s)
            .map { Instant.fromEpochMilliseconds(it.scheduledAt).toLocalDateTime(tz).dayOfWeek }
            .toSet()

    /** 첫 일정의 시각(hour) */
    private fun occurrenceHour(s: TimeParseResult.Scheduled): Int =
        Instant.fromEpochMilliseconds(s.scheduledAt).toLocalDateTime(tz).hour

    @Test
    fun `매일 스트레칭 - DAILY 반복`() {
        val result = parser.parse("매일 스트레칭")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("스트레칭", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.DAILY, s.recurrenceRule!!.type)
    }

    @Test
    fun `매주 금요일 보고서 - WEEKLY 금`() {
        val result = parser.parse("매주 금요일 보고서")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("보고서", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.WEEKLY, s.recurrenceRule!!.type)
        assertEquals(setOf(DayOfWeek.FRIDAY), s.recurrenceRule!!.daysOfWeek)
    }

    @Test
    fun `격주 수요일 미팅 - interval 2`() {
        val result = parser.parse("격주 수요일 미팅")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("미팅", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(2, s.recurrenceRule!!.interval)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), s.recurrenceRule!!.daysOfWeek)
    }

    @Test
    fun `매달 1일 월세 - MONTHLY`() {
        val result = parser.parse("매달 1일 월세")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("월세", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.MONTHLY, s.recurrenceRule!!.type)
        assertEquals(1, s.recurrenceRule!!.dayOfMonth)
    }

    @Test
    fun `매일 아침 7시 스트레칭 - 반복 + 명시적 시간`() {
        val result = parser.parse("매일 아침 7시 스트레칭")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("스트레칭", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.DAILY, s.recurrenceRule!!.type)
        assertEquals(7, s.recurrenceRule!!.hour)
        assertEquals(0, s.recurrenceRule!!.minute)
    }

    @Test
    fun `반복 키워드 없는 일반 입력 - recurrenceRule null`() {
        val result = parser.parse("7시 다이소 그릇")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNull(s.recurrenceRule)
    }

    @Test
    fun `반복 키워드 없는 버퍼 입력 - 기존과 동일`() {
        val result = parser.parse("뚜띠 간식")
        assertIs<TimeParseResult.Buffered>(result)
    }

    // ── 다중 요일 / 평일·주말 fan-out ──────────────────────────────

    @Test
    fun `다중 요일 단발 - 반복 키워드 없으면 첫 일정 단발`() {
        val input = "금, 토, 일 오후 11시에 알림 테스트"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        assertNull(s.recurrenceRule)
        assertEquals(2, s.additionalOccurrences.size)
        assertTrue(s.additionalOccurrences.all { it.recurrenceRule == null })
        assertEquals(
            setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            occurrenceDays(s),
        )
        assertEquals(23, occurrenceHour(s))
    }

    @Test
    fun `다중 요일 반복 - 매주 키워드면 요일별 매주 반복`() {
        val input = "매주 금, 토, 일 오후 11시에 X"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        val all = allOccurrences(s)
        assertEquals(3, all.size)
        assertTrue(all.all { it.recurrenceRule != null })
        assertTrue(all.all { it.recurrenceRule!!.daysOfWeek.size == 1 })
        assertEquals(
            setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            all.flatMap { it.recurrenceRule!!.daysOfWeek }.toSet(),
        )
    }

    @Test
    fun `격주 다중 요일 - 각 규칙 interval 2`() {
        val input = "격주 금, 토 회의"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        val all = allOccurrences(s)
        assertEquals(2, all.size)
        assertTrue(all.all { it.recurrenceRule!!.interval == 2 })
    }

    @Test
    fun `평일 단발 - 월-금 5개 모두 단발`() {
        val input = "평일 오전 9시 약먹기"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        val all = allOccurrences(s)
        assertEquals(5, all.size)
        assertTrue(all.all { it.recurrenceRule == null })
        assertEquals(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            occurrenceDays(s),
        )
        assertEquals(9, occurrenceHour(s))
    }

    @Test
    fun `평일마다 반복 - 월-금 5개 모두 반복`() {
        val input = "평일마다 약먹기"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        val all = allOccurrences(s)
        assertEquals(5, all.size)
        assertTrue(all.all { it.recurrenceRule != null })
    }

    @Test
    fun `주말 단발 - 토일 2개 모두 단발`() {
        val input = "주말 청소"
        val s = assertIs<TimeParseResult.Scheduled>(parser.parse(input))
        val all = allOccurrences(s)
        assertEquals(2, all.size)
        assertTrue(all.all { it.recurrenceRule == null })
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), occurrenceDays(s))
    }

    @Test
    fun `다중 요일 정렬 - 금요일 기준 오름차순이고 첫 일정은 금요일`() {
        // 2026-05-01은 금요일
        val friday = LocalDateTime(2026, 5, 1, 8, 0, 0).toInstant(tz)
        val fridayParser = TimeParser(nowProvider = { friday }, timeZone = tz)
        val input = "금, 토, 일 11시"
        val s = assertIs<TimeParseResult.Scheduled>(fridayParser.parse(input))
        val times = allOccurrences(s).map { it.scheduledAt }
        assertEquals(times.sorted(), times)
        val firstDow = Instant.fromEpochMilliseconds(times.first()).toLocalDateTime(tz).dayOfWeek
        assertEquals(DayOfWeek.FRIDAY, firstDow)
    }

    @Test
    fun `오탐 가드 - 주말농장은 주간 반복이 아니다`() {
        val result = parser.parse("주말농장 가기")
        assertIs<TimeParseResult.Buffered>(result)
    }

    @Test
    fun `오탐 가드 - 월 나열 날짜는 주간 반복이 아니다`() {
        val result = parser.parse("4월, 5월에 정산")
        if (result is TimeParseResult.Scheduled) {
            assertTrue(result.recurrenceRule?.type != RecurrenceType.WEEKLY)
        }
    }
}

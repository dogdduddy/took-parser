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

    @Test
    fun `매일 스트레칭 - DAILY 반복`() {
        val result = parser.parse("매일 스트레칭")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("스트레칭", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.DAILY, s.recurrenceRule!!.type)
    }

    @Test
    fun `주말마다 청소 - WEEKLY 토일`() {
        val result = parser.parse("주말마다 청소")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("청소", s.title)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.WEEKLY, s.recurrenceRule!!.type)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), s.recurrenceRule!!.daysOfWeek)
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
    fun `평일마다 출근 보고 - 월-금`() {
        val result = parser.parse("평일마다 출근 보고")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(5, s.recurrenceRule!!.daysOfWeek.size)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            s.recurrenceRule!!.daysOfWeek,
        )
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

    @Test
    fun `여러 요일 나열 + 명시적 시간 - WEEKLY 다중 요일`() {
        val result = parser.parse("월, 화, 수, 목, 토 오후 11시에 중고등부 톡방 링크올리기")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(RecurrenceType.WEEKLY, s.recurrenceRule!!.type)
        assertEquals(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.SATURDAY,
            ),
            s.recurrenceRule!!.daysOfWeek,
        )
        assertEquals(23, s.recurrenceRule!!.hour)
        assertEquals("중고등부 톡방 링크올리기", s.title)
    }

    @Test
    fun `요일 풀네임 나열 - 토요일 일요일`() {
        val result = parser.parse("토요일, 일요일 청소")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), s.recurrenceRule!!.daysOfWeek)
        assertEquals("청소", s.title)
    }

    @Test
    fun `평일 키워드 + 명시적 시간 - 월-금 오전 9시`() {
        val result = parser.parse("평일 오전 9시 약먹기")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            s.recurrenceRule!!.daysOfWeek,
        )
        assertEquals(9, s.recurrenceRule!!.hour)
    }

    @Test
    fun `주말 키워드 + 명시적 시간 - 토일 오후 3시`() {
        val result = parser.parse("주말 오후 3시에 청소")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), s.recurrenceRule!!.daysOfWeek)
        assertEquals(15, s.recurrenceRule!!.hour)
    }

    @Test
    fun `격주 다중 요일 - interval 2 월수`() {
        val result = parser.parse("격주 월, 수 회의")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        assertEquals(2, s.recurrenceRule!!.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), s.recurrenceRule!!.daysOfWeek)
    }

    @Test
    fun `주간 반복 첫 알림은 지정 요일에 잡힌다`() {
        // 2026-05-03은 일요일 — daysOfWeek에 없는 요일에 첫 알림이 잡히면 안 됨
        val sunday = LocalDateTime(2026, 5, 3, 10, 0, 0).toInstant(tz)
        val sundayParser = TimeParser(nowProvider = { sunday }, timeZone = tz)
        val result = sundayParser.parse("월, 화, 수 11시")
        val s = assertIs<TimeParseResult.Scheduled>(result)
        assertNotNull(s.recurrenceRule)
        val firstLdt = Instant.fromEpochMilliseconds(s.scheduledAt).toLocalDateTime(tz)
        assertTrue(firstLdt.dayOfWeek in s.recurrenceRule!!.daysOfWeek)
        assertEquals(DayOfWeek.MONDAY, firstLdt.dayOfWeek)
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

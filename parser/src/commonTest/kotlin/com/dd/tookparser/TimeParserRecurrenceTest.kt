package com.dd.tookparser

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeParserRecurrenceTest {

    private val parser = TimeParser()

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
}

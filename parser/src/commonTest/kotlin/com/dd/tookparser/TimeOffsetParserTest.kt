package com.dd.tookparser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeOffsetParserTest {

    @Test
    fun `30분 전에 - 음수 30분`() {
        val result = TimeOffsetParser.parse("퇴근 30분 전에 보고서 마감")
        assertNotNull(result)
        assertEquals(-30, result!!.offsetMinutes)
        assertEquals("30분 전에", result.matchedText)
    }

    @Test
    fun `1시간 전에 - 음수 60분`() {
        val result = TimeOffsetParser.parse("퇴근 1시간 전에 정리")
        assertNotNull(result)
        assertEquals(-60, result!!.offsetMinutes)
    }

    @Test
    fun `20분 전 - 조사 없이`() {
        val result = TimeOffsetParser.parse("출근 20분 전 커피")
        assertNotNull(result)
        assertEquals(-20, result!!.offsetMinutes)
    }

    @Test
    fun `15분 전에 - 캘린더 컨텍스트`() {
        val result = TimeOffsetParser.parse("회의 15분 전에 PPT 확인")
        assertNotNull(result)
        assertEquals(-15, result!!.offsetMinutes)
    }

    @Test
    fun `30분 후에 - 양수 30분`() {
        val result = TimeOffsetParser.parse("회의 끝나고 30분 후에 정리")
        assertNotNull(result)
        assertEquals(30, result!!.offsetMinutes)
    }

    @Test
    fun `1시간 후에 - 양수 60분`() {
        val result = TimeOffsetParser.parse("퇴근 1시간 후에 헬스장")
        assertNotNull(result)
        assertEquals(60, result!!.offsetMinutes)
    }

    @Test
    fun `오프셋 없는 입력 - null`() {
        assertNull(TimeOffsetParser.parse("퇴근하고 마트"))
        assertNull(TimeOffsetParser.parse("회의 전에 자료"))
        assertNull(TimeOffsetParser.parse("뚜띠 간식"))
    }

    @Test
    fun `오프셋 텍스트 제거`() {
        val result = TimeOffsetParser.removeOffset("30분 전에 보고서 마감", "30분 전에")
        assertEquals("보고서 마감", result)
    }

    @Test
    fun `오프셋 텍스트 제거 후 공백 정리`() {
        val result = TimeOffsetParser.removeOffset("1시간 전에  정리", "1시간 전에")
        assertEquals("정리", result)
    }
}

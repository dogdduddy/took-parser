package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertContains

class TimeParserTest {

    private lateinit var parser: TimeParser
    private val tz = TimeZone.currentSystemDefault()

    @BeforeTest
    fun setup() {
        parser = TimeParser()
    }

    private fun fixedNow(): Instant =
        LocalDateTime(2026, 5, 7, 15, 0, 0).toInstant(tz)

    private fun Long.toLdt() = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz)

    // ── 스케줄됨 (Scheduled) ──────────────────────────────────────────────

    @Test
    fun `7시 → 오늘 19시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("7시 다이소 그릇"))
        assertEquals(19, result.scheduledAt.toLdt().hour)
        assertEquals(0, result.scheduledAt.toLdt().minute)
        assertContains(result.title, "다이소 그릇")
    }

    @Test
    fun `내일 3시 → 내일 15시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("내일 3시 치과"))
        val nowDate = Clock.System.now().toLocalDateTime(tz).date
        val expectedDay = nowDate.plus(DatePeriod(days = 1))
        assertEquals(expectedDay.dayOfMonth, result.scheduledAt.toLdt().dayOfMonth)
        assertEquals(15, result.scheduledAt.toLdt().hour)
        assertEquals("치과", result.title.trim())
    }

    @Test
    fun `금요일까지 → 이번 주 금요일 10시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("금요일까지 PR 올리기"))
        assertEquals(DayOfWeek.FRIDAY, result.scheduledAt.toLdt().dayOfWeek)
        assertEquals(10, result.scheduledAt.toLdt().hour)
        assertContains(result.title, "PR 올리기")
    }

    @Test
    fun `다음주 수요일 → 다음 주 수요일 10시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("다음주 수요일 미팅"))
        assertEquals(DayOfWeek.WEDNESDAY, result.scheduledAt.toLdt().dayOfWeek)
        val daysFromNow = ((result.scheduledAt - Clock.System.now().toEpochMilliseconds()) / (24 * 60 * 60 * 1000)).toInt()
        assertTrue(daysFromNow >= 7)
        assertEquals("미팅", result.title.trim())
    }

    @Test
    fun `30분 뒤 → 현재+30분`() {
        val before = Clock.System.now().toEpochMilliseconds()
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("30분 뒤 세탁기 돌리기"))
        val after = Clock.System.now().toEpochMilliseconds()
        assertTrue(result.scheduledAt >= before + 30 * 60_000L - 2000)
        assertTrue(result.scheduledAt <= after + 30 * 60_000L + 2000)
        assertContains(result.title, "세탁기 돌리기")
    }

    @Test
    fun `25일 3시 할머니 - D일 단독 매칭`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("25일 3시에 할머니 만나러 가기"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(25, ldt.dayOfMonth)
        assertEquals(15, ldt.hour)
        assertEquals(0, ldt.minute)
        assertEquals("할머니 만나러 가기", result.title)
    }

    @Test
    fun `25일 - D일 단독 기본 10시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("25일 할머니 생신"))
        assertEquals(25, result.scheduledAt.toLdt().dayOfMonth)
        assertEquals("할머니 생신", result.title)
    }

    @Test
    fun `다음 달 5일 - 다음달 5일로 설정`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("다음 달 5일 결제"))
        val nowDate = Clock.System.now().toLocalDateTime(tz).date
        val nextMonthDate = nowDate.plus(DatePeriod(months = 1))
        assertEquals(nextMonthDate.month, result.scheduledAt.toLdt().month)
        assertEquals(5, result.scheduledAt.toLdt().dayOfMonth)
        assertEquals("결제", result.title)
    }

    @Test
    fun `5일간 여행 - 일간 은 날짜로 매칭되지 않음`() {
        val result = parser.parse("5일간 여행")
        assertIs<TimeParseResult.Buffered>(result)
    }

    @Test
    fun `4월 25일 엄마 생신 → 4월 25일 10시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("4월 25일 엄마 생신"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(Month.APRIL, ldt.month)
        assertEquals(25, ldt.dayOfMonth)
        assertEquals(10, ldt.hour)
        assertContains(result.title, "엄마 생신")
    }

    @Test
    fun `오후 3시 회의 → 오늘 15시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("오후 3시 회의"))
        assertEquals(15, result.scheduledAt.toLdt().hour)
        assertEquals("회의", result.title.trim())
    }

    @Test
    fun `저녁에 운동 → 오늘 19시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("저녁에 운동"))
        assertEquals(19, result.scheduledAt.toLdt().hour)
        assertEquals("운동", result.title.trim())
    }

    @Test
    fun `모레 점심 약속 → 모레 12시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("모레 점심 약속"))
        val nowDate = Clock.System.now().toLocalDateTime(tz).date
        val expectedDay = nowDate.plus(DatePeriod(days = 2))
        assertEquals(expectedDay.dayOfMonth, result.scheduledAt.toLdt().dayOfMonth)
        assertEquals(12, result.scheduledAt.toLdt().hour)
    }

    @Test
    fun `2시간 뒤 → 현재+2시간`() {
        val before = Clock.System.now().toEpochMilliseconds()
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("2시간 뒤 약 먹기"))
        val after = Clock.System.now().toEpochMilliseconds()
        assertTrue(result.scheduledAt >= before + 2 * 60 * 60_000L - 2000)
        assertTrue(result.scheduledAt <= after + 2 * 60 * 60_000L + 2000)
        assertContains(result.title, "약 먹기")
    }

    @Test
    fun `오전 10시 30분 미팅 → 10시 30분`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("오전 10시 30분 미팅"))
        assertEquals(10, result.scheduledAt.toLdt().hour)
        assertEquals(30, result.scheduledAt.toLdt().minute)
    }

    // ── 컨텍스트 없는 모호한 시각 (가장 가까운 미래 룰) ─────────────────

    @Test
    fun `밤 22시 19분 기준 11시 - 오늘 23시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 22, 19, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("11시에 밥 먹어야해요"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(23, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `밤 23시 기준 10시 - 내일 오전 10시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 23, 0, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("10시 다이소"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(10, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `오후 16시 기준 3시 - 내일 15시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 16, 0, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("3시 회의"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(15, ldt.hour)
    }

    @Test
    fun `오후 15시 기준 9시 - 오늘 21시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 15, 0, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("9시 다이소"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(21, ldt.hour)
    }

    @Test
    fun `오전 8시 기준 11시 - 오늘 오전 11시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 8, 0, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("11시 회의"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(11, ldt.hour)
    }

    @Test
    fun `오후 14시 기준 12시 - 내일 12시로 해석`() {
        val now = LocalDateTime(2026, 5, 7, 14, 0, 0).toInstant(tz)
        val fixedParser = TimeParser({ now })
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("12시 점심"))
        val ldt = result.scheduledAt.toLdt()
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(12, ldt.hour)
    }

    @Test
    fun `오늘 15시 기준 오전 9시 약은 오늘 9시로 파싱`() {
        val fixedParser = TimeParser(::fixedNow)
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("오전 9시 약"))
        val ldt = result.scheduledAt.toLdt()

        assertEquals(2026, ldt.year)
        assertEquals(Month.MAY, ldt.month)
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(9, ldt.hour)
        assertEquals(0, ldt.minute)
        assertEquals("약", result.title)
    }

    @Test
    fun `오늘 15시 기준 내일 오전 9시 약은 내일 9시로 파싱`() {
        val fixedParser = TimeParser(::fixedNow)
        val result = assertIs<TimeParseResult.Scheduled>(fixedParser.parse("내일 오전 9시 약"))
        val ldt = result.scheduledAt.toLdt()

        assertEquals(2026, ldt.year)
        assertEquals(Month.MAY, ldt.month)
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(9, ldt.hour)
        assertEquals(0, ldt.minute)
        assertEquals("약", result.title)
    }

    @Test
    fun `7시반 → 7시 30분 PM`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("7시반 저녁 약속"))
        assertEquals(19, result.scheduledAt.toLdt().hour)
        assertEquals(30, result.scheduledAt.toLdt().minute)
    }

    @Test
    fun `내일 오전 8시 약 → 내일 8시`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("내일 오전 8시 약"))
        val nowDate = Clock.System.now().toLocalDateTime(tz).date
        val tomorrow = nowDate.plus(DatePeriod(days = 1))
        assertEquals(tomorrow.dayOfMonth, result.scheduledAt.toLdt().dayOfMonth)
        assertEquals(8, result.scheduledAt.toLdt().hour)
    }

    @Test
    fun `이번주 금요일 → 이번 주 금요일`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("이번주 금요일 보고서"))
        assertEquals(DayOfWeek.FRIDAY, result.scheduledAt.toLdt().dayOfWeek)
    }

    @Test
    fun `글피 미팅 → 3일 후`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("글피 미팅"))
        val nowDate = Clock.System.now().toLocalDateTime(tz).date
        val expected = nowDate.plus(DatePeriod(days = 3))
        assertEquals(expected.dayOfMonth, result.scheduledAt.toLdt().dayOfMonth)
    }

    // ── 버퍼 (Buffered) ────────────────────────────────────────────────────

    @Test
    fun `뚜띠 간식 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse("뚜띠 간식"))
    }

    @Test
    fun `블로그 글 쓰기 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse("블로그 글 쓰기"))
    }

    @Test
    fun `아빠 혈압약 OO약국 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse("아빠 혈압약 OO약국"))
    }

    @Test
    fun `카드값 확인 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse("카드값 확인"))
    }

    @Test
    fun `빈 문자열 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse(""))
    }

    @Test
    fun `공백만 → 버퍼`() {
        assertIs<TimeParseResult.Buffered>(parser.parse("   "))
    }

    // ── 조사 제거 (Particle stripping) ───────────────────────────────────

    @Test
    fun `5분 뒤에 회의 시작 - 에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("5분 뒤에 회의 시작"))
        assertEquals("회의 시작", result.title)
    }

    @Test
    fun `30분 후에 세탁기 - 후에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("30분 후에 세탁기"))
        assertEquals("세탁기", result.title)
    }

    @Test
    fun `30분 후 세탁기 - 후 인식`() {
        val before = Clock.System.now().toEpochMilliseconds()
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("30분 후 세탁기"))
        val after = Clock.System.now().toEpochMilliseconds()
        assertTrue(result.scheduledAt >= before + 30 * 60_000L - 2000)
        assertTrue(result.scheduledAt <= after + 30 * 60_000L + 2000)
        assertEquals("세탁기", result.title)
    }

    @Test
    fun `7시에 다이소 그릇 - 에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("7시에 다이소 그릇"))
        assertEquals("다이소 그릇", result.title)
    }

    @Test
    fun `내일 오후 3시에 치과 - 에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("내일 오후 3시에 치과"))
        assertEquals("치과", result.title)
    }

    @Test
    fun `금요일까지 PR 올리기 - 까지 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("금요일까지 PR 올리기"))
        assertEquals("PR 올리기", result.title)
    }

    @Test
    fun `3시부터 회의 - 부터 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("3시부터 회의"))
        assertEquals("회의", result.title)
    }

    @Test
    fun `내일까지 보고서 - 내일까지 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("내일까지 보고서"))
        assertEquals("보고서", result.title)
    }

    @Test
    fun `오늘 안에 마감 - 안에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("오늘 안에 마감"))
        assertEquals("마감", result.title)
    }

    @Test
    fun `다음 주에 미팅 - 에 제거`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("다음 주에 미팅"))
        assertEquals("미팅", result.title)
    }

    @Test
    fun `7시 다이소 그릇 - 조사 없을 때 정상 동작`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("7시 다이소 그릇"))
        assertEquals("다이소 그릇", result.title)
    }

    @Test
    fun `5분 뒤에만 있는 경우 - 원본 fallback`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("5분 뒤에"))
        assertEquals("5분 뒤에", result.title)
    }

    @Test
    fun `연속 공백 정리`() {
        val result = assertIs<TimeParseResult.Scheduled>(parser.parse("내일  오후 3시에   치과"))
        assertEquals("치과", result.title)
    }

    // ── LLM 폴백 필요 (NeedsFallback) ────────────────────────────────────

    @Test
    fun `퇴근 후 마트 → NeedsFallback`() {
        val result = assertIs<TimeParseResult.NeedsFallback>(parser.parse("퇴근 후 마트"))
        assertContains(result.hint, "퇴근")
    }

    @Test
    fun `점심 먹고 은행 → NeedsFallback`() {
        val result = assertIs<TimeParseResult.NeedsFallback>(parser.parse("점심 먹고 은행"))
        assertContains(result.hint, "점심")
    }

    @Test
    fun `회의 끝나고 병선님께 보고 → NeedsFallback`() {
        val result = assertIs<TimeParseResult.NeedsFallback>(parser.parse("회의 끝나고 병선님께 보고"))
        assertContains(result.hint, "회의")
    }
}

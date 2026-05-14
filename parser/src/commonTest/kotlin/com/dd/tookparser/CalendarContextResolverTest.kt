package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertContains

class CalendarContextResolverTest {

    private val tz = TimeZone.currentSystemDefault()

    private fun Long.toLdt() = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz)

    private fun futureEvent(title: String, startHour: Int, endHour: Int): CalendarEvent {
        val tomorrow = Clock.System.now().toLocalDateTime(tz).date.plus(DatePeriod(days = 1))
        val startMillis = LocalDateTime(tomorrow, LocalTime(startHour, 0, 0)).toInstant(tz).toEpochMilliseconds()
        val endMillis = LocalDateTime(tomorrow, LocalTime(endHour, 0, 0)).toInstant(tz).toEpochMilliseconds()
        return CalendarEvent(
            id = 1L,
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            location = null,
            calendarName = null,
        )
    }

    private fun resolverWith(event: CalendarEvent?) =
        CalendarContextResolver(eventFinder = { _, _ -> event })

    // ── 매칭 성공 ─────────────────────────────────────────────────────────

    @Test
    fun `회의 전에 자료 준비 - 이벤트 시작 30분 전`() {
        val event = futureEvent("팀 주간 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 전에 자료 준비")

        assertNotNull(result)
        assertEquals("자료 준비", result!!.title)
        assertNotNull(result.scheduledAt)
        assertEquals(event.startMillis - 30 * 60 * 1000, result.scheduledAt)
        assertEquals("팀 주간 회의", result.calendarEvent!!.title)
        assertNull(result.failReason)
    }

    @Test
    fun `회의 끝나고 회의록 정리 - 이벤트 종료 시각`() {
        val event = futureEvent("팀 주간 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 끝나고 회의록 정리")

        assertNotNull(result)
        assertEquals("회의록 정리", result!!.title)
        assertEquals(event.endMillis, result.scheduledAt)
    }

    @Test
    fun `치과 가기 전에 양치 - 가기 전에 패턴`() {
        val event = futureEvent("치과 예약", 16, 17)
        val result = resolverWith(event).resolve("치과 가기 전에 양치")

        assertNotNull(result)
        assertEquals("양치", result!!.title)
        assertEquals(event.startMillis - 30 * 60 * 1000, result.scheduledAt)
    }

    @Test
    fun `미팅 후에 보고서 작성 - 후에 패턴`() {
        val event = futureEvent("클라이언트 미팅", 10, 11)
        val result = resolverWith(event).resolve("미팅 후에 보고서 작성")

        assertNotNull(result)
        assertEquals("보고서 작성", result!!.title)
        assertEquals(event.endMillis, result.scheduledAt)
    }

    @Test
    fun `수업 끝나면 도서관 - 끝나면 패턴`() {
        val event = futureEvent("알고리즘 수업", 13, 15)
        val result = resolverWith(event).resolve("수업 끝나면 도서관")

        assertNotNull(result)
        assertEquals("도서관", result!!.title)
    }

    @Test
    fun `회의 시작 전에 PPT 검토 - 시작 전에 패턴`() {
        val event = futureEvent("전략 회의", 15, 16)
        val result = resolverWith(event).resolve("회의 시작 전에 PPT 검토")

        assertNotNull(result)
        assertEquals("PPT 검토", result!!.title)
    }

    @Test
    fun `치과 다녀와서 처방전 - 다녀와서 패턴`() {
        val event = futureEvent("치과", 14, 15)
        val result = resolverWith(event).resolve("치과 다녀와서 처방전")

        assertNotNull(result)
        assertEquals("처방전", result!!.title)
        assertEquals(event.endMillis, result.scheduledAt)
    }

    // ── 매칭 실패 ─────────────────────────────────────────────────────────

    @Test
    fun `캘린더에 이벤트 없으면 scheduledAt null`() {
        val result = resolverWith(null).resolve("회의 전에 자료 준비")

        assertNotNull(result)
        assertNull(result!!.scheduledAt)
        assertContains(result.failReason!!, "찾지 못했")
        assertNull(result.calendarEvent)
    }

    // ── 트리거 없는 입력 ──────────────────────────────────────────────────

    @Test
    fun `캘린더 트리거 없는 입력 - null 반환`() {
        assertNull(resolverWith(null).resolve("뚜띠 간식"))
    }

    @Test
    fun `일반 시간 표현 - null 반환`() {
        assertNull(resolverWith(null).resolve("7시 다이소"))
    }

    @Test
    fun `퇴근하고 마트 - 생활 구간 트리거 캘린더 트리거 아님`() {
        assertNull(resolverWith(null).resolve("퇴근하고 마트"))
    }

    // ── 제목 추출 ─────────────────────────────────────────────────────────

    @Test
    fun `회의 시작하기 전에 PPT 검토하고 프린트 - 제목 정확히 추출`() {
        val event = futureEvent("팀 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 시작하기 전에 PPT 검토하고 프린트")

        assertEquals("PPT 검토하고 프린트", result!!.title)
    }

    @Test
    fun `회의 끝나고 나서 회의록 - 끝나고 나서 패턴`() {
        val event = futureEvent("팀 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 끝나고 나서 회의록")

        assertEquals("회의록", result!!.title)
    }

    // ── TimeParser 통합 ────────────────────────────────────────────────────

    @Test
    fun `명시적 시간이 있으면 캘린더보다 우선`() {
        val event = futureEvent("회의", 14, 15)
        val resolver = resolverWith(event)
        val result = TimeParser().parse("내일 3시 회의 자료 준비", null, resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals(15, scheduled.scheduledAt.toLdt().hour)
    }

    @Test
    fun `calendarResolver null이면 회의 전에가 Buffered로 처리`() {
        val result = TimeParser().parse("회의 전에 자료 준비", null, null)
        assertIs<TimeParseResult.Buffered>(result)
    }

    @Test
    fun `회의 끝나고 resolver null이면 기존과 동일하게 NeedsFallback`() {
        val result = TimeParser().parse("회의 끝나고 병선님께 보고", null, null)
        assertIs<TimeParseResult.NeedsFallback>(result)
    }

    @Test
    fun `회의 끝나고 resolver 있으면 이벤트 종료 시각으로 Scheduled`() {
        val event = futureEvent("팀 회의", 14, 15)
        val result = TimeParser().parse("회의 끝나고 병선님께 보고", null, resolverWith(event))

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals(event.endMillis, scheduled.scheduledAt)
    }

    @Test
    fun `회의 끝나고 resolver 있지만 이벤트 없으면 BufferedWithHint`() {
        val result = TimeParser().parse("회의 끝나고 병선님께 보고", null, resolverWith(null))

        val buffered = assertIs<TimeParseResult.BufferedWithHint>(result)
        assertContains(buffered.hint, "찾지 못했")
    }

    @Test
    fun `리그레션 - 뚜띠 간식 버퍼`() {
        val result = TimeParser().parse("뚜띠 간식", null, resolverWith(null))
        assertIs<TimeParseResult.Buffered>(result)
    }

    @Test
    fun `리그레션 - 7시 다이소 그릇 Scheduled`() {
        val result = TimeParser().parse("7시 다이소 그릇", null, resolverWith(null))
        assertIs<TimeParseResult.Scheduled>(result)
    }

    // ── 오프셋 테스트 ──────────────────────────────────────────────────────

    @Test
    fun `회의 15분 전에 PPT 확인 - 이벤트 시작 15분 전`() {
        val event = futureEvent("팀 주간 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 15분 전에 PPT 확인")

        assertNotNull(result)
        assertEquals("PPT 확인", result!!.title)
        assertEquals(event.startMillis - 15 * 60 * 1000, result.scheduledAt)
    }

    @Test
    fun `회의 10분 전에 커피 - 이벤트 시작 10분 전`() {
        val event = futureEvent("스크럼", 10, 11)
        val result = resolverWith(event).resolve("회의 10분 전에 커피")

        assertNotNull(result)
        assertEquals(event.startMillis - 10 * 60 * 1000, result.scheduledAt)
    }

    @Test
    fun `치과 1시간 전에 양치 - 이벤트 시작 60분 전`() {
        val event = futureEvent("치과 예약", 16, 17)
        val result = resolverWith(event).resolve("치과 1시간 전에 양치")

        assertNotNull(result)
        assertEquals(event.startMillis - 60 * 60 * 1000, result.scheduledAt)
    }

    @Test
    fun `회의 끝나고 30분 후에 정리 - 이벤트 종료 30분 후`() {
        val event = futureEvent("팀 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 끝나고 30분 후에 정리")

        assertNotNull(result)
        assertEquals(event.endMillis + 30 * 60 * 1000, result.scheduledAt)
    }

    @Test
    fun `오프셋 없는 회의 전에 - 기존 30분 전 유지`() {
        val event = futureEvent("팀 회의", 14, 15)
        val result = resolverWith(event).resolve("회의 전에 자료 준비")

        assertNotNull(result)
        assertEquals(event.startMillis - 30 * 60 * 1000, result.scheduledAt)
    }
}

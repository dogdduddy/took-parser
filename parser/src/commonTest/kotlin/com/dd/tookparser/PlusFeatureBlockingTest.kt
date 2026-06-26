package com.dd.tookparser

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plus 기능 차단 & 메타데이터 전파 회귀 방지 테스트.
 *
 * 검증 기준:
 * - 무료 유저 + 프리미엄 세그먼트 → isBlocked=true, TimeParser → Buffered + LIFESTYLE_SEGMENT
 * - 무료 유저 + 무료 세그먼트(WORK_END 등) → isBlocked=false, 바로 등록
 * - Plus 유저 → 정상 변환, triggeredPlusFeatures에 LIFESTYLE_SEGMENT 포함
 */
class PlusFeatureBlockingTest {

    private val tz = TimeZone.currentSystemDefault()

    private fun defaultSchedule() = LifeSchedule(
        workStartHour = 9,
        workStartMinute = 0,
        workEndHour = 18,
        workEndMinute = 0,
        isConfigured = true,
        lunchHour = 12,
        lunchMinute = 30,
        dinnerHour = 19,
        dinnerMinute = 0,
        wakeUpHour = 7,
        wakeUpMinute = 30,
        bedtimeHour = 23,
        bedtimeMinute = 0,
    )

    private fun fixedNow(): Instant =
        LocalDateTime(2026, 5, 25, 10, 0, 0).toInstant(tz)

    private fun Long.hour() = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz).hour
    private fun Long.minute() = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz).minute

    // ── LifeSegmentResolver.isBlocked ────────────────────────────────────────

    @Test
    fun `무료 유저 점심 입력은 isBlocked=true`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false)
        val result = resolver.resolve("점심 약")

        assertNotNull(result)
        assertEquals(LifeSegment.LUNCH, result!!.segment)
        assertTrue(result.isBlocked)
    }

    @Test
    fun `무료 유저 저녁 단독 입력은 isBlocked=true`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false)
        val result = resolver.resolve("저녁")

        assertNotNull(result)
        assertEquals(LifeSegment.DINNER, result!!.segment)
        assertTrue(result.isBlocked)
    }

    @Test
    fun `무료 유저 기상 단독 입력은 isBlocked=true`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false)
        val result = resolver.resolve("기상")

        assertNotNull(result)
        assertEquals(LifeSegment.WAKE_UP, result!!.segment)
        assertTrue(result.isBlocked)
    }

    @Test
    fun `무료 유저 퇴근하고 입력은 isBlocked=false — WORK_END는 무료`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false)
        val result = resolver.resolve("퇴근하고 마트")

        assertNotNull(result)
        assertEquals(LifeSegment.WORK_END, result!!.segment)
        assertFalse(result.isBlocked)
        // 퇴근 시각으로 자동 등록
        assertEquals(18, result.scheduledAt.hour())
        assertEquals(0, result.scheduledAt.minute())
    }

    @Test
    fun `무료 유저 출근하고 입력은 isBlocked=false — WORK_START는 무료`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false)
        val result = resolver.resolve("출근하고 회의")

        assertNotNull(result)
        assertEquals(LifeSegment.WORK_START, result!!.segment)
        assertFalse(result.isBlocked)
        assertEquals(9, result.scheduledAt.hour())
    }

    @Test
    fun `Plus 유저 점심 입력은 정상 변환`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = true)
        val result = resolver.resolve("점심 약")

        assertNotNull(result)
        assertEquals(LifeSegment.LUNCH, result!!.segment)
        assertFalse(result.isBlocked)
        assertEquals(12, result.scheduledAt.hour())
        assertEquals(30, result.scheduledAt.minute())
    }

    @Test
    fun `Plus 유저 저녁 먹고 입력은 정상 변환`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = true)
        val result = resolver.resolve("저녁 먹고 설거지")

        assertNotNull(result)
        assertEquals(LifeSegment.DINNER, result!!.segment)
        assertFalse(result.isBlocked)
        assertEquals(19, result.scheduledAt.hour())
        assertEquals(0, result.scheduledAt.minute())
    }

    // ── TimeParser triggeredPlusFeatures 전파 ───────────────────────────────

    @Test
    fun `무료 유저 점심 약 — Buffered + LIFESTYLE_SEGMENT 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("점심 약", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in result.triggeredPlusFeatures)
    }

    @Test
    fun `무료 유저 점심쯤 약 — Buffered + LIFESTYLE_SEGMENT 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("점심쯤 약", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in result.triggeredPlusFeatures)
    }

    @Test
    fun `무료 유저 저녁 단독 — Buffered + LIFESTYLE_SEGMENT 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("저녁", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in result.triggeredPlusFeatures)
    }

    @Test
    fun `무료 유저 기상 단독 — Buffered + LIFESTYLE_SEGMENT 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("기상", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in result.triggeredPlusFeatures)
    }

    @Test
    fun `무료 유저 퇴근하고 마트 — Scheduled 바로 등록 트리거 없음`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("퇴근하고 마트", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("마트", scheduled.title)
        assertTrue(scheduled.triggeredPlusFeatures.isEmpty())
        assertEquals(18, scheduled.scheduledAt.hour())
        assertEquals(0, scheduled.scheduledAt.minute())
    }

    @Test
    fun `무료 유저 출근하고 회의 — Scheduled 바로 등록 트리거 없음`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("출근하고 회의", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("회의", scheduled.title)
        assertTrue(scheduled.triggeredPlusFeatures.isEmpty())
        assertEquals(9, scheduled.scheduledAt.hour())
    }

    @Test
    fun `무료 유저 매주 월요일 보고 — Buffered + RECURRENCE 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("매주 월요일 보고", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.RECURRENCE in result.triggeredPlusFeatures)
    }

    @Test
    fun `무료 유저 내일 3시 치과 — Scheduled 바로 등록 트리거 없음`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("내일 3시 치과", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("치과", scheduled.title)
        assertTrue(scheduled.triggeredPlusFeatures.isEmpty())
        assertEquals(15, scheduled.scheduledAt.hour())
    }

    @Test
    fun `Plus 유저 매주 월요일 보고 — Scheduled 정상 등록`() {
        // resolver=null → isPremium=true(기본값)
        val result = TimeParser(::fixedNow).parse("매주 월요일 보고")

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("보고", scheduled.title)
        assertNotNull(scheduled.recurrenceRule)
    }

    @Test
    fun `Plus 유저 점심 약 — Scheduled + LIFESTYLE_SEGMENT 포함`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = true,
            nowProvider = ::fixedNow)
        val result = TimeParser(::fixedNow).parse("점심 약", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("약", scheduled.title)
        assertEquals(LifeSegment.LUNCH, scheduled.matchedSegment)
        assertEquals(12, scheduled.scheduledAt.hour())
        assertEquals(30, scheduled.scheduledAt.minute())
        // Plus 유저도 어떤 기능을 썼는지 메타데이터에 포함
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in scheduled.triggeredPlusFeatures)
    }

    @Test
    fun `회의 끝나고 점심 무료 유저 — Buffered + LIFESTYLE+CALENDAR 트리거`() {
        val resolver = LifeSegmentResolver(defaultSchedule(), isPremium = false,
            nowProvider = ::fixedNow)
        // calendarResolver 없음 → 캘린더 트리거 감지
        val result = TimeParser(::fixedNow).parse("회의 끝나고 점심", resolver)

        assertIs<TimeParseResult.Buffered>(result)
        assertTrue(PlusFeature.LIFESTYLE_SEGMENT in result.triggeredPlusFeatures)
        assertTrue(PlusFeature.CALENDAR_CONTEXT in result.triggeredPlusFeatures)
    }

    // ── triggeredPlusFeatures() 확장 함수 ──────────────────────────────────

    @Test
    fun `triggeredPlusFeatures 확장 함수 - 모든 서브타입에서 동작`() {
        val set = setOf(PlusFeature.RECURRENCE)

        assertEquals(set, TimeParseResult.Scheduled("t", 0L, triggeredPlusFeatures = set).triggeredPlusFeatures())
        assertEquals(set, TimeParseResult.Buffered("t", triggeredPlusFeatures = set).triggeredPlusFeatures())
        assertEquals(set, TimeParseResult.BufferedWithHint("t", "h", triggeredPlusFeatures = set).triggeredPlusFeatures())
        assertEquals(set, TimeParseResult.NeedsFallback("t", "h", triggeredPlusFeatures = set).triggeredPlusFeatures())
    }
}

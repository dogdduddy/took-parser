package com.dd.tookparser

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeSegmentResolverTest {

    private val tz = TimeZone.currentSystemDefault()

    private fun testSchedule() = LifeSchedule(
        workStartHour = 10,
        workStartMinute = 0,
        workEndHour = 19,
        workEndMinute = 0,
        isConfigured = true,
        commuteMinutes = 75,
        lunchHour = 12,
        lunchMinute = 0,
        dinnerHour = 20,
        dinnerMinute = 45,
        wakeUpHour = 8,
        wakeUpMinute = 20,
        bedtimeHour = 23,
        bedtimeMinute = 30,
    )

    private fun fixedNow(): Instant =
        LocalDateTime(2026, 5, 7, 15, 0, 0).toInstant(tz)

    private fun Long.toLdt() = Instant.fromEpochMilliseconds(this).toLocalDateTime(tz)

    private fun resolvedHour(scheduledAt: Long): Int = scheduledAt.toLdt().hour

    private fun resolvedMinute(scheduledAt: Long): Int = scheduledAt.toLdt().minute

    // ── 무료 계층 ──────────────────────────────────────────────────────────

    @Test
    fun `기본 미설정 스케줄 - 출근해서 메일 확인이 기본 출근 시각으로 파싱`() {
        val resolver = LifeSegmentResolver(LifeSchedule(isConfigured = false), isPremium = false)
        val result = resolver.resolve("출근해서 메일 확인")

        assertNotNull(result)
        assertEquals("메일 확인", result!!.title)
        assertEquals(LifeSegment.WORK_START, result.segment)
        assertEquals(9, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `오늘 15시 기준 출근때 뚜띠 목욕은 오늘 9시 제목 뚜띠 목욕으로 파싱`() {
        val resolver = LifeSegmentResolver(
            LifeSchedule(isConfigured = false),
            isPremium = false,
            nowProvider = ::fixedNow,
        )
        val result = resolver.resolve("출근때 뚜띠 목욕")
        val ldt = result!!.scheduledAt.toLdt()

        assertNotNull(result)
        assertEquals("뚜띠 목욕", result.title)
        assertEquals(LifeSegment.WORK_START, result.segment)
        assertEquals(2026, ldt.year)
        assertEquals(Month.MAY, ldt.month)
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(9, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `오늘 15시 기준 TimeParser 출근때 뚜띠 목욕은 오늘 9시로 파싱`() {
        val resolver = LifeSegmentResolver(
            LifeSchedule(isConfigured = false),
            isPremium = false,
            nowProvider = ::fixedNow,
        )
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser(::fixedNow).parse("출근때 뚜띠 목욕", resolver)
        )
        val ldt = result.scheduledAt.toLdt()

        assertEquals("뚜띠 목욕", result.title)
        assertEquals(2026, ldt.year)
        assertEquals(Month.MAY, ldt.month)
        assertEquals(7, ldt.dayOfMonth)
        assertEquals(9, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `기본 미설정 스케줄 - 퇴근하고 밥 먹기가 무료 폴백 시각으로 파싱`() {
        val resolver = LifeSegmentResolver(LifeSchedule(isConfigured = false), isPremium = false)
        val result = resolver.resolve("퇴근하고 밥 먹기")

        assertNotNull(result)
        assertEquals("밥 먹기", result!!.title)
        assertEquals(LifeSegment.ARRIVE_HOME, result.segment)
        assertEquals(18, resolvedHour(result.scheduledAt))
        assertEquals(30, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `기본 미설정 스케줄 - TimeParser도 생활 구간을 Scheduled로 파싱`() {
        val resolver = LifeSegmentResolver(LifeSchedule(isConfigured = false), isPremium = false)
        val result = TimeParser().parse("퇴근하고 밥 먹기", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("밥 먹기", scheduled.title)
        assertEquals(18, resolvedHour(scheduled.scheduledAt))
        assertEquals(30, resolvedMinute(scheduled.scheduledAt))
    }

    @Test
    fun `출근해서 메일 확인 - 무료출근 시각으로 파싱`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("출근해서 메일 확인")

        assertNotNull(result)
        assertEquals("메일 확인", result!!.title)
        assertEquals(LifeSegment.WORK_START, result.segment)
        assertEquals(10, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `퇴근하고 마트 - 무료퇴근+30분 폴백`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("퇴근하고 마트")

        assertNotNull(result)
        assertEquals("마트", result!!.title)
        assertEquals(LifeSegment.ARRIVE_HOME, result.segment)
        assertEquals(19, resolvedHour(result.scheduledAt))
        assertEquals(30, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `오전에 보고서 작성 - 무료오전 매칭`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("오전에 보고서 작성")

        assertNotNull(result)
        assertEquals("보고서 작성", result!!.title)
        assertEquals(LifeSegment.MORNING, result.segment)
        assertEquals(11, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `오후에 회의 준비 - 무료오후 매칭`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("오후에 회의 준비")

        assertNotNull(result)
        assertEquals("회의 준비", result!!.title)
        assertEquals(LifeSegment.AFTERNOON, result.segment)
        assertEquals(14, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    // ── 프리미엄 계층 ──────────────────────────────────────────────────────

    @Test
    fun `퇴근하고 운동 - 프리미엄퇴근+통근으로 정밀 계산`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("퇴근하고 운동")

        assertNotNull(result)
        assertEquals("운동", result!!.title)
        assertEquals(LifeSegment.ARRIVE_HOME, result.segment)
        assertTrue(result.isPremiumAccuracy)
        assertEquals(20, resolvedHour(result.scheduledAt))
        assertEquals(15, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `출근길에 커피 사기 - 프리미엄출근-통근`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("출근길에 커피 사기")

        assertNotNull(result)
        assertEquals("커피 사기", result!!.title)
        assertEquals(LifeSegment.COMMUTE_TO_WORK, result.segment)
        assertEquals(8, resolvedHour(result.scheduledAt))
        assertEquals(45, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `자기 전에 스트레칭 - 프리미엄취침 시각`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("자기 전에 스트레칭")

        assertNotNull(result)
        assertEquals("스트레칭", result!!.title)
        assertEquals(LifeSegment.BEDTIME, result.segment)
        assertEquals(23, resolvedHour(result.scheduledAt))
        assertEquals(30, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `점심 먹고 은행 - 프리미엄점심 시각`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("점심 먹고 은행")

        assertNotNull(result)
        assertEquals("은행", result!!.title)
        assertEquals(LifeSegment.LUNCH, result.segment)
        assertEquals(12, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `집 가서 운동 - 프리미엄집 도착 시각`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("집 가서 운동")

        assertNotNull(result)
        assertEquals("운동", result!!.title)
        assertEquals(LifeSegment.ARRIVE_HOME, result.segment)
        assertEquals(20, resolvedHour(result.scheduledAt))
        assertEquals(15, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `저녁 먹고 설거지 - 프리미엄저녁 식사 시각`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("저녁 먹고 설거지")

        assertNotNull(result)
        assertEquals("설거지", result!!.title)
        assertEquals(LifeSegment.DINNER, result.segment)
        assertEquals(20, resolvedHour(result.scheduledAt))
        assertEquals(45, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `일어나서 스트레칭 - 프리미엄기상 시각`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("일어나서 스트레칭")

        assertNotNull(result)
        assertEquals("스트레칭", result!!.title)
        assertEquals(LifeSegment.WAKE_UP, result.segment)
        assertEquals(8, resolvedHour(result.scheduledAt))
        assertEquals(20, resolvedMinute(result.scheduledAt))
    }

    // ── 매칭 안 되는 케이스 ────────────────────────────────────────────────

    @Test
    fun `생활 구간 키워드 없는 입력 - null 반환`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        assertNull(resolver.resolve("뚜띠 간식"))
    }

    @Test
    fun `빈 입력 - null 반환`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        assertNull(resolver.resolve(""))
    }

    // ── stripTrigger ───────────────────────────────────────────────────────

    @Test
    fun `stripTrigger - 퇴근하고 마트에서 퇴근하고 제거`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        assertEquals("마트", resolver.stripTrigger("퇴근하고 마트"))
    }

    @Test
    fun `stripTrigger - 트리거 없는 입력은 원본 반환`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        assertEquals("마트", resolver.stripTrigger("마트"))
    }

    // ── TimeParser 통합 테스트 ─────────────────────────────────────────────

    @Test
    fun `명시적 시간이 있으면 구간보다 우선 - 퇴근하고 7시에 마트`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser().parse("퇴근하고 7시에 마트", resolver)
        )
        assertEquals("마트", result.title)
        assertEquals(19, result.scheduledAt.toLdt().hour)
    }

    @Test
    fun `리그레션 - 7시 다이소 그릇`() {
        val result = assertIs<TimeParseResult.Scheduled>(TimeParser().parse("7시 다이소 그릇"))
        assertEquals("다이소 그릇", result.title)
        assertEquals(19, result.scheduledAt.toLdt().hour)
    }

    @Test
    fun `리그레션 - 뚜띠 간식 버퍼`() {
        val result = TimeParser().parse("뚜띠 간식")
        assertIs<TimeParseResult.Buffered>(result)
    }

    @Test
    fun `리그레션 - 점심 먹고 은행 resolver null이면 NeedsFallback`() {
        val result = TimeParser().parse("점심 먹고 은행")
        assertIs<TimeParseResult.NeedsFallback>(result)
    }

    @Test
    fun `리그레션 - 퇴근 후 마트 resolver null이면 NeedsFallback`() {
        val result = TimeParser().parse("퇴근 후 마트")
        assertIs<TimeParseResult.NeedsFallback>(result)
    }

    @Test
    fun `resolver 있으면 점심 먹고 은행이 Scheduled로 파싱`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser().parse("점심 먹고 은행", resolver)
        )
        assertEquals("은행", result.title)
        assertEquals(12, resolvedHour(result.scheduledAt))
    }

    // ── 오프셋 테스트 ──────────────────────────────────────────────────────

    @Test
    fun `퇴근 30분 전에 보고서 - 퇴근 시각 30분 전`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("퇴근 30분 전에 보고서 마감")

        assertNotNull(result)
        assertEquals("보고서 마감", result!!.title)
        assertEquals(18, resolvedHour(result.scheduledAt))
        assertEquals(30, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `퇴근 1시간 전에 정리 - 퇴근 시각 1시간 전`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("퇴근 1시간 전에 정리")

        assertNotNull(result)
        assertEquals("정리", result!!.title)
        assertEquals(18, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `점심 10분 전에 손 씻기 - 프리미엄점심 10분 전`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true)
        val result = resolver.resolve("점심 10분 전에 손 씻기")

        assertNotNull(result)
        assertEquals("손 씻기", result!!.title)
        assertEquals(11, resolvedHour(result.scheduledAt))
        assertEquals(50, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `출근 20분 전에 커피 - 출근 20분 전`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("출근 20분 전에 커피")

        assertNotNull(result)
        assertEquals("커피", result!!.title)
        assertEquals(9, resolvedHour(result.scheduledAt))
        assertEquals(40, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `오프셋 없는 기존 표현 리그레션 - 퇴근하고 마트`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("퇴근하고 마트")

        assertNotNull(result)
        assertEquals("마트", result!!.title)
        assertEquals(19, resolvedHour(result.scheduledAt))
        assertEquals(30, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `오프셋 없는 기존 표현 리그레션 - 출근해서 메일`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = resolver.resolve("출근해서 메일 확인")

        assertNotNull(result)
        assertEquals("메일 확인", result!!.title)
        assertEquals(10, resolvedHour(result.scheduledAt))
        assertEquals(0, resolvedMinute(result.scheduledAt))
    }

    @Test
    fun `퇴근 30분 전 resolver 있으면 Scheduled`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false)
        val result = TimeParser().parse("퇴근 30분 전에 보고서 마감", resolver)

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertEquals("보고서 마감", scheduled.title)
    }

    @Test
    fun `리그레션 - 30분 뒤 세탁기 퇴근 키워드 없으면 기존 상대시간`() {
        val result = TimeParser().parse("30분 뒤 세탁기 돌리기")

        val scheduled = assertIs<TimeParseResult.Scheduled>(result)
        assertTrue(scheduled.title.contains("세탁기 돌리기"))
    }

    // ── 날짜 + 생활구간 복합 표현 ──────────────────────────────────────────

    @Test
    fun `내일 출근전에 강아지 약 먹이기 - 내일 날짜 + 출근전 시간 결합`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true, nowProvider = ::fixedNow)
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser(::fixedNow).parse("내일 출근전에 강아지 약 먹이기", resolver)
        )
        val ldt = result.scheduledAt.toLdt()

        assertEquals("강아지 약 먹이기", result.title)
        assertEquals(2026, ldt.year)
        assertEquals(Month.MAY, ldt.month)
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(8, ldt.hour)
        assertEquals(45, ldt.minute)
    }

    @Test
    fun `모레 출근전 산책 - 모레 날짜 + 출근전 시간 결합`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true, nowProvider = ::fixedNow)
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser(::fixedNow).parse("모레 출근전 산책", resolver)
        )
        val ldt = result.scheduledAt.toLdt()

        assertEquals("산책", result.title)
        assertEquals(9, ldt.dayOfMonth)
        assertEquals(8, ldt.hour)
        assertEquals(45, ldt.minute)
    }

    @Test
    fun `내일 출근 메일 확인 - 내일 날짜 + 출근 시간 결합`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = false, nowProvider = ::fixedNow)
        val result = assertIs<TimeParseResult.Scheduled>(
            TimeParser(::fixedNow).parse("내일 출근 메일 확인", resolver)
        )
        val ldt = result.scheduledAt.toLdt()

        assertEquals("메일 확인", result.title)
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(10, ldt.hour)
        assertEquals(0, ldt.minute)
    }

    @Test
    fun `리그레션 - 내일 치과는 기본시간 10시`() {
        val result = assertIs<TimeParseResult.Scheduled>(TimeParser(::fixedNow).parse("내일 치과"))
        val ldt = result.scheduledAt.toLdt()

        assertEquals("치과", result.title)
        assertEquals(8, ldt.dayOfMonth)
        assertEquals(10, ldt.hour)
    }

    @Test
    fun `리그레션 - 모레 점심 약속은 extractTime이 점심 인식`() {
        val result = assertIs<TimeParseResult.Scheduled>(TimeParser(::fixedNow).parse("모레 점심 약속"))
        val ldt = result.scheduledAt.toLdt()

        assertEquals(9, ldt.dayOfMonth)
        assertEquals(12, ldt.hour)
    }

    @Test
    fun `출근전에 강아지 약 - 날짜 없이 세그먼트만`() {
        val resolver = LifeSegmentResolver(testSchedule(), isPremium = true, nowProvider = ::fixedNow)
        val result = resolver.resolve("출근전에 강아지 약")

        assertNotNull(result)
        assertEquals("강아지 약", result!!.title)
        assertEquals(LifeSegment.COMMUTE_TO_WORK, result.segment)
        assertEquals(8, resolvedHour(result.scheduledAt))
        assertEquals(45, resolvedMinute(result.scheduledAt))
    }
}

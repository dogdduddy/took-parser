package com.dd.tookparser

/**
 * 파서가 감지한 Plus 기능 종류.
 * app 레이어에서 무료 유저가 Plus 기능을 트리거했을 때 어떤 종류인지 판단하기 위해 사용.
 */
enum class PlusFeature {
    /** 점심/저녁/기상/취침/통근 등 사용자 맞춤 생활 구간 */
    LIFESTYLE_SEGMENT,
    /** 매주/매일/격주 등 반복 규칙 */
    RECURRENCE,
    /** "회의 끝나고", "수업 전에" 등 캘린더 컨텍스트 */
    CALENDAR_CONTEXT,
}

sealed class TimeParseResult {

    /** 시간이 명확히 파싱됨 */
    data class Scheduled(
        val title: String,
        val scheduledAt: Long,
        val confidence: Float = 1f,
        val recurrenceRule: RecurrenceRule? = null,
        /** 매칭된 생활 구간 (있을 경우) */
        val matchedSegment: LifeSegment? = null,
        /** 이 결과를 만들기 위해 사용된 Plus 기능 */
        val triggeredPlusFeatures: Set<PlusFeature> = emptySet(),
    ) : TimeParseResult()

    /** 시간 정보 없음 — 버퍼로 저장 */
    data class Buffered(
        val title: String,
        val triggeredPlusFeatures: Set<PlusFeature> = emptySet(),
    ) : TimeParseResult()

    /** 캘린더 트리거 감지됐지만 이벤트 없음 → 버퍼 저장 + 힌트 */
    data class BufferedWithHint(
        val title: String,
        val hint: String,
        val triggeredPlusFeatures: Set<PlusFeature> = emptySet(),
    ) : TimeParseResult()

    /** 시간 힌트 감지됐지만 로컬 파서 실패 → LLM 폴백 필요 */
    data class NeedsFallback(
        val title: String,
        val hint: String,
        val triggeredPlusFeatures: Set<PlusFeature> = emptySet(),
    ) : TimeParseResult()
}

/** 어느 서브타입이든 triggeredPlusFeatures를 통일 접근 */
fun TimeParseResult.triggeredPlusFeatures(): Set<PlusFeature> = when (this) {
    is TimeParseResult.Scheduled -> triggeredPlusFeatures
    is TimeParseResult.Buffered -> triggeredPlusFeatures
    is TimeParseResult.BufferedWithHint -> triggeredPlusFeatures
    is TimeParseResult.NeedsFallback -> triggeredPlusFeatures
}

package com.dd.tookparser

sealed class TimeParseResult {

    /** 시간이 명확히 파싱됨 */
    data class Scheduled(
        val title: String,
        val scheduledAt: Long,
        val confidence: Float = 1f,
        val recurrenceRule: RecurrenceRule? = null,
    ) : TimeParseResult()

    /** 시간 정보 없음 — 버퍼로 저장 */
    data class Buffered(val title: String) : TimeParseResult()

    /** 캘린더 트리거 감지됐지만 이벤트 없음 → 버퍼 저장 + 힌트 */
    data class BufferedWithHint(
        val title: String,
        val hint: String,
    ) : TimeParseResult()

    /** 시간 힌트 감지됐지만 로컬 파서 실패 → LLM 폴백 필요 */
    data class NeedsFallback(
        val title: String,
        val hint: String,
    ) : TimeParseResult()
}

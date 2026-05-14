package com.dd.tookparser

object TimeOffsetParser {

    data class OffsetResult(
        val offsetMinutes: Int,
        val matchedText: String,
    )

    private val OFFSET_BEFORE_PATTERNS = listOf(
        Regex("""(\d+)\s*시간\s*전(?:에|쯤)?"""),
        Regex("""(\d+)\s*분\s*전(?:에|쯤)?"""),
    )

    private val OFFSET_AFTER_PATTERNS = listOf(
        Regex("""(\d+)\s*시간\s*(?:후|뒤)(?:에|쯤)?"""),
        Regex("""(\d+)\s*분\s*(?:후|뒤)(?:에|쯤)?"""),
    )

    fun parse(input: String): OffsetResult? {
        for (pattern in OFFSET_BEFORE_PATTERNS) {
            val match = pattern.find(input) ?: continue
            val amount = match.groupValues[1].toIntOrNull() ?: continue
            if (amount <= 0) continue
            val minutes = if (match.value.contains("시간")) amount * 60 else amount
            return OffsetResult(offsetMinutes = -minutes, matchedText = match.value)
        }

        for (pattern in OFFSET_AFTER_PATTERNS) {
            val match = pattern.find(input) ?: continue
            val amount = match.groupValues[1].toIntOrNull() ?: continue
            if (amount <= 0) continue
            val minutes = if (match.value.contains("시간")) amount * 60 else amount
            return OffsetResult(offsetMinutes = minutes, matchedText = match.value)
        }

        return null
    }

    fun removeOffset(input: String, matchedText: String): String =
        input.replace(matchedText, "").replace(Regex("\\s+"), " ").trim()
}

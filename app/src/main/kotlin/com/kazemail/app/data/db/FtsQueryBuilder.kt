package com.kazemail.app.data.db

import androidx.room3.RoomRawQuery
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Builds parameterized SQLite queries against `messages_meta` and `messages_fts` (trigram).
 * Supports operators: `from:`, `has:attachment`, `is:unread`, `before:YYYY-MM-DD`, `after:YYYY-MM-DD`.
 * Short terms (<3 chars) fall back cleanly to LIKE so trigram index limits never hide matches.
 */
object FtsQueryBuilder {
    private const val DEFAULT_LIMIT = 200

    data class ParsedSearchCriteria(
        val fromFilter: String? = null,
        val hasAttachment: Boolean? = null,
        val isUnreadOnly: Boolean = false,
        val beforeMs: Long? = null,
        val afterMs: Long? = null,
        val freeTerms: List<String> = emptyList(),
    )

    fun parse(rawInput: String): ParsedSearchCriteria {
        val tokens = tokenizeRespectingQuotes(rawInput)
        var fromFilter: String? = null
        var hasAttachment: Boolean? = null
        var isUnreadOnly = false
        var beforeMs: Long? = null
        var afterMs: Long? = null
        val freeTerms = mutableListOf<String>()

        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower.startsWith("from:") && token.length > 5 -> {
                    fromFilter = token.substring(5).trim('"')
                }
                lower == "has:attachment" -> hasAttachment = true
                lower == "is:unread" -> isUnreadOnly = true
                lower.startsWith("before:") && token.length > 7 -> {
                    beforeMs = parseDateStartMs(token.substring(7).trim('"'))
                }
                lower.startsWith("after:") && token.length > 6 -> {
                    afterMs = parseDateStartMs(token.substring(6).trim('"'))
                }
                token.isNotBlank() -> freeTerms += token
            }
        }

        return ParsedSearchCriteria(
            fromFilter = fromFilter?.takeIf { it.isNotBlank() },
            hasAttachment = hasAttachment,
            isUnreadOnly = isUnreadOnly,
            beforeMs = beforeMs,
            afterMs = afterMs,
            freeTerms = freeTerms,
        )
    }

    fun buildSearchQuery(
        rawInput: String,
        accountId: Long? = null,
        limit: Int = DEFAULT_LIMIT,
    ): RoomRawQuery {
        val criteria = parse(rawInput)
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        val trigramTerms = criteria.freeTerms.filter { it.length >= 3 }
        val shortTerms = criteria.freeTerms.filter { it.length < 3 }

        val joinFts = trigramTerms.isNotEmpty()
        if (joinFts) {
            val ftsMatchExpr = trigramTerms.joinToString(" ") { escapeFtsPhrase(it) }
            whereClauses += "messages_fts MATCH ?"
            args += ftsMatchExpr
        }

        for (shortTerm in shortTerms) {
            val escaped = escapeLike(shortTerm)
            whereClauses += "(m.subject LIKE ? ESCAPE '\\' OR m.sender_name LIKE ? ESCAPE '\\' OR m.sender_address LIKE ? ESCAPE '\\' OR m.snippet LIKE ? ESCAPE '\\')"
            repeat(4) { args += "%$escaped%" }
        }

        if (accountId != null) {
            whereClauses += "m.account_id = ?"
            args += accountId
        }
        criteria.fromFilter?.let { from ->
            val escaped = escapeLike(from)
            whereClauses += "(m.sender_name LIKE ? ESCAPE '\\' OR m.sender_address LIKE ? ESCAPE '\\')"
            args += "%$escaped%"
            args += "%$escaped%"
        }
        if (criteria.hasAttachment == true) {
            whereClauses += "m.has_attachments = 1"
        }
        if (criteria.isUnreadOnly) {
            whereClauses += "m.is_read = 0"
        }
        criteria.beforeMs?.let { before ->
            whereClauses += "m.received_at < ?"
            args += before
        }
        criteria.afterMs?.let { after ->
            whereClauses += "m.received_at >= ?"
            args += after
        }

        val sql = buildString {
            append("SELECT m.* FROM messages_meta m ")
            if (joinFts) {
                append("INNER JOIN messages_fts ON messages_fts.meta_id = m.id ")
            }
            if (whereClauses.isNotEmpty()) {
                append("WHERE ")
                append(whereClauses.joinToString(" AND "))
                append(' ')
            }
            append("ORDER BY m.received_at DESC LIMIT ?")
        }
        args += limit.coerceIn(1, 500)

        return RoomRawQuery(sql = sql) { stmt ->
            args.forEachIndexed { idx, value ->
                val bindIndex = idx + 1
                when (value) {
                    is Long -> stmt.bindLong(bindIndex, value)
                    is Int -> stmt.bindLong(bindIndex, value.toLong())
                    is String -> stmt.bindText(bindIndex, value)
                }
            }
        }
    }

    private fun escapeFtsPhrase(term: String): String {
        val cleaned = term.replace("\"", "\"\"")
        return "\"$cleaned\""
    }

    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun parseDateStartMs(isoDate: String): Long? =
        runCatching {
            LocalDate.parse(isoDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

    private fun tokenizeRespectingQuotes(input: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in input.trim()) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            out += current.toString()
        }
        return out
    }
}

package com.kazemail.app.filter

import com.kazemail.app.data.db.LocalFilterRuleEntity
import com.kazemail.app.data.db.MessageMetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class FilterField(val label: String) {
    SENDER_ANY("送信元（名前・アドレス）"),
    SENDER_ADDRESS("送信元メールアドレス"),
    SENDER_NAME("送信者名"),
    RECIPIENT_TO("宛先 (To)"),
    SUBJECT("件名"),
    BODY_OR_SNIPPET("本文・スニペット"),
    RAW_HEADERS("生ヘッダ (List-Id / X-Mailer 等)"),
    HAS_ATTACHMENTS("添付ファイルあり"),
}

@Serializable
enum class FilterOperator(val label: String) {
    CONTAINS("を含む"),
    NOT_CONTAINS("を含まない"),
    EQUALS("と完全一致"),
    STARTS_WITH("で始まる"),
    ENDS_WITH("で終わる"),
    REGEX("正規表現に一致"),
    IS_TRUE("あり（真）"),
    IS_FALSE("なし（偽）"),
}

@Serializable
data class LocalFilterCondition(
    val field: FilterField = FilterField.SENDER_ANY,
    val operator: FilterOperator = FilterOperator.CONTAINS,
    val value: String = "",
    val caseSensitive: Boolean = false,
)

data class FilterEvaluationTarget(
    val meta: MessageMetaEntity,
    val plainText: String? = null,
    val rawHeaders: String? = null,
)

data class FilterEvaluationResult(
    val matchedRuleIds: List<Long> = emptyList(),
    val targetLocalFolder: String? = null,
    val hideFromFolder: Boolean = false,
    val archiveLocally: Boolean = false,
    val markRead: Boolean = false,
    val markFlagged: Boolean = false,
    val suppressNotification: Boolean = false,
) {
    val hasAnyAction: Boolean
        get() = targetLocalFolder != null || archiveLocally || markRead || markFlagged || suppressNotification
}

/**
 * Pure client-side automatic sorting / filtering engine.
 * Operates strictly on local SQLite state — never issues destructive commands to IMAP servers.
 */
object LocalFilterEngine {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeConditions(conditions: List<LocalFilterCondition>): String =
        json.encodeToString(conditions)

    fun decodeConditions(rawJson: String?): List<LocalFilterCondition> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<LocalFilterCondition>>(rawJson) }
            .getOrDefault(emptyList())
    }

    fun formatConditionSummary(condition: LocalFilterCondition): String =
        when (condition.field) {
            FilterField.HAS_ATTACHMENTS -> {
                val boolText = if (condition.operator == FilterOperator.IS_FALSE) "なし" else "あり"
                "${condition.field.label} = $boolText"
            }
            else -> "${condition.field.label} ${condition.operator.label} 「${condition.value}」"
        }

    fun evaluate(
        rules: List<LocalFilterRuleEntity>,
        target: FilterEvaluationTarget,
    ): FilterEvaluationResult {
        if (rules.isEmpty()) return FilterEvaluationResult()

        val sortedRules = rules
            .filter { it.isEnabled }
            .sortedWith(compareBy<LocalFilterRuleEntity> { it.priority }.thenBy { it.ruleId })

        val matchedIds = mutableListOf<Long>()
        var localFolder: String? = null
        var hideFromFolder = false
        var archiveLocally = false
        var markRead = false
        var markFlagged = false
        var suppressNotification = false

        for (rule in sortedRules) {
            val conditions = decodeConditions(rule.conditionsJson)
            if (!matchesRule(rule.matchMode, conditions, target)) {
                continue
            }

            matchedIds += rule.ruleId

            val cleanFolder = rule.targetLocalFolder?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanFolder != null && localFolder == null) {
                localFolder = cleanFolder
                hideFromFolder = rule.hideFromFolder
            }
            if (rule.archiveLocally) archiveLocally = true
            if (rule.markRead) markRead = true
            if (rule.markFlagged) markFlagged = true
            if (rule.suppressNotification) suppressNotification = true

            if (rule.stopProcessing) {
                break
            }
        }

        return FilterEvaluationResult(
            matchedRuleIds = matchedIds,
            targetLocalFolder = localFolder,
            hideFromFolder = localFolder != null && hideFromFolder,
            archiveLocally = archiveLocally,
            markRead = markRead,
            markFlagged = markFlagged,
            suppressNotification = suppressNotification,
        )
    }

    fun matchesRule(
        matchMode: String,
        conditions: List<LocalFilterCondition>,
        target: FilterEvaluationTarget,
    ): Boolean {
        if (conditions.isEmpty()) return false
        return if (matchMode.equals(LocalFilterRuleEntity.MATCH_MODE_ANY, ignoreCase = true)) {
            conditions.any { matchesCondition(it, target) }
        } else {
            conditions.all { matchesCondition(it, target) }
        }
    }

    fun matchesCondition(
        condition: LocalFilterCondition,
        target: FilterEvaluationTarget,
    ): Boolean {
        val meta = target.meta
        if (condition.field == FilterField.HAS_ATTACHMENTS) {
            return when (condition.operator) {
                FilterOperator.IS_FALSE -> !meta.hasAttachments
                else -> meta.hasAttachments
            }
        }

        val needle = condition.value
        if (needle.isEmpty()) return false

        val candidateTexts: List<String> = when (condition.field) {
            FilterField.SENDER_ANY -> listOfNotNull(
                meta.senderName?.takeIf { it.isNotBlank() },
                meta.senderAddress,
            )
            FilterField.SENDER_ADDRESS -> listOf(meta.senderAddress)
            FilterField.SENDER_NAME -> listOf(meta.senderName.orEmpty())
            FilterField.RECIPIENT_TO -> listOf(meta.recipientTo.orEmpty())
            FilterField.SUBJECT -> listOf(meta.subject.orEmpty())
            FilterField.BODY_OR_SNIPPET -> listOf(
                listOfNotNull(meta.snippet, target.plainText).joinToString("\n"),
            )
            FilterField.RAW_HEADERS -> listOf(target.rawHeaders.orEmpty())
            FilterField.HAS_ATTACHMENTS -> emptyList()
        }

        if (condition.operator == FilterOperator.NOT_CONTAINS) {
            return candidateTexts.all { haystack ->
                !matchesStringOperator(
                    haystack = haystack,
                    operator = FilterOperator.CONTAINS,
                    needle = needle,
                    caseSensitive = condition.caseSensitive,
                )
            }
        }

        return candidateTexts.any { haystack ->
            matchesStringOperator(
                haystack = haystack,
                operator = condition.operator,
                needle = needle,
                caseSensitive = condition.caseSensitive,
            )
        }
    }

    private fun matchesStringOperator(
        haystack: String,
        operator: FilterOperator,
        needle: String,
        caseSensitive: Boolean,
    ): Boolean {
        return when (operator) {
            FilterOperator.CONTAINS -> haystack.contains(needle, ignoreCase = !caseSensitive)
            FilterOperator.NOT_CONTAINS -> !haystack.contains(needle, ignoreCase = !caseSensitive)
            FilterOperator.EQUALS -> {
                if (caseSensitive) haystack.trim() == needle.trim()
                else haystack.trim().equals(needle.trim(), ignoreCase = true)
            }
            FilterOperator.STARTS_WITH -> haystack.trimStart().startsWith(needle, ignoreCase = !caseSensitive)
            FilterOperator.ENDS_WITH -> haystack.trimEnd().endsWith(needle, ignoreCase = !caseSensitive)
            FilterOperator.REGEX -> {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                runCatching { Regex(needle, options).containsMatchIn(haystack) }.getOrDefault(false)
            }
            FilterOperator.IS_TRUE -> haystack.isNotBlank()
            FilterOperator.IS_FALSE -> haystack.isBlank()
        }
    }
}

object FilterRuleTemplates {
    data class TemplateSpec(
        val id: String,
        val label: String,
        val name: String,
        val matchMode: String,
        val conditions: List<LocalFilterCondition>,
        val targetLocalFolder: String?,
        val hideFromFolder: Boolean,
        val archiveLocally: Boolean,
        val markRead: Boolean,
        val markFlagged: Boolean,
        val suppressNotification: Boolean,
    )

    val presets: List<TemplateSpec> = listOf(
        TemplateSpec(
            id = "receipts",
            label = "請求書・領収書を仕分け",
            name = "請求書・領収書・注文確認",
            matchMode = LocalFilterRuleEntity.MATCH_MODE_ANY,
            conditions = listOf(
                LocalFilterCondition(
                    field = FilterField.SUBJECT,
                    operator = FilterOperator.REGEX,
                    value = "請求|領収書|注文確認|ご請求|Invoice|Receipt",
                ),
                LocalFilterCondition(
                    field = FilterField.SENDER_ANY,
                    operator = FilterOperator.CONTAINS,
                    value = "billing",
                ),
            ),
            targetLocalFolder = "請求・注文",
            hideFromFolder = true,
            archiveLocally = false,
            markRead = false,
            markFlagged = false,
            suppressNotification = false,
        ),
        TemplateSpec(
            id = "newsletter",
            label = "メルマガ自動既読＋通知オフ",
            name = "メルマガ・広告（自動既読）",
            matchMode = LocalFilterRuleEntity.MATCH_MODE_ANY,
            conditions = listOf(
                LocalFilterCondition(
                    field = FilterField.RAW_HEADERS,
                    operator = FilterOperator.REGEX,
                    value = "(List-Unsubscribe:|Precedence:\\s*bulk)",
                ),
                LocalFilterCondition(
                    field = FilterField.SENDER_ADDRESS,
                    operator = FilterOperator.REGEX,
                    value = "^(no-reply|noreply|newsletter|magazine)@",
                ),
            ),
            targetLocalFolder = "メルマガ",
            hideFromFolder = true,
            archiveLocally = false,
            markRead = true,
            markFlagged = false,
            suppressNotification = true,
        ),
        TemplateSpec(
            id = "important_attachment",
            label = "添付あり重要メールに★",
            name = "添付付きビジネス重要メール",
            matchMode = LocalFilterRuleEntity.MATCH_MODE_ALL,
            conditions = listOf(
                LocalFilterCondition(
                    field = FilterField.HAS_ATTACHMENTS,
                    operator = FilterOperator.IS_TRUE,
                    value = "true",
                ),
                LocalFilterCondition(
                    field = FilterField.SUBJECT,
                    operator = FilterOperator.REGEX,
                    value = "契約|見積|重要|至急|確認|資料",
                ),
            ),
            targetLocalFolder = null,
            hideFromFolder = false,
            archiveLocally = false,
            markRead = false,
            markFlagged = true,
            suppressNotification = false,
        ),
    )
}

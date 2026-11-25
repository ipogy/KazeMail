package com.kazemail.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object InboxKey : NavKey

@Serializable
data class MessageDetailKey(
    val accountId: Long,
    val metaId: Long,
    val threadId: String?,
    val isThreadMode: Boolean,
) : NavKey

@Serializable
data class ComposeKey(
    val accountId: Long,
    val draftId: Long? = null,
    val replyToMetaId: Long? = null,
    val mode: String = "NEW",
    val initialAiTone: String? = null,
    val initialAiInstruction: String? = null,
    val initialAiPreferHtml: Boolean? = null,
    val autoTriggerAi: Boolean = false,
    val initialAiReferenceMetaIds: List<Long> = emptyList(),
    val initialAiReferenceFilesJson: String? = null,
    val initialAiClarificationQuestionsJson: String? = null,
    val initialAiClarificationAnswersJson: String? = null,
    val initialAiClarificationSummary: String? = null,
    val externalTo: List<String> = emptyList(),
    val externalCc: List<String> = emptyList(),
    val externalBcc: List<String> = emptyList(),
    val externalSubject: String = "",
    val externalBody: String = "",
    val externalAttachmentUriStrings: List<String> = emptyList(),
    val externalLaunchNonce: Long = 0L,
) : NavKey

@Serializable
data class AddAccountKey(
    val initialK9sUriString: String? = null,
    val editAccountId: Long? = null,
) : NavKey

@Serializable data object SettingsKey : NavKey

@Serializable data object AiSettingsKey : NavKey

@Serializable
data class AccountSettingsKey(
    val accountId: Long,
) : NavKey

@Serializable data object DashboardKey : NavKey

package com.kazemail.app

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kazemail.app.ai.AiClarificationAnswer
import com.kazemail.app.ai.AiClarificationPlan
import com.kazemail.app.ai.AiClarificationQuestion
import com.kazemail.app.ai.AiReferenceFileSpec
import com.kazemail.app.ui.account.AccountSettingsScreen
import com.kazemail.app.ui.account.AccountSettingsViewModel
import com.kazemail.app.ui.account.AddAccountScreen
import com.kazemail.app.ui.account.AddAccountViewModel
import com.kazemail.app.ui.ai.AiSettingsScreen
import com.kazemail.app.ui.ai.AiSettingsViewModel
import com.kazemail.app.ui.compose.ComposeExternalPrefill
import com.kazemail.app.ui.compose.ComposeScreen
import com.kazemail.app.ui.compose.ComposeViewModel
import com.kazemail.app.ui.dashboard.DashboardScreen
import com.kazemail.app.ui.dashboard.DashboardViewModel
import com.kazemail.app.ui.inbox.InboxScreen
import com.kazemail.app.ui.inbox.InboxViewModel
import com.kazemail.app.ui.settings.SettingsScreen
import com.kazemail.app.ui.settings.SettingsViewModel
import com.kazemail.app.ui.viewer.MessageDetailScreen
import com.kazemail.app.ui.viewer.MessageDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class NotificationDeepLink(
    val accountId: Long,
    val metaId: Long?,
    val threadId: String?,
)

@Composable
fun MainNavigation(
    deepLink: NotificationDeepLink? = null,
    onConsumeDeepLink: () -> Unit = {},
    pendingImportUris: List<Uri> = emptyList(),
    onConsumeImportUris: () -> Unit = {},
    externalCompose: ExternalComposeRequest? = null,
    onConsumeExternalCompose: () -> Unit = {},
) {
    val backStack = rememberNavBackStack(InboxKey)
    val context = LocalContext.current
    val container = (context.applicationContext as KazeMailApp).container
    val inboxVm: InboxViewModel = viewModel(factory = InboxViewModel.factory(container))
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, inboxVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                inboxVm.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            inboxVm.selectAccount(deepLink.accountId)
            if (deepLink.metaId != null && deepLink.metaId > 0L) {
                backStack.add(
                    MessageDetailKey(
                        accountId = deepLink.accountId,
                        metaId = deepLink.metaId,
                        threadId = deepLink.threadId,
                        isThreadMode = !deepLink.threadId.isNullOrBlank(),
                    ),
                )
            }
            onConsumeDeepLink()
        }
    }

    LaunchedEffect(pendingImportUris) {
        if (pendingImportUris.isNotEmpty()) {
            inboxVm.importArchiveFiles(
                context = context,
                uris = pendingImportUris,
                onK9sFileDetected = { k9sUri ->
                    backStack.add(AddAccountKey(initialK9sUriString = k9sUri.toString()))
                },
            )
            onConsumeImportUris()
        }
    }

    LaunchedEffect(externalCompose) {
        if (externalCompose != null) {
            val selectedId = inboxVm.uiState.value.selectedAccountId
            val resolvedAccountId = withContext(Dispatchers.IO) {
                val accounts = container.awaitDatabase().accountDao().getAll()
                accounts.firstOrNull { it.accountId == selectedId }?.accountId
                    ?: accounts.firstOrNull()?.accountId
            }
            if (resolvedAccountId == null) {
                Toast.makeText(
                    context,
                    "メールを作成するには、先にアカウントを追加してください",
                    Toast.LENGTH_LONG,
                ).show()
                backStack.add(AddAccountKey())
            } else {
                backStack.add(
                    ComposeKey(
                        accountId = resolvedAccountId,
                        mode = "NEW",
                        externalTo = externalCompose.toAddresses,
                        externalCc = externalCompose.ccAddresses,
                        externalBcc = externalCompose.bccAddresses,
                        externalSubject = externalCompose.subject,
                        externalBody = externalCompose.bodyText,
                        externalAttachmentUriStrings = externalCompose.attachmentUris.map(Uri::toString),
                        externalLaunchNonce = externalCompose.eventId,
                    ),
                )
            }
            onConsumeExternalCompose()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<InboxKey> {
                InboxScreen(
                    viewModel = inboxVm,
                    onOpenMessage = { accountId, metaId, threadId, isThreadMode ->
                        backStack.add(
                            MessageDetailKey(
                                accountId = accountId,
                                metaId = metaId,
                                threadId = threadId,
                                isThreadMode = isThreadMode,
                            ),
                        )
                    },
                    onComposeNew = { accountId ->
                        backStack.add(
                            ComposeKey(
                                accountId = accountId,
                                mode = "NEW",
                            ),
                        )
                    },
                    onOpenDraft = { accountId, draftId ->
                        backStack.add(
                            ComposeKey(
                                accountId = accountId,
                                draftId = draftId,
                                mode = "NEW",
                            ),
                        )
                    },
                    onAddAccountClick = { backStack.add(AddAccountKey()) },
                    onImportK9sUri = { uri ->
                        backStack.add(AddAccountKey(initialK9sUriString = uri.toString()))
                    },
                    onOpenSettings = { backStack.add(SettingsKey) },
                    onOpenAccountSettings = { accId ->
                        backStack.add(AccountSettingsKey(accountId = accId))
                    },
                    onOpenSyncDiagnostics = { backStack.add(DashboardKey) },
                )
            }
            entry<AccountSettingsKey> { key ->
                val vm: AccountSettingsViewModel = viewModel(
                    key = "acc_settings_${key.accountId}",
                    factory = AccountSettingsViewModel.factory(container, key.accountId),
                )
                AccountSettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onEditConnectionClick = { accId ->
                        backStack.add(AddAccountKey(editAccountId = accId))
                    },
                    onOpenAppSettings = { backStack.add(SettingsKey) },
                    onOpenSyncDiagnostics = { backStack.add(DashboardKey) },
                )
            }
            entry<SettingsKey> {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(container, context),
                )
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onAddAccountClick = { backStack.add(AddAccountKey()) },
                    onEditAccountClick = { accountId ->
                        backStack.add(AddAccountKey(editAccountId = accountId))
                    },
                    onImportK9sUri = { uri ->
                        backStack.add(AddAccountKey(initialK9sUriString = uri.toString()))
                    },
                    onImportArchiveUris = { uris ->
                        inboxVm.importArchiveFiles(
                            context = context,
                            uris = uris,
                            onK9sFileDetected = { k9sUri ->
                                backStack.add(AddAccountKey(initialK9sUriString = k9sUri.toString()))
                            },
                        )
                        backStack.removeLastOrNull()
                    },
                    onOpenSyncDiagnostics = { backStack.add(DashboardKey) },
                    onOpenAiSettings = { backStack.add(AiSettingsKey) },
                )
            }
            entry<AiSettingsKey> {
                val vm: AiSettingsViewModel = viewModel(
                    factory = AiSettingsViewModel.factory(container),
                )
                AiSettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { backStack.removeLastOrNull() },
                )
            }
            entry<MessageDetailKey> { key ->
                val detailVm: MessageDetailViewModel = viewModel(
                    key = "detail_${key.metaId}_${key.isThreadMode}",
                    factory = MessageDetailViewModel.factory(
                        container = container,
                        accountId = key.accountId,
                        metaId = key.metaId,
                        threadId = key.threadId,
                        isThreadMode = key.isThreadMode,
                    ),
                )
                MessageDetailScreen(
                    viewModel = detailVm,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onArchivedWithUndo = { archivedIds ->
                        inboxVm.notifyExternalArchived(archivedIds)
                    },
                    onComposeReply = { accId, targetMetaId, mode ->
                        backStack.add(
                            ComposeKey(
                                accountId = accId,
                                replyToMetaId = targetMetaId,
                                mode = mode,
                            ),
                        )
                    },
                    onComposeAiReply = { accId, targetMetaId, mode, aiTone, aiInstruction, aiPreferHtml, autoTriggerAi, aiRefMetaIds, aiRefFilesJson, aiClarifyQsJson, aiClarifyAnsJson, aiClarifySummary ->
                        backStack.add(
                            ComposeKey(
                                accountId = accId,
                                replyToMetaId = targetMetaId,
                                mode = mode,
                                initialAiTone = aiTone,
                                initialAiInstruction = aiInstruction,
                                initialAiPreferHtml = aiPreferHtml,
                                autoTriggerAi = autoTriggerAi,
                                initialAiReferenceMetaIds = aiRefMetaIds,
                                initialAiReferenceFilesJson = aiRefFilesJson,
                                initialAiClarificationQuestionsJson = aiClarifyQsJson,
                                initialAiClarificationAnswersJson = aiClarifyAnsJson,
                                initialAiClarificationSummary = aiClarifySummary,
                            ),
                        )
                    },
                    onOpenAiSettings = { backStack.add(AiSettingsKey) },
                )
            }
            entry<ComposeKey> { key ->
                val hasExternalPrefill = key.externalTo.isNotEmpty() ||
                    key.externalCc.isNotEmpty() ||
                    key.externalBcc.isNotEmpty() ||
                    key.externalSubject.isNotEmpty() ||
                    key.externalBody.isNotEmpty() ||
                    key.externalAttachmentUriStrings.isNotEmpty()
                val initialRefFiles = key.initialAiReferenceFilesJson?.let { raw ->
                    runCatching {
                        Json.decodeFromString(ListSerializer(AiReferenceFileSpec.serializer()), raw)
                    }.getOrDefault(emptyList())
                }.orEmpty()
                val initialClarifyPlan = key.initialAiClarificationQuestionsJson?.let { raw ->
                    val qs = runCatching {
                        Json.decodeFromString(ListSerializer(AiClarificationQuestion.serializer()), raw)
                    }.getOrDefault(emptyList())
                    if (qs.isNotEmpty()) {
                        AiClarificationPlan(
                            summary = key.initialAiClarificationSummary.orEmpty(),
                            questions = qs,
                        )
                    } else {
                        null
                    }
                }
                val initialClarifyAnswers = key.initialAiClarificationAnswersJson?.let { raw ->
                    runCatching {
                        Json.decodeFromString(ListSerializer(AiClarificationAnswer.serializer()), raw)
                    }.getOrDefault(emptyList())
                }.orEmpty().associateBy { it.questionId }
                val composeVm: ComposeViewModel = viewModel(
                    key = "compose_${key.accountId}_${key.draftId}_${key.replyToMetaId}_${key.mode}_${key.initialAiTone}_${key.autoTriggerAi}_${key.externalLaunchNonce}_${key.initialAiReferenceMetaIds.joinToString(",")}_${key.initialAiReferenceFilesJson?.hashCode() ?: 0}_${key.initialAiClarificationAnswersJson?.hashCode() ?: 0}",
                    factory = ComposeViewModel.factory(
                        container = container,
                        accountId = key.accountId,
                        draftId = key.draftId,
                        replyToMetaId = key.replyToMetaId,
                        mode = key.mode,
                        initialAiTone = key.initialAiTone,
                        initialAiInstruction = key.initialAiInstruction,
                        initialAiPreferHtml = key.initialAiPreferHtml,
                        autoTriggerAi = key.autoTriggerAi,
                        externalPrefill = if (hasExternalPrefill) {
                            ComposeExternalPrefill(
                                to = key.externalTo,
                                cc = key.externalCc,
                                bcc = key.externalBcc,
                                subject = key.externalSubject,
                                bodyText = key.externalBody,
                                attachmentUris = key.externalAttachmentUriStrings.map(Uri::parse),
                            )
                        } else {
                            null
                        },
                        context = context,
                        initialAiReferenceMetaIds = key.initialAiReferenceMetaIds,
                        initialAiReferenceFiles = initialRefFiles,
                        initialAiClarificationPlan = initialClarifyPlan,
                        initialAiClarificationAnswers = initialClarifyAnswers,
                    ),
                )
                ComposeScreen(
                    viewModel = composeVm,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onMessageQueued = { inboxVm.notifyMessageQueued() },
                    onOpenAiSettings = { backStack.add(AiSettingsKey) },
                )
            }
            entry<AddAccountKey> { key ->
                val vm: AddAccountViewModel = viewModel(
                    key = "add_acc_${key.editAccountId ?: 0}_${key.initialK9sUriString.orEmpty()}",
                    factory = AddAccountViewModel.factory(container, key.editAccountId),
                )
                AddAccountScreen(
                    viewModel = vm,
                    initialK9sUriString = key.initialK9sUriString,
                    onNavigateBack = { backStack.removeLastOrNull() },
                )
            }
            entry<DashboardKey> {
                val vm: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.factory(container),
                )
                DashboardScreen(
                    viewModel = vm,
                    onAddAccountClick = { backStack.add(AddAccountKey()) },
                    onNavigateBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

package com.kazemail.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.LocalCategorySummaryTuple
import com.kazemail.app.data.db.LocalFilterRuleEntity
import com.kazemail.app.data.model.AccountSignatureResolver
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.filter.FilterRuleTemplates
import com.kazemail.app.filter.LocalFilterCondition
import com.kazemail.app.filter.LocalFilterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountSettingsUiState(
    val hasLoadedAccount: Boolean = false,
    val account: AccountEntity? = null,
    val messageCount: Int? = null,
    val rules: List<LocalFilterRuleEntity> = emptyList(),
    val existingCategories: List<LocalCategorySummaryTuple> = emptyList(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val editingRuleDraft: LocalFilterRuleDraft? = null,
    val deletedAccount: Boolean = false,
    val signatureEnabled: Boolean = false,
    val signatureText: String = "",
    val signatureInheritedFromLegacy: Boolean = false,
)

data class LocalFilterRuleDraft(
    val ruleId: Long = 0,
    val name: String = "",
    val isEnabled: Boolean = true,
    val matchMode: String = LocalFilterRuleEntity.MATCH_MODE_ALL,
    val conditions: List<LocalFilterCondition> = listOf(LocalFilterCondition()),
    val targetLocalFolder: String = "",
    val hideFromFolder: Boolean = true,
    val archiveLocally: Boolean = false,
    val markRead: Boolean = false,
    val markFlagged: Boolean = false,
    val suppressNotification: Boolean = false,
    val stopProcessing: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModel(
    private val container: KazeMailContainer,
    private val accountId: Long,
) : ViewModel() {

    private val databaseFlow = flow {
        emit(container.awaitDatabase())
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val db get() = container.database
    private val repo get() = container.messageRepository
    private val prefs = container.appPreferences

    private val isBusyFlow = MutableStateFlow(false)
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    private val editingRuleDraftFlow = MutableStateFlow<LocalFilterRuleDraft?>(null)
    private val deletedAccountFlow = MutableStateFlow(false)

    private val accountWithLoadedFlow = databaseFlow
        .flatMapLatest { database ->
            database.accountDao().observeAll().map { accounts ->
                true to accounts.firstOrNull { it.accountId == accountId }
            }
        }
        .onStart { emit(false to null) }

    private val countForAccountFlow = databaseFlow
        .flatMapLatest { database ->
            database.messageMetaDao().observeMessageCountsByAccount().map { tuples ->
                tuples.firstOrNull { it.accountId == accountId }?.count ?: 0
            }
        }
        .onStart { emit(-1) }

    private val rulesFlow = databaseFlow
        .flatMapLatest { database -> database.localFilterRuleDao().observeByAccount(accountId) }
        .onStart { emit(emptyList()) }

    private val categoriesFlow = databaseFlow
        .flatMapLatest { database -> database.messageLocalStateDao().observeLocalCategorySummaries(accountId) }
        .onStart { emit(emptyList()) }

    private val repoFlow = combine(
        accountWithLoadedFlow,
        countForAccountFlow,
        rulesFlow,
        categoriesFlow,
    ) { (loaded, account), msgCount, rules, categories ->
        AccountRepoSnapshot(loaded, account, msgCount.takeIf { it >= 0 }, rules, categories)
    }

    val uiState: StateFlow<AccountSettingsUiState> = combine(
        repoFlow,
        isBusyFlow,
        statusMessageFlow,
        editingRuleDraftFlow,
        combine(deletedAccountFlow, prefs.signatureEnabled, prefs.signatureText, ::Triple),
    ) { snap, busy, status, draft, (deleted, legacyEnabled, legacyText) ->
        val sigState = AccountSignatureResolver.resolveState(
            account = snap.account,
            legacyEnabled = legacyEnabled,
            legacyText = legacyText,
        )
        AccountSettingsUiState(
            hasLoadedAccount = snap.hasLoadedAccount,
            account = snap.account,
            messageCount = snap.messageCount,
            rules = snap.rules,
            existingCategories = snap.categories,
            isBusy = busy,
            statusMessage = status,
            editingRuleDraft = draft,
            deletedAccount = deleted,
            signatureEnabled = sigState.enabled,
            signatureText = sigState.text,
            signatureInheritedFromLegacy = sigState.inheritedFromLegacyGlobal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountSettingsUiState())

    private data class AccountRepoSnapshot(
        val hasLoadedAccount: Boolean,
        val account: AccountEntity?,
        val messageCount: Int?,
        val rules: List<LocalFilterRuleEntity>,
        val categories: List<LocalCategorySummaryTuple>,
    )

    fun openNewRuleEditor() {
        editingRuleDraftFlow.value = LocalFilterRuleDraft()
    }

    fun openRuleEditor(rule: LocalFilterRuleEntity) {
        val parsed = LocalFilterEngine.decodeConditions(rule.conditionsJson)
        editingRuleDraftFlow.value = LocalFilterRuleDraft(
            ruleId = rule.ruleId,
            name = rule.name,
            isEnabled = rule.isEnabled,
            matchMode = rule.matchMode,
            conditions = if (parsed.isEmpty()) listOf(LocalFilterCondition()) else parsed,
            targetLocalFolder = rule.targetLocalFolder.orEmpty(),
            hideFromFolder = rule.hideFromFolder,
            archiveLocally = rule.archiveLocally,
            markRead = rule.markRead,
            markFlagged = rule.markFlagged,
            suppressNotification = rule.suppressNotification,
            stopProcessing = rule.stopProcessing,
        )
    }

    fun dismissRuleEditor() {
        editingRuleDraftFlow.value = null
    }

    fun addTemplateRule(template: FilterRuleTemplates.TemplateSpec) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val nextPriority = withContext(Dispatchers.IO) {
                (db.localFilterRuleDao().maxPriorityForAccount(accountId) ?: -1) + 1
            }
            val entity = LocalFilterRuleEntity(
                accountId = accountId,
                name = template.name,
                isEnabled = true,
                priority = nextPriority,
                matchMode = template.matchMode,
                conditionsJson = LocalFilterEngine.encodeConditions(template.conditions),
                targetLocalFolder = template.targetLocalFolder,
                hideFromFolder = template.hideFromFolder,
                archiveLocally = template.archiveLocally,
                markRead = template.markRead,
                markFlagged = template.markFlagged,
                suppressNotification = template.suppressNotification,
                stopProcessing = true,
                createdAt = now,
                updatedAt = now,
            )
            withContext(Dispatchers.IO) {
                db.localFilterRuleDao().upsert(entity)
            }
            statusMessageFlow.value = "テンプレート「${template.name}」を追加しました"
        }
    }

    fun saveRuleDraft(draft: LocalFilterRuleDraft) {
        val cleanName = draft.name.trim()
        if (cleanName.isEmpty()) {
            statusMessageFlow.value = "ルール名を入力してください"
            return
        }
        val cleanConditions = draft.conditions.filter { cond ->
            cond.field == com.kazemail.app.filter.FilterField.HAS_ATTACHMENTS || cond.value.isNotBlank()
        }
        if (cleanConditions.isEmpty()) {
            statusMessageFlow.value = "少なくとも1つの有効な条件を入力してください"
            return
        }
        val targetFolder = draft.targetLocalFolder.trim().takeIf { it.isNotEmpty() }
        val hasAction = targetFolder != null ||
            draft.archiveLocally ||
            draft.markRead ||
            draft.markFlagged ||
            draft.suppressNotification
        if (!hasAction) {
            statusMessageFlow.value = "実行するアクションを1つ以上選択してください"
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val existing = if (draft.ruleId > 0L) db.localFilterRuleDao().getById(draft.ruleId) else null
                val priority = existing?.priority
                    ?: ((db.localFilterRuleDao().maxPriorityForAccount(accountId) ?: -1) + 1)
                db.localFilterRuleDao().upsert(
                    LocalFilterRuleEntity(
                        ruleId = existing?.ruleId ?: 0L,
                        accountId = accountId,
                        name = cleanName,
                        isEnabled = draft.isEnabled,
                        priority = priority,
                        matchMode = draft.matchMode,
                        conditionsJson = LocalFilterEngine.encodeConditions(cleanConditions),
                        targetLocalFolder = targetFolder,
                        hideFromFolder = targetFolder != null && draft.hideFromFolder,
                        archiveLocally = draft.archiveLocally,
                        markRead = draft.markRead,
                        markFlagged = draft.markFlagged,
                        suppressNotification = draft.suppressNotification,
                        stopProcessing = draft.stopProcessing,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
            editingRuleDraftFlow.value = null
            statusMessageFlow.value = "自動振り分けルール「$cleanName」を保存しました"
        }
    }

    fun toggleRuleEnabled(rule: LocalFilterRuleEntity, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.localFilterRuleDao().updateEnabled(rule.ruleId, enabled, System.currentTimeMillis())
        }
    }

    fun moveRulePriority(ruleId: Long, direction: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.localFilterRuleDao().getAllByAccount(accountId)
            val idx = list.indexOfFirst { it.ruleId == ruleId }
            val targetIdx = idx + direction
            if (idx < 0 || targetIdx !in list.indices) return@launch
            val reordered = list.toMutableList()
            val item = reordered.removeAt(idx)
            reordered.add(targetIdx, item)
            val now = System.currentTimeMillis()
            reordered.forEachIndexed { index, r ->
                db.localFilterRuleDao().updatePriority(r.ruleId, index, now)
            }
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.localFilterRuleDao().deleteById(ruleId)
        }
        statusMessageFlow.value = "ルールを削除しました"
    }

    fun applyRulesToInboxNow() {
        if (isBusyFlow.value) return
        viewModelScope.launch {
            isBusyFlow.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.applyAccountFilterRulesNow(accountId)
                }
            }.onSuccess { matched ->
                statusMessageFlow.value = "受信トレイへローカルルールを適用しました（一致: ${matched}件）"
            }.onFailure { err ->
                statusMessageFlow.value = "ルール適用エラー: ${err.message}"
            }
            isBusyFlow.value = false
        }
    }

    fun syncAccountNow() {
        val acc = uiState.value.account ?: return
        if (acc.authType.equals("LOCAL", ignoreCase = true) || isBusyFlow.value) return
        viewModelScope.launch {
            isBusyFlow.value = true
            runCatching {
                container.syncEngine.runIncrementalSync(accountId)
            }.onSuccess { delta ->
                statusMessageFlow.value =
                    "同期完了: 新規 ${delta.newlySyncedCount} 件 / フラグ更新 ${delta.flagUpdatedCount} 件"
            }.onFailure { err ->
                statusMessageFlow.value = "同期エラー: ${err.message}"
            }
            isBusyFlow.value = false
        }
    }

    fun enqueueBackfill() {
        val acc = uiState.value.account ?: return
        if (acc.authType.equals("LOCAL", ignoreCase = true)) return
        container.syncScheduler.enqueueInitialBackfill(accountId, prefs.wifiOnlyBackfill.value)
        statusMessageFlow.value = "${acc.emailAddress} の全件バックフィル同期を予約しました"
    }

    fun saveAccountSignature(enabled: Boolean, signatureText: String) {
        viewModelScope.launch {
            val acc = uiState.value.account ?: return@launch
            withContext(Dispatchers.IO) {
                db.accountDao().updateSignature(accountId, enabled, signatureText)
            }
            statusMessageFlow.value = if (enabled) {
                "${acc.emailAddress} の署名を保存しました"
            } else {
                "${acc.emailAddress} の署名を無効にしました"
            }
        }
    }

    fun deleteAccountLocally() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.deleteLocalAccount(accountId)
            }
            deletedAccountFlow.value = true
        }
    }

    fun clearStatusMessage() {
        statusMessageFlow.value = null
    }

    companion object {
        fun factory(
            container: KazeMailContainer,
            accountId: Long,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AccountSettingsViewModel(container, accountId) as T
            }
    }
}

package com.kazemail.app.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room3.Room
import com.kazemail.app.ai.AiClient
import com.kazemail.app.ai.AiEmailContextBuilder
import com.kazemail.app.ai.AiHttpTransport
import com.kazemail.app.ai.AiPreparationService
import com.kazemail.app.ai.AiSecretVault
import com.kazemail.app.ai.HttpUrlConnectionAiTransport
import com.kazemail.app.ai.MultiProviderAiClient
import com.kazemail.app.auth.OAuth2Manager
import com.kazemail.app.data.AppPreferences
import com.kazemail.app.data.MessageRepository
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.importexport.LocalArchiveImporter
import com.kazemail.app.notifications.MailNotificationManager
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.AndroidKeystoreSecretCipher
import com.kazemail.app.security.DatabasePassphraseManager
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.send.OutboxManager
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.RustMailCoreEngine
import com.kazemail.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * Dependency container initialized lazily and pre-warmed off the main thread
 * so cold start and screen transitions stay non-blocking.
 */
class KazeMailContainer(context: Context) {
    private val appContext = context.applicationContext
    private val backgroundInitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val appPreferences: AppPreferences by lazy { AppPreferences(appContext) }

    val notificationManager: MailNotificationManager by lazy { MailNotificationManager(appContext) }

    val secretCipher: SecretCipher by lazy { AndroidKeystoreSecretCipher() }

    private val passphraseManager: DatabasePassphraseManager by lazy {
        DatabasePassphraseManager(appContext, secretCipher)
    }

    val database: KazeMailDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val keyBytes = passphraseManager.getOrCreatePassphraseBytes()
        val driver = SQLCipherDriver(keyBytes, null, null)
        val builder = Room.databaseBuilder<KazeMailDatabase>(
            context = appContext,
            name = KazeMailDatabase.DB_NAME,
        )
        KazeMailDatabase.buildWithDriver(builder, driver)
    }

    private val databaseDeferred: Deferred<KazeMailDatabase> by lazy {
        backgroundInitScope.async { database }
    }

    suspend fun awaitDatabase(): KazeMailDatabase = databaseDeferred.await()

    suspend fun awaitSyncScheduler(): SyncScheduler =
        withContext(Dispatchers.IO) { syncScheduler }

    fun hasInternetConnection(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun prewarmAsync() {
        backgroundInitScope.launch {
            runCatching { appPreferences }
            val db = runCatching { databaseDeferred.await() }.getOrNull()
            val scheduler = runCatching { syncScheduler }.getOrNull()
            if (db != null && scheduler != null) {
                runCatching {
                    val hasRemoteAccounts = db.accountDao().getAll()
                        .any { !it.authType.equals(NonDestructiveSyncEngine.AUTH_TYPE_LOCAL, ignoreCase = true) }
                    if (hasRemoteAccounts) {
                        scheduler.ensurePeriodicIncrementalSync(appPreferences.periodicSyncIntervalMinutes.value)
                    }
                }
            }
        }
    }

    val credentialStore: AccountCredentialStore by lazy {
        AccountCredentialStore(database.accountSecretDao(), secretCipher)
    }

    val oauth2Manager: OAuth2Manager by lazy { OAuth2Manager(appContext) }

    val mailCoreEngine: MailCoreEngine by lazy { RustMailCoreEngine() }

    val syncEngine: NonDestructiveSyncEngine by lazy {
        NonDestructiveSyncEngine(
            db = database,
            credentialStore = credentialStore,
            mailCore = mailCoreEngine,
            oauth2Manager = oauth2Manager,
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    val messageRepository: MessageRepository by lazy {
        MessageRepository(
            db = database,
            mailCore = mailCoreEngine,
            syncEngine = syncEngine,
        )
    }

    val outboxManager: OutboxManager by lazy {
        OutboxManager(
            db = database,
            credentialStore = credentialStore,
            mailCore = mailCoreEngine,
            oauth2Manager = oauth2Manager,
        )
    }

    val localArchiveImporter: LocalArchiveImporter by lazy {
        LocalArchiveImporter(
            db = database,
            mailCore = mailCoreEngine,
        )
    }

    val aiSecretVault: AiSecretVault by lazy {
        AiSecretVault(secretCipher)
    }

    val aiHttpTransport: AiHttpTransport by lazy {
        HttpUrlConnectionAiTransport()
    }

    val aiClient: AiClient by lazy {
        MultiProviderAiClient(transport = aiHttpTransport)
    }

    val aiEmailContextBuilder: AiEmailContextBuilder by lazy {
        AiEmailContextBuilder(
            decompressHtml = { compressed -> messageRepository.decompressHtmlInMemory(compressed) },
        )
    }

    val aiPreparationService: AiPreparationService by lazy {
        AiPreparationService(
            db = database,
            appPreferences = appPreferences,
            secretVault = aiSecretVault,
            aiClient = aiClient,
            messageRepository = messageRepository,
            contextBuilder = aiEmailContextBuilder,
        )
    }
}

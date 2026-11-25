package com.kazemail.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.notifications.MailNotificationManager
import com.kazemail.app.ui.theme.KazeMailTheme
import com.kazemail.app.ui.theme.resolveKazeDarkTheme
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = mutableStateOf<NotificationDeepLink?>(null)
    private val pendingImportUris = mutableStateOf<List<Uri>>(emptyList())
    private val pendingExternalCompose = mutableStateOf<ExternalComposeRequest?>(null)
    private val externalComposeCounter = AtomicLong(1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            dispatchIncomingIntent(intent)
        }

        val appPreferences = (application as KazeMailApp).container.appPreferences
        enableEdgeToEdge()
        setContent {
            val themeMode by appPreferences.themeMode.collectAsStateWithLifecycle()
            val darkTheme = resolveKazeDarkTheme(themeMode)
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }
            KazeMailTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavigation(
                        deepLink = pendingDeepLink.value,
                        onConsumeDeepLink = { pendingDeepLink.value = null },
                        pendingImportUris = pendingImportUris.value,
                        onConsumeImportUris = { pendingImportUris.value = emptyList() },
                        externalCompose = pendingExternalCompose.value,
                        onConsumeExternalCompose = { pendingExternalCompose.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingIntent(intent)
    }

    private fun dispatchIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        if (extractNotificationDeepLink(intent)) return

        when (val classified = ExternalMailIntentHelper.classifyIncomingIntent(this, intent) {
            externalComposeCounter.getAndIncrement()
        }) {
            is IncomingMailIntent.Compose -> pendingExternalCompose.value = classified.request
            is IncomingMailIntent.ImportArchive -> pendingImportUris.value = classified.uris
            IncomingMailIntent.None -> Unit
        }
    }

    private fun extractNotificationDeepLink(intent: Intent?): Boolean {
        val accId = intent?.getLongExtra(MailNotificationManager.EXTRA_NAV_ACCOUNT_ID, -1L) ?: -1L
        if (accId <= 0L) return false
        val metaId = intent?.getLongExtra(MailNotificationManager.EXTRA_NAV_META_ID, -1L)
            ?.takeIf { it > 0L }
        val threadId = intent?.getStringExtra(MailNotificationManager.EXTRA_NAV_THREAD_ID)
        pendingDeepLink.value = NotificationDeepLink(
            accountId = accId,
            metaId = metaId,
            threadId = threadId,
        )
        return true
    }
}

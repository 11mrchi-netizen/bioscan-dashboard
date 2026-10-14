package com.bioscan.fieldterminal

import androidx.compose.runtime.remember
import io.github.jan.supabase.auth.status.RefreshFailureCause
import com.bioscan.fieldterminal.ui.theme.FTType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.data.HealthConnectSyncWorker
import com.bioscan.fieldterminal.data.NotificationActionReceiver
import com.bioscan.fieldterminal.data.NotificationDispatchWorker
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppSyncWorker
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.nav.FieldTerminalNavHost
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import com.bioscan.fieldterminal.util.Perf
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

// Step 2 (auth) + Step 3 (navigation skeleton) + Step 4 (visual design,
// applied here to the sign-in/loading states -- the 4-tab UI itself is
// styled in FieldTerminalNavHost.kt and its screens). Real per-screen
// content is Phase C onward, per mobile-app-implementation-roadmap.md.
class MainActivity : ComponentActivity() {
    // The notification kind the user tapped, held until FieldTerminalNavHost (only shown once signed in) has navigated
    // to its destination and cleared it. Compose state, so a tap while the app is already open (onNewIntent) re-triggers it.
    private var pendingNotificationKind by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationKind = intent.getStringExtra(NotificationActionReceiver.EXTRA_KIND)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.enabled = BuildConfig.DEBUG // logcat tag "perf"; see util/Perf.kt
        // Notification channels, the Supabase client and the metric registry are warmed on a background thread by FieldTerminalApp.
        // Only on a fresh launch: after process restore the same intent is redelivered and would re-navigate.
        if (savedInstanceState == null) {
            pendingNotificationKind = intent?.getStringExtra(NotificationActionReceiver.EXTRA_KIND)
        }
        setContent {
            FieldTerminalTheme {
                AuthGate(
                    pendingNotificationKind = pendingNotificationKind,
                    onNotificationHandled = { pendingNotificationKind = null },
                )
            }
        }
    }
}

@Composable
private fun AuthGate(pendingNotificationKind: String?, onNotificationHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionStatus by SupabaseClientProvider.client.auth.sessionStatus.collectAsState()
    var signInError by remember { mutableStateOf<String?>(null) }
    val isAuthenticated = sessionStatus is SessionStatus.Authenticated

    // Phase G2: auto-sync once per app open, replacing the old manual/
    // chat-triggered Wellness Project sync for the metrics Health Connect
    // now covers. Enqueued as WorkManager background work (HealthConnectSyncWorker)
    // rather than run inline here -- a real on-device sync got killed
    // ("Software caused connection abort") the moment the user switched
    // apps mid-sync, since a plain Activity-scoped coroutine isn't
    // guaranteed to keep running once backgrounded. WorkManager survives
    // that (and even process death) and retries on failure with backoff.
    // enqueueUniqueWork + KEEP means re-opening the app while a sync is
    // still running/queued doesn't stack a second one.
    //
    // Keyed on the plain authenticated/not boolean, not the SessionStatus
    // object itself -- Authenticated carries the session token, which
    // changes on a routine background refresh, and keying on the whole
    // object would re-enqueue on every refresh.
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            HealthConnectSyncWorker.enqueue(context)
            ZeppSyncWorker.enqueue(context)
            NotificationDispatchWorker.enqueue(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FT.Base),
        contentAlignment = Alignment.Center,
    ) {
        when (sessionStatus) {
            is SessionStatus.Authenticated -> FieldTerminalNavHost(
                pendingNotificationKind = pendingNotificationKind,
                onNotificationHandled = onNotificationHandled,
            )
            is SessionStatus.NotAuthenticated -> {
                androidx.compose.runtime.LaunchedEffect(Unit) { com.bioscan.fieldterminal.data.ReadCache.invalidateAll() } // nothing of the last user survives sign-out
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AmberButton(label = "SIGN IN WITH GOOGLE") {
                        scope.launch { signInError = GoogleAuthManager.signIn(context).exceptionOrNull()?.message }
                    }
                    signInError?.let { Text("Sign-in failed: $it", style = FTType.Caption, color = FT.Critical) }
                }
            }
            is SessionStatus.Initializing -> CircularProgressIndicator(color = FT.Emerald)
            is SessionStatus.RefreshFailure -> {
                // supabase-kt keeps retrying the refresh by itself when the cause is the network, so an offline phone
                // with an expired access token is not a lost session: say so instead of demanding a sign-in. Only a
                // real auth failure (revoked or dead refresh token, mirrors the web dashboard's own handling) asks to sign in.
                if ((sessionStatus as SessionStatus.RefreshFailure).cause is RefreshFailureCause.NetworkError) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = FT.Emerald)
                        Text("Offline. Reconnecting when the network is back.", style = FTType.BodySmall, color = FT.TextSecondary)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AmberButton(label = "SESSION EXPIRED — SIGN IN AGAIN") {
                            scope.launch { signInError = GoogleAuthManager.signIn(context).exceptionOrNull()?.message }
                        }
                        signInError?.let { Text("Sign-in failed: $it", style = FTType.Caption, color = FT.Critical) }
                    }
                }
            }
        }
    }
}

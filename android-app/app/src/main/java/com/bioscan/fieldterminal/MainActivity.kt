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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
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
import com.bioscan.fieldterminal.domain.AuthState
import com.bioscan.fieldterminal.domain.GateView
import com.bioscan.fieldterminal.domain.gateView
import com.bioscan.fieldterminal.domain.showsApp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.bioscan.fieldterminal.data.HealthConnectSyncWorker
import com.bioscan.fieldterminal.data.NotificationActionReceiver
import com.bioscan.fieldterminal.data.NotificationDispatchWorker
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.LocationCollector
import com.bioscan.fieldterminal.data.routine.RoutineCaptureWorker
import com.bioscan.fieldterminal.data.ZeppSyncWorker
import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
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
            // Routine intelligence: both streams stay idle until switched on in Setup; this only keeps the schedule and
            // Play services' registrations alive.
            RoutineCaptureWorker.schedule(context)
            LocationCollector.registerAll(context)
        }
    }

    // Set once the app has been composed in this composition: from then on a transient session state (the reload and
    // refresh supabase-kt runs on every return to the foreground) keeps it on screen instead of replacing it, so the open
    // page, sheets and forms survive the camera, file pickers and time in the background (domain/AuthGateView.kt).
    // Not saveable on purpose: after the process is restored the app waits for the session before composing, and then
    // restores its saved back stack and form state.
    var appShown by remember { mutableStateOf(false) }
    val state = when (val s = sessionStatus) {
        is SessionStatus.Authenticated -> AuthState.AUTHENTICATED
        is SessionStatus.Initializing -> AuthState.INITIALIZING
        is SessionStatus.NotAuthenticated -> AuthState.NOT_AUTHENTICATED
        is SessionStatus.RefreshFailure ->
            if (s.cause is RefreshFailureCause.NetworkError) AuthState.REFRESH_NETWORK_ERROR else AuthState.REFRESH_FAILED
    }
    val view = gateView(state, appShown)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FT.Base),
        contentAlignment = Alignment.Center,
    ) {
        when (view) {
            GateView.APP, GateView.APP_RECONNECTING -> {
                LaunchedEffect(Unit) { appShown = true }
                FieldTerminalNavHost(
                    pendingNotificationKind = pendingNotificationKind,
                    onNotificationHandled = onNotificationHandled,
                )
                if (view == GateView.APP_RECONNECTING) {
                    Text(
                        if (state == AuthState.REFRESH_NETWORK_ERROR) "OFFLINE · RECONNECTING WHEN THE NETWORK IS BACK" else "RECONNECTING…",
                        style = FTType.LabelCaps, color = FT.TextSecondary,
                        modifier = Modifier.align(Alignment.TopCenter).background(FT.Elevated).fillMaxWidth().padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            GateView.SIGN_IN -> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    appShown = false
                    com.bioscan.fieldterminal.data.ReadCache.invalidateAll() // nothing of the last user survives sign-out
                }
                SignInPrompt("SIGN IN WITH GOOGLE", FTButtonEmphasis.Primary, signInError) {
                    scope.launch { signInError = GoogleAuthManager.signIn(context).exceptionOrNull()?.message }
                }
            }
            GateView.LOADING -> CircularProgressIndicator(color = FT.Emerald)
            // supabase-kt keeps retrying the refresh by itself when the cause is the network, so an offline phone with an
            // expired access token is not a lost session: say so instead of demanding a sign-in. Only a real auth failure
            // (revoked or dead refresh token, mirrors the web dashboard's own handling) asks to sign in.
            GateView.OFFLINE -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = FT.Emerald)
                Text("Offline. Reconnecting when the network is back.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            GateView.SESSION_EXPIRED -> {
                LaunchedEffect(Unit) { appShown = false }
                SignInPrompt("SESSION EXPIRED — SIGN IN AGAIN", FTButtonEmphasis.Secondary, signInError) {
                    scope.launch { signInError = GoogleAuthManager.signIn(context).exceptionOrNull()?.message }
                }
            }
        }
    }
}

// The message has a fixed slot under the button, so a failure doesn't push the centred button up.
@Composable
private fun SignInPrompt(label: String, emphasis: FTButtonEmphasis, error: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FTButton(label = label, emphasis = emphasis, onClick = onClick)
        Box(Modifier.widthIn(max = 300.dp).height(52.dp), contentAlignment = Alignment.TopCenter) {
            error?.let { Text(it, style = FTType.Caption, color = FT.Critical, textAlign = TextAlign.Center, maxLines = 3) }
        }
    }
}

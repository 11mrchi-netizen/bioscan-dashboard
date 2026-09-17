package com.bioscan.fieldterminal

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.data.HealthConnectSyncWorker
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.nav.FieldTerminalNavHost
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

// Step 2 (auth) + Step 3 (navigation skeleton) + Step 4 (visual design,
// applied here to the sign-in/loading states -- the 4-tab UI itself is
// styled in FieldTerminalNavHost.kt and its screens). Real per-screen
// content is Phase C onward, per mobile-app-implementation-roadmap.md.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FieldTerminalTheme {
                AuthGate()
            }
        }
    }
}

@Composable
private fun AuthGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionStatus by SupabaseClientProvider.client.auth.sessionStatus.collectAsState()
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
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FieldColors.Ground),
        contentAlignment = Alignment.Center,
    ) {
        when (sessionStatus) {
            is SessionStatus.Authenticated -> FieldTerminalNavHost()
            is SessionStatus.NotAuthenticated -> {
                AmberButton(label = "SIGN IN WITH GOOGLE") {
                    scope.launch { GoogleAuthManager.signIn(context) }
                }
            }
            is SessionStatus.Initializing -> CircularProgressIndicator(color = FieldColors.Amber)
            is SessionStatus.RefreshFailure -> {
                // Mirrors the web dashboard's own handling of a dead
                // refresh token (see ROADMAP.md's Google token-refresh
                // section) -- treat it as needing a fresh sign-in rather
                // than silently retrying forever.
                AmberButton(label = "SESSION EXPIRED — SIGN IN AGAIN") {
                    scope.launch { GoogleAuthManager.signIn(context) }
                }
            }
        }
    }
}

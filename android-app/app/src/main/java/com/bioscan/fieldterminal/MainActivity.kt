package com.bioscan.fieldterminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

// Step 2 scaffold (see mobile-app-implementation-roadmap.md): real Google
// sign-in/sign-out against the same Supabase project the web dashboard uses,
// gating a still-blank post-login screen. The real 4-tab navigation (Step 3)
// and visual design (Step 4, gated on /design/ mockups) are separate,
// deliberately sequenced follow-ups -- not built here.
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (sessionStatus) {
                is SessionStatus.Authenticated -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Intentionally blank beyond this -- Step 3 builds the
                        // real tab structure here.
                        Button(onClick = { scope.launch { GoogleAuthManager.signOut() } }) {
                            Text("Sign out")
                        }
                    }
                }
                is SessionStatus.NotAuthenticated -> {
                    Button(onClick = { scope.launch { GoogleAuthManager.signIn(context) } }) {
                        Text("Sign in with Google")
                    }
                }
                is SessionStatus.Initializing -> CircularProgressIndicator()
                is SessionStatus.RefreshFailure -> {
                    // Mirrors the web dashboard's own handling of a dead
                    // refresh token (see ROADMAP.md's Google token-refresh
                    // section) -- treat it as needing a fresh sign-in rather
                    // than silently retrying forever.
                    Button(onClick = { scope.launch { GoogleAuthManager.signIn(context) } }) {
                        Text("Session expired — sign in again")
                    }
                }
            }
        }
    }
}

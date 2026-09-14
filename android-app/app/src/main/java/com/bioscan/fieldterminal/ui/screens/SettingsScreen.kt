package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Step 15's real scope is deliberately unfilled beyond this (per
// mobile-app-scoping.md: "genuinely unscoped ... expand only when a concrete
// need arises"). Sign-out lives here for real -- not a placeholder like the
// other empty screens, since Step 2 already required a working sign-out.
@Composable
fun SettingsScreen(scope: CoroutineScope) {
    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        ScreenHeader(title = "SETUP", context = "PLACEHOLDER")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = "SETUP — placeholder", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
                AmberButton(label = "SIGN OUT") { scope.launch { GoogleAuthManager.signOut() } }
            }
        }
    }
}

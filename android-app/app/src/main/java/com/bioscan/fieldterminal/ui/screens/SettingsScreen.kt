package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.data.GeminiApiKeyStore
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.Saira
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Step 15's real scope is deliberately unfilled beyond what's here (per
// mobile-app-scoping.md: "genuinely unscoped ... expand only when a concrete
// need arises"). Sign-out has been real since Step 2. The Gemini API key
// field was added for Step 13 (AI food-photo estimation) -- see
// data/GeminiApiKeyStore.kt for why it's stored locally (Android Keystore)
// rather than synced to Supabase.
@Composable
fun SettingsScreen(scope: CoroutineScope) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(GeminiApiKeyStore.get(context) != null) }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        ScreenHeader(title = "SETUP", context = "PLACEHOLDER")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(title = "AI NUTRITION ESTIMATION") {
                Text(
                    "Gemini API key for photo-based calorie/macro estimation on the Food entry form. " +
                        "Stored on this device only (Android Keystore-encrypted) — never synced to Supabase.",
                    style = TextStyle(fontFamily = Saira, fontSize = 13.sp),
                    color = FieldColors.InkMuted,
                )
                FieldTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = if (keySaved) "•••••••• (key saved — paste to replace)" else "Paste your Gemini API key",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton(label = "SAVE KEY") {
                        if (apiKeyInput.isNotBlank()) {
                            GeminiApiKeyStore.save(context, apiKeyInput.trim())
                            keySaved = true
                            apiKeyInput = ""
                        }
                    }
                    if (keySaved) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, FieldColors.Hairline)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    GeminiApiKeyStore.clear(context)
                                    keySaved = false
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "CLEAR",
                                style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                                color = FieldColors.InkMuted,
                            )
                        }
                    }
                }
                Text(
                    if (keySaved) "AI estimation is available on the Food entry form." else "No key set — AI estimation is hidden on the Food entry form until one is saved.",
                    style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                    color = FieldColors.InkMuted,
                )
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AmberButton(label = "SIGN OUT") { scope.launch { GoogleAuthManager.signOut() } }
            }
        }
    }
}

package com.bioscan.fieldterminal.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTerminalTheme
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.Saira

// Phase G1: Health Connect requires an activity answering
// androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE (reachable from Health
// Connect's own permission screen, and from this app's own Play Store
// listing if it's ever published) -- a real platform requirement even for a
// personal, unpublished, sideloaded app. Static -- no data of its own to
// show, just what this app reads Health Connect data for.
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FieldTerminalTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(FieldColors.Ground).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("HEALTH CONNECT DATA USE", style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
                    Text(
                        "Field Terminal reads activity, body measurement, sleep, and vitals records from " +
                            "Health Connect to show them in the Status and Training tabs, and reads/writes " +
                            "hydration and nutrition records to keep them in sync with entries logged in " +
                            "this app's Log tab. Nothing is sent off this device except to this app's own " +
                            "private Supabase project, which only this account can read.",
                        style = TextStyle(fontFamily = Saira, fontSize = 15.sp),
                        color = FieldColors.InkMuted,
                    )
                }
            }
        }
    }
}

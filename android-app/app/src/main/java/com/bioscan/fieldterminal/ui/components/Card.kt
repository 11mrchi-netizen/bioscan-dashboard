package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

// Bordered, titled section card -- the "MACROS"/"HYDRATION"-style container
// first built for Step 6, promoted to a shared component once Step 7 wanted
// the identical shape (a titled bar + padded content, squared, no radius).
@Composable
fun Card(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FieldColors.Hairline)
            .background(FieldColors.RaisedSurface),
    ) {
        Text(
            text = title,
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.Amber,
            modifier = Modifier
                .fillMaxWidth()
                .background(FieldColors.Hairline)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            content()
        }
    }
}

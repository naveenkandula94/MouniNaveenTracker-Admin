package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Concise, high-contrast data tiles with one primary metric each. */
@Composable
internal fun AdminMetricTile(
    title: String, value: String, fill: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = fill)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Color(0xFF15243C))
            Text(title, style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF445469))
        }
    }
}

@Composable
internal fun AdminStatusDot(isRecent: Boolean, hasEverSynced: Boolean = true) {
    val color = if (isRecent) Color(0xFF17836C) else Color(0xFFAF7937)
    Text(
        when {
            !hasEverSynced -> "● No successful Firebase sync yet"
            isRecent -> "● Checked recently"
            else -> "● Last check older"
        },
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}

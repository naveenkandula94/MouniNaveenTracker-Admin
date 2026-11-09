package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * No SMS content is accessed from Admin_App or Personal_App.
 * Android SMS access is reserved for eligible default handlers or approved use cases.
 * Show a clear absence rather than a misleading zero count or fake data feed.
 */
@Composable
fun MessagesScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Messages", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Not connected", style = MaterialTheme.typography.titleLarge)
                Text("SMS and message contents are not collected by Personal_App and are not present in this dashboard.")
                Text(
                    "Android and Google Play restrict READ_SMS to eligible default SMS/Assistant handlers and narrowly approved uses. " +
                    "Admin_App is a cloud viewer, not a messaging handler.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "No WhatsApp, Instagram, or other app messages are collected. No message permissions will be requested by Admin_App.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

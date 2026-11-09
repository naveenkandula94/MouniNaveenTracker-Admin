package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Summaries describe actual stored Firebase documents.
 * Automatic owner backups are never counted as previously shared device calls.
 */
@Composable
internal fun AdminOverview(
    devices: List<Pair<String, String>>,
    onInfo: (String) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    var backedUp by remember(uid) { mutableStateOf<Long?>(null) }
    var sharedCalls by remember(uid) { mutableStateOf<Long?>(null) }
    var locks by remember(uid) { mutableStateOf<Long?>(null) }
    var sessions by remember(uid) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(uid, devices, refresh) {
        if (uid.isBlank()) return@LaunchedEffect
        busy = true
        try {
            // Firestore count aggregations return total data, not just the first 50 rows.
            backedUp = db.collection("users").document(uid).collection("callBackups")
                .count().get(AggregateSource.SERVER).await().count
            var sharedTotal = 0L
            var lockTotal = 0L
            var sessionTotal = 0L
            devices.forEach { (deviceId, _) ->
                val root = db.collection("devices").document(deviceId)
                sharedTotal += root.collection("callLogs").count()
                    .get(AggregateSource.SERVER).await().count
                lockTotal += root.collection("usageEvents").count()
                    .get(AggregateSource.SERVER).await().count
                sessionTotal += root.collection("liveAudio").count()
                    .get(AggregateSource.SERVER).await().count
            }
            sharedCalls = sharedTotal
            locks = lockTotal
            sessions = sessionTotal
        } catch (e: Exception) {
            onInfo("Dashboard refresh failed: ${e.localizedMessage}")
        } finally { busy = false }
    }

    Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Your account and linked devices", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardMetric("My backups", backedUp, Modifier.weight(1f))
        DashboardMetric("Shared calls", sharedCalls, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardMetric("Lock events", locks, Modifier.weight(1f))
        DashboardMetric("Audio sessions", sessions, Modifier.weight(1f))
    }
    Text("Backups are stored under your own Firebase account. Shared calls are a separate collection, so their counts can differ.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !busy, onClick = { refresh++ }) {
        Text(if (busy) "Refreshing…" else "Refresh dashboard")
    }
}

@Composable
private fun DashboardMetric(name: String, value: Long?, modifier: Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (value == null) "—" else value.toString(),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

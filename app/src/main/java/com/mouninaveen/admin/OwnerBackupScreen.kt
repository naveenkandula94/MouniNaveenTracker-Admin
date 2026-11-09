package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class BackupCall(
    val id: String, val number: String, val direction: String,
    val time: Long, val durationSeconds: Int
)

/**
 * Read/manage cloud documents CREATED BY Personal_App, not by Admin_App.\n * Firestore requires request.auth.uid == uid for owner-private call history.\n * Admin_App never reads Android CallLog or schedules a phone backup.
 */
@Composable
fun OwnerBackupScreen(deviceId: String?, deviceName: String, legacy: Boolean = false, onStatus: (String) -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val uid = auth.currentUser?.uid
    var records by remember(uid, deviceId, legacy) { mutableStateOf(emptyList<BackupCall>()) }
    var selected by remember(uid, deviceId, legacy) { mutableStateOf(emptySet<String>()) }
    var lastDocument by remember(uid, deviceId, legacy) { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMore by remember(uid, deviceId, legacy) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("All") }
    var startInput by remember { mutableStateOf("") }
    var endInput by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    fun dateAtMidnight(value: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        formatter.isLenient = false
        check(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            "Use date format YYYY-MM-DD."
        }
        return requireNotNull(formatter.parse(value)).time
    }

    fun bounds(): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000
        return when (range) {
            "Today" -> {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                calendar.timeInMillis to null
            }
            "7 days" -> (now - 7L * oneDay) to null
            "30 days" -> (now - 30L * oneDay) to null
            "Custom" -> {
                val start = if (startInput.isBlank()) null else dateAtMidnight(startInput.trim())
                val end = if (endInput.isBlank()) null else dateAtMidnight(endInput.trim()) + oneDay - 1
                check(start == null || end == null || start <= end) { "Start date must be before end date." }
                start to end
            }
            else -> null to null
        }
    }

    fun buildQuery(): Query {
        val owner = uid ?: error("Sign in first.")
        val root = db.collection("users").document(owner)
        var query: Query = if (legacy) {
            root.collection("callBackups")
        } else {
            val id = deviceId ?: error("Choose a device.")
            root.collection("deviceCallBackups").document(id).collection("calls")
        }
        val (start, end) = bounds()
        if (start != null) query = query.whereGreaterThanOrEqualTo("callTimestamp", Timestamp(Date(start)))
        if (end != null) query = query.whereLessThanOrEqualTo("callTimestamp", Timestamp(Date(end)))
        return query.orderBy("callTimestamp", Query.Direction.DESCENDING)
    }

    fun load(more: Boolean = false) {
        if (loading || uid == null) return
        loading = true
        scope.launch {
            try {
                var query = buildQuery().limit(100)
                if (more && lastDocument != null) query = query.startAfter(lastDocument!!)
                val result = query.get().await()
                val next = result.documents.map { d ->
                    BackupCall(
                        d.id, d.getString("number") ?: "Unknown",
                        d.getString("direction") ?: "UNKNOWN",
                        d.getTimestamp("callTimestamp")?.toDate()?.time ?: 0L,
                        d.getLong("durationSeconds")?.toInt() ?: 0
                    )
                }
                records = if (more) records + next else next
                if (!more) selected = emptySet()
                lastDocument = result.documents.lastOrNull()
                hasMore = result.size() == 100
                onStatus("Displaying ${records.size} cloud records uploaded by Personal_App.")
            } catch (e: Exception) {
                onStatus("Private call backup query failed: ${e.localizedMessage}")
            } finally { loading = false }
        }
    }

    LaunchedEffect(uid, deviceId, legacy, range, refreshKey) {
        records = emptyList()
        lastDocument = null
        selected = emptySet()
        if (uid != null && (legacy || deviceId != null) && range != "Custom") load()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (legacy) "Earlier Personal_App records" else "$deviceName • Call history",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            if (legacy) "Viewing older Firebase records uploaded by Personal_App before individual phones were tracked. Their original phone cannot be identified reliably."
            else "View only: Personal_App on $deviceName uploads the call history. Admin_App does not read or back up calls from this phone.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Privacy: Firebase permits private records to be viewed only when this app is signed in with the same owner UID. Another Admin account cannot access a different owner's private call history.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All","Today","7 days","30 days","Custom").forEach { option ->
                FilterChip(
                    selected = range == option,
                    onClick = { range = option; confirm = "" },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (range == "Custom") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startInput, onValueChange = { startInput = it.take(10) },
                    label = { Text("From YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endInput, onValueChange = { endInput = it.take(10) },
                    label = { Text("To YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading && uid != null, onClick = { load() }) { Text(if (loading) "Loading…" else "Refresh / Apply") }
            Text("${records.size} loaded", modifier = Modifier.weight(1f))
        }

        if (records.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    selected = if (selected.size == records.size) emptySet() else records.map { it.id }.toSet()
                }) { Text(if (selected.size == records.size) "Clear selection" else "Select all loaded") }
                OutlinedButton(onClick = { confirm = "filtered" }) { Text("Delete in date range") }
            }
            if (selected.isNotEmpty()) {
                Button(
                    onClick = { confirm = "selected" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete selected (${selected.size})") }
            }
        }

        if (confirm.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (confirm == "selected") "Delete ${selected.size} selected backups?"
                        else "Delete ALL private backups matching the selected date range?",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("Permanent Firebase deletion. This does not delete calls from the phone, and a future backup may restore them if backup is enabled.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = "" }) { Text("Cancel") }
                        Button(
                            enabled = !loading,
                            onClick = {
                                val mode = confirm
                                val chosen = selected.toList()
                                confirm = ""
                                loading = true
                                scope.launch {
                                    try {
                                        val owner = auth.currentUser?.uid ?: error("Signed out.")
                                        check(owner == uid) { "Firebase account changed." }
                                        val root = db.collection("users").document(owner)
                                        val collection = if (legacy) {
                                            root.collection("callBackups")
                                        } else {
                                            val id = deviceId ?: error("Choose a phone before deleting.")
                                            root.collection("deviceCallBackups").document(id).collection("calls")
                                        }
                                        var total = 0
                                        if (mode == "selected") {
                                            chosen.chunked(200).forEach { part ->
                                                val batch = db.batch()
                                                part.forEach { batch.delete(collection.document(it)) }
                                                batch.commit().await()
                                                total += part.size
                                            }
                                        } else {
                                            while (true) {
                                                val page = buildQuery().limit(200).get().await().documents
                                                if (page.isEmpty()) break
                                                val batch = db.batch()
                                                page.forEach { batch.delete(it.reference) }
                                                batch.commit().await()
                                                total += page.size
                                            }
                                        }
                                        onStatus("Deleted $total private backup records in Firebase.")
                                        refreshKey++
                                    } catch (e: Exception) {
                                        onStatus("Backup deletion failed: ${e.localizedMessage}")
                                    } finally { loading = false }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Confirm delete") }
                    }
                }
            }
        }

        if (records.isEmpty()) Text(
            if (!legacy && deviceId == null)
                "No phone selected. Add a device in Admin_App Settings, then choose that device above. Older Personal_App uploads remain under Earlier records."
            else "No Personal_App records for this phone and date filter yet. This Admin app cannot start a backup; check Personal_App on the selected phone."
        )
        records.forEach { call ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Checkbox(
                        checked = call.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + call.id else selected - call.id
                        }
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${call.direction} · ${call.number}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${if (call.time > 0) SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(call.time)) else "Unknown date"} · ${call.durationSeconds}s",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        if (hasMore) OutlinedButton(enabled = !loading, onClick = { load(more = true) }) { Text("Load next 100") }
    }
}

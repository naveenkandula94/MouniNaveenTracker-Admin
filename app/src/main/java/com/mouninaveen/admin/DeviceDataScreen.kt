package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class ActivityRecord(
    val id: String, val kind: String, val time: Long, val description: String
)

@Composable
fun DeviceDataScreen(
    deviceId: String,
    initialKind: String = "Lock/unlock",
    fixedKind: Boolean = false,
    onStatus: (String) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    var kind by remember(deviceId, initialKind) { mutableStateOf(initialKind) }
    var range by remember { mutableStateOf("7 days") }
    var records by remember(deviceId, kind) { mutableStateOf(emptyList<ActivityRecord>()) }
    var selected by remember(deviceId, kind) { mutableStateOf(emptySet<String>()) }
    var lastDoc by remember(deviceId, kind) { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMore by remember(deviceId, kind) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    val collectionName = when (kind) {
        "Lock/unlock" -> "usageEvents"
        "Audio sessions" -> "liveAudio"
        else -> "syncLogs"
    }
    val timeField = when (kind) {
        "Lock/unlock" -> "eventTimestamp"
        "Audio sessions" -> "createdAt"
        else -> "timestamp"
    }

    fun query(): Query {
        var query: Query = db.collection("devices").document(deviceId).collection(collectionName)
        val now = System.currentTimeMillis()
        val from = when (range) {
            "Today" -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0)
                set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
            }.timeInMillis
            "7 days" -> now - 7L * 86400000L
            "30 days" -> now - 30L * 86400000L
            else -> 0L
        }
        if (from > 0L) query = query.whereGreaterThanOrEqualTo(timeField, Timestamp(Date(from)))
        return query.orderBy(timeField, Query.Direction.DESCENDING)
    }

    fun reload(more: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val uid = auth.currentUser?.uid ?: error("Sign in first.")
                val device = db.collection("devices").document(deviceId).get().await()
                check(device.getString("adminUid") == uid) { "Access denied: account does not own this device." }
                var request = query().limit(100)
                if (more && lastDoc != null) request = request.startAfter(lastDoc!!)
                val docs = request.get().await().documents
                val parsed = docs.map { d ->
                    ActivityRecord(d.id,
                        if (kind == "Lock/unlock") d.getString("eventType") ?: "EVENT"
                        else if (kind == "Audio sessions") d.getString("status") ?: "SESSION"
                        else d.getString("eventType") ?: "SYNC",
                        d.getTimestamp(timeField)?.toDate()?.time ?: 0L,
                        when (kind) {
                            "Lock/unlock" -> d.getString("applicationLabel") ?: "Screen state"
                            "Audio sessions" -> "Microphone connection metadata only (no recording)"
                            else -> "${d.getString("status") ?: "UNKNOWN"} · ${d.getLong("count") ?: 0L} new calls in backup"
                        }
                    )
                }
                records = if (more) records + parsed else parsed
                if (!more) selected = emptySet()
                lastDoc = docs.lastOrNull()
                hasMore = docs.size == 100
                onStatus("Loaded ${records.size} $kind records.")
            } catch (e: Exception) {
                onStatus("Device record query failed: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }

    LaunchedEffect(deviceId, kind, range, refresh) {
        records = emptyList()
        selected = emptySet()
        lastDoc = null
        confirm = ""
        reload()
    }

    // Firestore live subscription refreshes the newest records when a phone uploads.
    // It is removed automatically when the tab closes or the device changes.
    DisposableEffect(deviceId, kind, range) {
        val subscription = query().limit(1).addSnapshotListener { _, error ->
            if (error != null) onStatus("Live event updates unavailable: ${error.localizedMessage}")
            else reload()
        }
        onDispose { subscription.remove() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when (kind) {
                "Sync logs" -> "Sync activity · newest first"
                "Lock/unlock" -> "Screen lock history · newest first"
                else -> "Audio sessions · newest first"
            },
            style = MaterialTheme.typography.titleLarge
        )
        Text("View and manage device event metadata in Firebase.", style = MaterialTheme.typography.bodySmall)
        if (!fixedKind) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Lock/unlock", "Audio sessions", "Sync logs").forEach { value ->
                    FilterChip(
                        selected = kind == value,
                        onClick = { kind = value },
                        label = { Text(value) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Today","7 days","30 days","All").forEach { value ->
                FilterChip(selected = range == value, onClick = { range = value }, label = { Text(value) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { reload() }) { Text(if (busy) "Loading…" else "Refresh") }
            Text("${records.size} loaded", modifier = Modifier.weight(1f))
        }
        if (records.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    selected = if (selected.size == records.size) emptySet() else records.map { it.id }.toSet()
                }) { Text(if (selected.size == records.size) "Clear selection" else "Select all loaded") }
                OutlinedButton(onClick = { confirm = "all" }) { Text("Delete all in range") }
            }
            if (selected.isNotEmpty()) {
                Button(
                    onClick = { confirm = "selected" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete selected (${selected.size})") }
            }
        }
        if (confirm.isNotBlank()) {
            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (confirm == "selected") "Delete ${selected.size} chosen $kind records?"
                        else "Delete all $kind records in the selected date range?",
                        color = MaterialTheme.colorScheme.error
                    )
                    if (kind == "Audio sessions") {
                        Text("Active audio sessions cannot be deleted. End them on Collector first. Audio recordings are not stored.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = "" }) { Text("Cancel") }
                        Button(enabled = !busy, onClick = {
                            val mode = confirm
                            val chosen = selected.toList()
                            confirm = ""
                            busy = true
                            scope.launch {
                                try {
                                    val uid = auth.currentUser?.uid ?: error("Sign in first.")
                                    val device = db.collection("devices").document(deviceId).get().await()
                                    check(device.getString("adminUid") == uid) { "Device ownership changed." }
                                    val coll = device.reference.collection(collectionName)
                                    var count = 0
                                    suspend fun deleteDocument(doc: DocumentSnapshot) {
                                        if (kind == "Audio sessions") {
                                            check(doc.getString("status") == "ENDED") {
                                                "An active/unended session cannot be deleted: ${doc.id}"
                                            }
                                            for (sub in listOf("publisherIce", "listenerIce")) {
                                                while (true) {
                                                    val page = doc.reference.collection(sub).limit(200).get().await().documents
                                                    if (page.isEmpty()) break
                                                    val batch = db.batch()
                                                    page.forEach { batch.delete(it.reference) }
                                                    batch.commit().await()
                                                }
                                            }
                                        }
                                        doc.reference.delete().await()
                                        count++
                                    }
                                    if (mode == "selected") {
                                        for (id in chosen) {
                                            val doc = coll.document(id).get().await()
                                            if (doc.exists()) deleteDocument(doc)
                                        }
                                    } else {
                                        // Iterate matching documents until none remain. Sessions must be
                                        // stopped before deleting, so skip live sessions.
                                        while (true) {
                                            val page = query().limit(100).get().await().documents
                                            val eligible = if (kind == "Audio sessions") {
                                                page.filter { it.getString("status") == "ENDED" }
                                            } else page
                                            if (eligible.isEmpty()) break
                                            eligible.forEach { deleteDocument(it) }
                                            if (kind == "Audio sessions" && eligible.size < page.size) break
                                        }
                                    }
                                    onStatus("Deleted $count $kind records from Firebase.")
                                    refresh++
                                } catch (e: Exception) {
                                    onStatus("Deletion stopped: ${e.localizedMessage}")
                                } finally { busy = false }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("Confirm delete")
                        }
                    }
                }
            }
        }
        if (records.isEmpty()) Text("No records for this device and time range.")
        records.forEach { record ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp)) {
                    Checkbox(
                        checked = record.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + record.id else selected - record.id
                        }
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(record.kind, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (record.time == 0L) "Unknown date" else
                                SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(record.time)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(record.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (hasMore) OutlinedButton(enabled = !busy, onClick = { reload(true) }) { Text("Load next 100") }
    }
}

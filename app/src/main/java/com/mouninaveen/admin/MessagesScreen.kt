package com.mouninaveen.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private data class StoredSms(
    val id: String, val address: String, val body: String,
    val direction: String, val time: Long
)

/**
 * Read-only as to the Android device; Admin only displays Personal_App's
 * explicit, owner-private Firestore SMS backup. UID ownership is enforced
 * again by Firestore rules. This app never requests Android READ_SMS.
 */
@Composable
fun MessagesScreen(deviceId: String?, deviceName: String, onStatus: (String) -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid
    val scope = rememberCoroutineScope()
    var range by remember(deviceId) { mutableStateOf("All") }
    var fromInput by remember(deviceId) { mutableStateOf("") }
    var untilInput by remember(deviceId) { mutableStateOf("") }
    var records by remember(deviceId, uid) { mutableStateOf(emptyList<StoredSms>()) }
    var chosen by remember(deviceId, uid) { mutableStateOf(emptySet<String>()) }
    var lastDoc by remember(deviceId, uid) { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMore by remember(deviceId, uid) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }

    fun dateMillis(input: String): Long {
        check(input.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            "Enter date as YYYY-MM-DD."
        }
        return requireNotNull(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
        }.parse(input)).time
    }
    fun filteredQuery(): Query {
        val owner = uid ?: error("Sign in to your Firebase account.")
        val phone = deviceId ?: error("Select a linked phone.")
        var request: Query = db.collection("users").document(owner)
            .collection("deviceSmsBackups").document(phone).collection("messages")
        val now = System.currentTimeMillis()
        val lower = when (range) {
            "Today" -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            "7 days" -> now - 7L * 86_400_000L
            "30 days" -> now - 30L * 86_400_000L
            "Custom" -> if (fromInput.isBlank()) null else dateMillis(fromInput.trim())
            else -> null
        }
        val upper = if (range == "Custom" && untilInput.isNotBlank()) {
            dateMillis(untilInput.trim()) + 86_400_000L - 1
        } else null
        check(lower == null || upper == null || lower <= upper) {
            "Start date must not be after end date."
        }
        if (lower != null) request = request.whereGreaterThanOrEqualTo(
            "receivedAt", Timestamp(Date(lower))
        )
        if (upper != null) request = request.whereLessThanOrEqualTo(
            "receivedAt", Timestamp(Date(upper))
        )
        return request.orderBy("receivedAt", Query.Direction.DESCENDING)
    }
    fun load(more: Boolean = false) {
        if (uid == null || deviceId == null || busy) return
        busy = true
        scope.launch {
            try {
                var query = filteredQuery().limit(100)
                if (more && lastDoc != null) query = query.startAfter(lastDoc!!)
                val docs = query.get().await().documents
                val page = docs.map { d ->
                    StoredSms(
                        d.id,
                        d.getString("address") ?: "Unknown",
                        d.getString("body") ?: "",
                        d.getString("direction") ?: "RECEIVED",
                        d.getTimestamp("receivedAt")?.toDate()?.time ?: 0L
                    )
                }
                records = if (more) records + page else page
                if (!more) chosen = emptySet()
                lastDoc = docs.lastOrNull()
                hasMore = docs.size == 100
                onStatus("Messages: loaded ${records.size} Personal_App cloud records.")
            } catch (e: Exception) {
                onStatus("Messages cannot load: ${e.localizedMessage}")
            } finally { busy = false }
        }
    }

    LaunchedEffect(uid, deviceId, range, version) {
        records = emptyList()
        chosen = emptySet()
        lastDoc = null
        hasMore = false
        if (range != "Custom") load()
    }

    DisposableEffect(uid, deviceId, range) {
        if (uid == null || deviceId == null || range == "Custom") onDispose { }
        else {
            // Watch only the newest message to avoid holding a large listener.
            val watcher = filteredQuery().limit(1).addSnapshotListener { snapshot, error ->
                if (error != null) onStatus("Message live updates: ${error.localizedMessage}")
                else if (snapshot != null && !busy && records.size <= 100) load()
            }
            onDispose { watcher.remove() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Messages", style = MaterialTheme.typography.headlineSmall)
        Text("Selected phone: $deviceName", style = MaterialTheme.typography.titleMedium)
        Text(
            "Messages shown here are sent to the owner's private Firebase backup only " +
                "after Personal_App is explicitly enabled and Android grants READ_SMS. " +
                "Admin_App cannot read SMS from this phone.",
            style = MaterialTheme.typography.bodySmall
        )

        if (deviceId == null) {
            Text("Select a linked phone above. No SMS messages have been requested from this Admin phone.")
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Today", "7 days", "30 days", "Custom").forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { range = option; deleteMode = "" },
                        label = { Text(option) }
                    )
                }
            }
            if (range == "Custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fromInput, onValueChange = { fromInput = it.take(10) },
                        modifier = Modifier.weight(1f),
                        label = { Text("From YYYY-MM-DD") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = untilInput, onValueChange = { untilInput = it.take(10) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Until YYYY-MM-DD") }, singleLine = true
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { load() }) {
                    Text(if (busy) "Loading…" else "Refresh / Apply")
                }
                Text("${records.size} loaded", modifier = Modifier.weight(1f))
            }
            if (records.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        chosen = if (chosen.size == records.size) emptySet()
                        else records.map { it.id }.toSet()
                    }) {
                        Text(if (chosen.size == records.size) "Clear selection" else "Select all loaded")
                    }
                    OutlinedButton(onClick = { deleteMode = "range" }) {
                        Text("Delete date range")
                    }
                }
                if (chosen.isNotEmpty()) {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        onClick = { deleteMode = "selected" }
                    ) { Text("Delete selected (${chosen.size})") }
                }
            }
            if (deleteMode.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (deleteMode == "selected")
                                "Delete ${chosen.size} selected cloud SMS records?"
                            else "Delete all cloud SMS records in this date range?",
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Permanent Firebase deletion; messages on the phone are not removed. " +
                                "Personal_App may back them up again if still enabled.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { deleteMode = "" }) { Text("Cancel") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val mode = deleteMode
                                    val selectedIds = chosen.toList()
                                    deleteMode = ""
                                    busy = true
                                    scope.launch {
                                        try {
                                            val current = auth.currentUser?.uid
                                                ?: error("Not signed in.")
                                            check(current == uid) { "Account changed." }
                                            val phone = deviceId ?: error("Choose phone.")
                                            val coll = db.collection("users").document(current)
                                                .collection("deviceSmsBackups").document(phone)
                                                .collection("messages")
                                            var removed = 0
                                            if (mode == "selected") {
                                                selectedIds.chunked(200).forEach { list ->
                                                    val batch = db.batch()
                                                    list.forEach { batch.delete(coll.document(it)) }
                                                    batch.commit().await()
                                                    removed += list.size
                                                }
                                            } else {
                                                while (true) {
                                                    val docs = filteredQuery().limit(200).get().await().documents
                                                    if (docs.isEmpty()) break
                                                    val batch = db.batch()
                                                    docs.forEach { batch.delete(it.reference) }
                                                    batch.commit().await()
                                                    removed += docs.size
                                                }
                                            }
                                            onStatus("Deleted $removed owner-private SMS backups.")
                                            version++
                                        } catch (e: Exception) {
                                            onStatus("SMS deletion failed: ${e.localizedMessage}")
                                        } finally { busy = false }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Confirm delete") }
                        }
                    }
                }
            }
            if (records.isEmpty()) {
                Text(
                    "No SMS backups found for this phone and filter. Check Personal_App > " +
                        "Messages for permission, backup status, and enrollment."
                )
            }
            records.forEach { sms ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Checkbox(checked = sms.id in chosen, onCheckedChange = { checked ->
                            chosen = if (checked) chosen + sms.id else chosen - sms.id
                        })
                        Column(Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${sms.direction} · ${sms.address}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (sms.time > 0) SimpleDateFormat(
                                    "dd MMM yyyy HH:mm:ss", Locale.getDefault()
                                ).format(Date(sms.time)) else "Unknown timestamp",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(sms.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (hasMore) OutlinedButton(
                enabled = !busy, onClick = { load(more = true) }
            ) { Text("Load next 100") }
        }
    }
}

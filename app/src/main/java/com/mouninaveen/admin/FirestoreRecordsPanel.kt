package com.mouninaveen.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The private backup is available only to the account that created it.
 * Unlike devices/{id}/callLogs, users/{uid}/callBackups is NOT a cross-account feed.
 */
internal enum class RecordKind { MY_BACKUPS, SHARED_CALLS, LOCK_EVENTS, AUDIO_SESSIONS }

private const val PAGE_SIZE = 50L

private fun startOfDay(daysAgo: Int): Long {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
    return calendar.timeInMillis
}

private fun parseDay(input: String): Long {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    parser.isLenient = false
    return requireNotNull(parser.parse(input.trim())) { "Enter a valid date, e.g. 2026-11-09" }.time
}

private fun dateText(millis: Long): String =
    if (millis <= 0L) "Unknown date" else
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(millis))

private fun collectionFor(kind: RecordKind, uid: String, deviceId: String?): CollectionReference {
    val firestore = FirebaseFirestore.getInstance()
    return when (kind) {
        RecordKind.MY_BACKUPS -> firestore.collection("users").document(uid).collection("callBackups")
        RecordKind.SHARED_CALLS -> firestore.collection("devices").document(requireNotNull(deviceId)).collection("callLogs")
        RecordKind.LOCK_EVENTS -> firestore.collection("devices").document(requireNotNull(deviceId)).collection("usageEvents")
        RecordKind.AUDIO_SESSIONS -> firestore.collection("devices").document(requireNotNull(deviceId)).collection("liveAudio")
    }
}

private fun dateField(kind: RecordKind): String = when (kind) {
    RecordKind.MY_BACKUPS, RecordKind.SHARED_CALLS -> "callTimestamp"
    RecordKind.LOCK_EVENTS -> "eventTimestamp"
    RecordKind.AUDIO_SESSIONS -> "createdAt"
}

private fun filteredQuery(
    collection: CollectionReference, kind: RecordKind,
    fromMs: Long?, toExclusiveMs: Long?
): Query {
    val field = dateField(kind)
    var query: Query = collection
    if (fromMs != null) query = query.whereGreaterThanOrEqualTo(field, Date(fromMs))
    if (toExclusiveMs != null) query = query.whereLessThan(field, Date(toExclusiveMs))
    return query.orderBy(field, Query.Direction.DESCENDING)
}

/**
 * Firestore does not cascade delete subcollections. Purge signaling candidates
 * first, then the parent session document. Nothing here touches microphone audio.
 */
private suspend fun deleteAudioSession(reference: DocumentReference) {
    val firestore = FirebaseFirestore.getInstance()
    for (child in listOf("publisherIce", "listenerIce")) {
        while (true) {
            val docs = reference.collection(child).limit(200).get().await().documents
            if (docs.isEmpty()) break
            val batch = firestore.batch()
            docs.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
    reference.delete().await()
}

@Composable
internal fun RecordsPanel(
    kind: RecordKind,
    deviceId: String?,
    onInfo: (String) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val uid = auth.currentUser?.uid.orEmpty()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var period by remember(kind, deviceId) { mutableIntStateOf(0) }
    var startText by remember(kind, deviceId) { mutableStateOf("") }
    var endText by remember(kind, deviceId) { mutableStateOf("") }
    var customFrom by remember(kind, deviceId) { mutableStateOf<Long?>(null) }
    var customTo by remember(kind, deviceId) { mutableStateOf<Long?>(null) }
    var dateError by remember(kind, deviceId) { mutableStateOf("") }
    var firstPage by remember(kind, deviceId) { mutableStateOf(emptyList<DocumentSnapshot>()) }
    var olderPages by remember(kind, deviceId) { mutableStateOf(emptyList<DocumentSnapshot>()) }
    var lastDocument by remember(kind, deviceId) { mutableStateOf<DocumentSnapshot?>(null) }
    var moreAvailable by remember(kind, deviceId) { mutableStateOf(false) }
    var totalMatching by remember(kind, deviceId) { mutableStateOf<Long?>(null) }
    var selectedIds by remember(kind, deviceId) { mutableStateOf(emptySet<String>()) }
    var deleteMode by remember(kind, deviceId) { mutableStateOf("") }
    var busy by remember(kind, deviceId) { mutableStateOf(false) }
    var status by remember(kind, deviceId) { mutableStateOf("Connecting to Firebase…") }
    var refreshToken by remember(kind, deviceId) { mutableIntStateOf(0) }

    val fromMs = when (period) {
        1 -> startOfDay(0)
        2 -> startOfDay(6)
        3 -> startOfDay(29)
        4 -> customFrom
        else -> null
    }
    val toMs = if (period == 4) customTo else null
    val valid = uid.isNotBlank() &&
        (kind == RecordKind.MY_BACKUPS || deviceId != null) &&
        (period != 4 || (customFrom != null && customTo != null))

    val target = remember(uid, kind, deviceId) {
        if (uid.isBlank() || (kind != RecordKind.MY_BACKUPS && deviceId == null)) null
        else collectionFor(kind, uid, deviceId)
    }
    val query = remember(target, kind, fromMs, toMs, period, valid) {
        if (target == null || !valid) null else filteredQuery(target, kind, fromMs, toMs)
    }
    val documents = (firstPage + olderPages).distinctBy { it.id }
        .sortedByDescending { it.getTimestamp(dateField(kind))?.toDate()?.time ?: 0L }

    DisposableEffect(query, refreshToken) {
        if (query == null) {
            status = "Choose an account, device and valid date range."
            onDispose { }
        } else {
            firstPage = emptyList()
            olderPages = emptyList()
            selectedIds = emptySet()
            lastDocument = null
            moreAvailable = false
            totalMatching = null
            deleteMode = ""
            status = "Loading records…"
            val registration: ListenerRegistration = query.limit(PAGE_SIZE)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status = "Firebase query failed: ${error.localizedMessage}"
                        onInfo(status)
                    } else if (snapshot != null) {
                        firstPage = snapshot.documents
                        if (olderPages.isEmpty()) lastDocument = snapshot.documents.lastOrNull()
                        moreAvailable = snapshot.documents.size >= PAGE_SIZE.toInt() || olderPages.isNotEmpty()
                        status = "Updated from Firebase."
                    }
                }
            onDispose { registration.remove() }
        }
    }

    LaunchedEffect(query, refreshToken) {
        if (query != null) {
            try { totalMatching = query.count().get(AggregateSource.SERVER).await().count }
            catch (e: Exception) {
                onInfo("Unable to count records: ${e.localizedMessage}")
            }
        }
    }

    fun reload() {
        refreshToken++
    }

    val title = when (kind) {
        RecordKind.MY_BACKUPS -> "My automatic call backups"
        RecordKind.SHARED_CALLS -> "Shared call records"
        RecordKind.LOCK_EVENTS -> "Lock & unlock events"
        RecordKind.AUDIO_SESSIONS -> "Live audio sessions"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(when (kind) {
            RecordKind.MY_BACKUPS -> "Private records saved by Collector in users / your UID / callBackups. Visible only to this signed-in Firebase account."
            RecordKind.SHARED_CALLS -> "Previously approved records stored under the selected device."
            RecordKind.LOCK_EVENTS -> "Device screen lock and unlock timestamps."
            RecordKind.AUDIO_SESSIONS -> "Only connection status and timestamps are shown; audio and signaling content are not displayed."
        }, style = MaterialTheme.typography.bodySmall)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Today", "7 days", "30 days", "Dates").forEachIndexed { i, label ->
                FilterChip(selected = period == i, onClick = {
                    period = i
                    dateError = ""
                    selectedIds = emptySet()
                }, label = { Text(label) })
            }
        }

        if (period == 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText, onValueChange = { startText = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("From") }, placeholder = { Text("YYYY-MM-DD") }
                )
                OutlinedTextField(
                    value = endText, onValueChange = { endText = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("Through") }, placeholder = { Text("YYYY-MM-DD") }
                )
            }
            Button(onClick = {
                try {
                    val first = parseDay(startText)
                    val last = parseDay(endText)
                    check(first <= last) { "From date must not be after Through date." }
                    val cal = Calendar.getInstance().apply { timeInMillis = last; add(Calendar.DAY_OF_YEAR, 1) }
                    customFrom = first
                    customTo = cal.timeInMillis
                    dateError = ""
                    selectedIds = emptySet()
                    reload()
                } catch (e: Exception) {
                    dateError = e.localizedMessage ?: "Invalid date"
                }
            }) { Text("Apply dates") }
            if (dateError.isNotBlank()) Text(dateError, color = MaterialTheme.colorScheme.error)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${totalMatching?.toString() ?: "…"} matching records",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${documents.size} loaded", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(enabled = !busy && valid, onClick = { reload() }) { Text("Refresh") }
        }

        if (documents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    selectedIds = if (documents.all { it.id in selectedIds }) emptySet()
                    else documents.map { it.id }.toSet()
                }) { Text(if (documents.all { it.id in selectedIds }) "Clear selection" else "Select loaded") }
                if (selectedIds.isNotEmpty()) {
                    Button(enabled = !busy, onClick = { deleteMode = "selected" }) {
                        Text("Delete selected (${selectedIds.size})")
                    }
                }
                OutlinedButton(enabled = !busy, onClick = { deleteMode = "filtered" }) {
                    Text("Delete all matching")
                }
            }
        }

        if (deleteMode.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val countDescription = if (deleteMode == "filtered")
                        "all ${totalMatching ?: "matching"} records in this date range"
                    else "${selectedIds.size} selected records"
                    Text("Permanently delete $countDescription from Firebase?",
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    if (kind == RecordKind.MY_BACKUPS) {
                        Text("This does not remove calls from the phone. If backup remains enabled, still-present calls may be backed up again. Disable backup on Collector first to keep deleted data removed.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (kind == RecordKind.AUDIO_SESSIONS) {
                        Text("Deleting an active signaling session may disconnect its current listener. Connection candidates are also permanently deleted.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { deleteMode = "" }) { Text("Cancel") }
                        Button(enabled = !busy, onClick = {
                            val mode = deleteMode
                            val ids = selectedIds.toList()
                            deleteMode = ""
                            val actualUid = auth.currentUser?.uid
                            if (actualUid.isNullOrBlank() || actualUid != uid) {
                                status = "Signed-in account changed. Reload records."
                                return@Button
                            }
                            val ref = target ?: return@Button
                            val q = query ?: return@Button
                            busy = true
                            scope.launch {
                                try {
                                    var deleted = 0
                                    if (kind == RecordKind.AUDIO_SESSIONS) {
                                        if (mode == "selected") {
                                            for (id in ids) {
                                                check(auth.currentUser?.uid == uid) { "Account changed; deletion cancelled." }
                                                deleteAudioSession(ref.document(id))
                                                deleted++
                                            }
                                        } else {
                                            var cursor: DocumentSnapshot? = null
                                            while (true) {
                                                check(auth.currentUser?.uid == uid) { "Account changed; deletion cancelled." }
                                                val pageQuery = if (cursor == null) q.limit(100)
                                                    else q.startAfter(cursor).limit(100)
                                                val page = pageQuery.get().await().documents
                                                if (page.isEmpty()) break
                                                // Retain the cursor even after the underlying documents are deleted.
                                                cursor = page.last()
                                                for (doc in page) {
                                                    deleteAudioSession(doc.reference)
                                                    deleted++
                                                }
                                            }
                                        }
                                    } else if (mode == "selected") {
                                        ids.chunked(200).forEach { page ->
                                            check(auth.currentUser?.uid == uid) { "Account changed; deletion cancelled." }
                                            val batch = FirebaseFirestore.getInstance().batch()
                                            page.forEach { batch.delete(ref.document(it)) }
                                            batch.commit().await()
                                            deleted += page.size
                                        }
                                    } else {
                                        while (true) {
                                            check(auth.currentUser?.uid == uid) { "Account changed; deletion cancelled." }
                                            val batchDocs = q.limit(200).get().await().documents
                                            if (batchDocs.isEmpty()) break
                                            val batch = FirebaseFirestore.getInstance().batch()
                                            batchDocs.forEach { batch.delete(it.reference) }
                                            batch.commit().await()
                                            deleted += batchDocs.size
                                        }
                                    }
                                    selectedIds = emptySet()
                                    status = "Deleted $deleted records from Firebase."
                                    AdminDiagnostics.record(context, "DELETE", status)
                                    onInfo(status)
                                    reload()
                                } catch (e: Exception) {
                                    status = "Delete failed: ${e.localizedMessage}"
                                    onInfo(status)
                                } finally { busy = false }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text(if (busy) "Deleting…" else "Confirm delete")
                        }
                    }
                }
            }
        }

        if (status.startsWith("Firebase query failed") || status.startsWith("Delete failed")) {
            Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (documents.isEmpty() && status == "Updated from Firebase.") {
            Text("No records for the chosen source and dates.", style = MaterialTheme.typography.bodyMedium)
        }

        // Virtualize records so large call histories do not inflate hundreds of cards at once.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(documents, key = { it.id }) { item ->
            val timestamp = item.getTimestamp(dateField(kind))?.toDate()?.time ?: 0L
            val heading = when (kind) {
                RecordKind.MY_BACKUPS, RecordKind.SHARED_CALLS ->
                    "${item.getString("direction") ?: "UNKNOWN"} · ${item.getString("number") ?: "Unknown"}"
                RecordKind.LOCK_EVENTS ->
                    when (item.getString("eventType")) {
                        "SCREEN_LOCKED" -> "Screen locked"
                        "SCREEN_UNLOCKED" -> "Screen unlocked"
                        else -> "Device event"
                    }
                RecordKind.AUDIO_SESSIONS ->
                    "Session · ${item.getString("status") ?: "Unknown"}"
            }
            val detail = when (kind) {
                RecordKind.MY_BACKUPS, RecordKind.SHARED_CALLS ->
                    "${dateText(timestamp)} · ${item.getLong("durationSeconds") ?: 0L} sec"
                RecordKind.LOCK_EVENTS -> dateText(timestamp)
                RecordKind.AUDIO_SESSIONS ->
                    "${dateText(timestamp)} · Ended: ${dateText(item.getTimestamp("endedAt")?.toDate()?.time ?: 0L)}"
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.id in selectedIds,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                        }
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(heading, fontWeight = FontWeight.SemiBold)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            }
        }

        if (moreAvailable && documents.isNotEmpty()) {
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val last = lastDocument ?: return@OutlinedButton
                    val q = query ?: return@OutlinedButton
                    busy = true
                    scope.launch {
                        try {
                            val results = q.startAfter(last).limit(PAGE_SIZE).get().await().documents
                            olderPages = (olderPages + results).distinctBy { it.id }
                            lastDocument = results.lastOrNull() ?: last
                            moreAvailable = results.size == PAGE_SIZE.toInt()
                            status = "Loaded additional records."
                        } catch (e: Exception) {
                            status = "Unable to load older records: ${e.localizedMessage}"
                            onInfo(status)
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Loading…" else "Load older records") }
        }
    }
}

@Composable
internal fun AccountCallsScreen(devices: List<Pair<String, String>>, onInfo: (String) -> Unit) {
    var group by remember { mutableIntStateOf(0) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(devices) {
        if (deviceId == null || devices.none { it.first == deviceId }) {
            deviceId = devices.firstOrNull()?.first
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Call history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = group == 0, onClick = { group = 0 }, label = { Text("My backups") })
            FilterChip(selected = group == 1, onClick = { group = 1 }, label = { Text("Shared calls") })
        }
        if (group == 1 && devices.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    FilterChip(selected = device.first == deviceId, onClick = { deviceId = device.first },
                        label = { Text(device.second) })
                }
            }
        }
        RecordsPanel(if (group == 0) RecordKind.MY_BACKUPS else RecordKind.SHARED_CALLS, deviceId, onInfo)
    }
}

@Composable
internal fun DeviceEventsScreen(devices: List<Pair<String, String>>, onInfo: (String) -> Unit) {
    var deviceId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(devices) {
        if (deviceId == null || devices.none { it.first == deviceId })
            deviceId = devices.firstOrNull()?.first
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Activity history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (devices.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    FilterChip(selected = device.first == deviceId, onClick = { deviceId = device.first },
                        label = { Text(device.second) })
                }
            }
        }
        RecordsPanel(RecordKind.LOCK_EVENTS, deviceId, onInfo)
    }
}

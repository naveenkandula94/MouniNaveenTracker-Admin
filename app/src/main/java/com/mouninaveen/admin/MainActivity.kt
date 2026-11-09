package com.mouninaveen.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFB9A3FF),
                secondary = androidx.compose.ui.graphics.Color(0xFF67D7C5),
                surface = androidx.compose.ui.graphics.Color(0xFF1B2040),
                background = androidx.compose.ui.graphics.Color(0xFF10152E)
            )) {
                SimpleAdminScreen()
            }
        }
    }
}

private data class DeviceRow(
    val id: String,
    val name: String,
    val collectorUid: String,
    val lastSync: String,
    val syncStatus: String,
    val lastSyncMillis: Long,
    val enabled: Boolean
)

private data class SharedCallRow(
    val id: String,
    val number: String,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val sharedAt: Long
)

private data class EventRow(
    val id: String,
    val app: String,
    val packageName: String,
    val eventType: String,
    val timestamp: Long
)

@Composable
private fun AdminScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var email by remember {
        mutableStateOf(auth.currentUser?.email.orEmpty())
    }

    var password by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var status by remember {
        mutableStateOf("Sign in using your authorized administrator account.")
    }

    var devices by remember {
        mutableStateOf(emptyList<DeviceRow>())
    }

    var selectedDevice by remember {
        mutableStateOf<DeviceRow?>(null)
    }

    var events by remember {
        mutableStateOf(emptyList<EventRow>())
    }

    var collectorUid by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("OnePlus") }
    var searchText by remember { mutableStateOf("") }
    var eventFilter by remember { mutableStateOf("ALL") }
    var selectedTab by remember { mutableStateOf(0) }
    var selectedNavigation by remember { mutableIntStateOf(0) }
    var callDevice by remember { mutableStateOf<DeviceRow?>(null) }
    var sharedCalls by remember { mutableStateOf(emptyList<SharedCallRow>()) }

    fun runTask(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                status = if ((e.localizedMessage ?: "").contains("PERMISSION_DENIED", ignoreCase = true) || (e.localizedMessage ?: "").contains("insufficient permissions", ignoreCase = true)) {
                    "Firestore access denied. Verify admins/{your Auth UID} has role=admin and publish the updated feature-branch Firestore rules (device list queries need permission). Details: ${e.localizedMessage}"
                } else e.localizedMessage ?: "Operation failed."
            } finally {
                busy = false
            }
        }
    }

    suspend fun verifyAdmin(): String {
        val uid = auth.currentUser?.uid
            ?: error("Please sign in again.")

        val adminDoc = db.collection("admins")
            .document(uid)
            .get()
            .await()

        check(
            adminDoc.exists() &&
                adminDoc.getString("role") == "admin"
        ) {
            "This account is not authorized as an administrator. Check admins/$uid and role=admin."
        }

        return uid
    }

    suspend fun loadDevices() {
        val adminUid = verifyAdmin()

        val snapshot = db.collection("devices")
            .whereEqualTo("adminUid", adminUid)
            .get()
            .await()

        devices = snapshot.documents.map { document ->
            DeviceRow(
                id = document.id,
                name = document.getString("displayName")
                    ?: "Collector device",
                collectorUid = document.getString("collectorUid")
                    ?: "",
                lastSync = document.getTimestamp("lastSyncAt")
                    ?.toDate()
                    ?.let { formatDate(it.time) }
                    ?: "Never",
                syncStatus = document.getString("lastSyncStatus")
                    ?: "Not reported",
                lastSyncMillis = document.getTimestamp("lastSyncAt")?.toDate()?.time ?: 0L,
                enabled = document.getBoolean("enabled") != false
            )
        }

        status = "${devices.size} enrolled device(s) loaded."
    }

    suspend fun loadEvents(device: DeviceRow) {
        val snapshot = db.collection("devices")
            .document(device.id)
            .collection("usageEvents")
            .orderBy(
                "eventTimestamp",
                Query.Direction.DESCENDING
            )
            .limit(500)
            .get()
            .await()

        events = snapshot.documents.filter { it.getString("eventType") in listOf("SCREEN_LOCKED", "SCREEN_UNLOCKED") }.map { document ->
            EventRow(
                id = document.id,
                app = document.getString("applicationLabel")
                    ?: "Unknown application",
                packageName = document.getString("packageName")
                    ?: "",
                eventType = document.getString("eventType")
                    ?: "UNKNOWN",
                timestamp = document.getTimestamp("eventTimestamp")
                    ?.toDate()
                    ?.time
                    ?: 0L
            )
        }

        selectedDevice = device

        status = "Loaded ${events.size} lock/unlock events. Android may delay or omit events."
    }

    suspend fun loadSharedCalls(device: DeviceRow) {
        verifyAdmin()
        val snapshot = db.collection("devices").document(device.id)
            .collection("callLogs")
            .orderBy("callTimestamp", Query.Direction.DESCENDING)
            .limit(200).get().await()
        sharedCalls = snapshot.documents.map { document ->
            SharedCallRow(
                id = document.id,
                number = document.getString("number") ?: "Unknown",
                direction = document.getString("direction") ?: "UNKNOWN",
                timestamp = document.getTimestamp("callTimestamp")?.toDate()?.time ?: 0L,
                durationSeconds = document.getLong("durationSeconds")?.toInt() ?: 0,
                sharedAt = document.getTimestamp("sharedAt")?.toDate()?.time ?: 0L
            )
        }
        callDevice = device
        status = "Loaded ${sharedCalls.size} manually shared call records for ${device.name}."
    }

    LaunchedEffect(auth.currentUser?.uid) {
        if (auth.currentUser != null && !signedIn) {
            try {
                verifyAdmin()
                loadDevices()
                signedIn = true
                password = ""
            } catch (e: Exception) {
                status = "Account setup needed: ${e.localizedMessage}. Check the admins document and published Firestore rules."
            }
        }
    }

    val visibleEvents = events.filter { event ->
        val matchesType =
            eventFilter == "ALL" ||
                event.eventType == eventFilter

        val matchesSearch =
            searchText.isBlank() ||
                event.app.contains(searchText, ignoreCase = true) ||
                event.packageName.contains(searchText, ignoreCase = true)

        matchesType && matchesSearch
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DEVICE ADMIN",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Text(
                        "Control Center",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (signedIn) {
                    TextButton(
                        onClick = {
                            auth.signOut()
                            signedIn = false
                            devices = emptyList()
                            events = emptyList()
                            selectedDevice = null
                            password = ""
                            status = "Signed out."
                        }
                    ) {
                        Text("Sign out")
                    }
                }
            }

            if (!signedIn) {

                Card(
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            "Administrator Login",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                runTask {
                                    auth.signInWithEmailAndPassword(
                                        email.trim(),
                                        password
                                    ).await()

                                    verifyAdmin()
                                    loadDevices()

                                    signedIn = true
                                    password = ""
                                }
                            },
                            enabled = !busy &&
                                email.isNotBlank() &&
                                password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (busy) "Signing in..."
                                else "Sign in"
                            )
                        }
                    }
                }

            } else {

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        auth.currentUser?.email ?: "Administrator",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            runTask { loadDevices() }
                        }
                    ) {
                        Text("Refresh")
                    }
                }

                ScrollableTabRow(selectedTabIndex = selectedNavigation, edgePadding = 0.dp) {
                    listOf("Home", "Calls", "Live audio", "Settings").forEachIndexed { index, name ->
                        Tab(
                            selected = selectedNavigation == index,
                            onClick = {
                                selectedNavigation = index
                                selectedTab = listOf(0, 3, 4, 5)[index]
                            },
                            text = { Text(name) }
                        )
                    }
                }
                if (selectedTab == 1 || selectedTab == 2 || selectedTab == 6) {
                    TextButton(onClick = {
                        selectedTab = 5
                        selectedNavigation = 3
                    }) { Text("Back to Settings") }
                }
                if (selectedTab == 0) {
                    val now = System.currentTimeMillis()
                    val recent = devices.count { it.lastSyncMillis > now - 60 * 60 * 1000L }
                    val stopped = devices.count { !it.enabled }
                    Text("Security overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Manage approved devices and review keyguard status history.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Devices", devices.size.toString(), Modifier.weight(1f))
                        MetricCard("Recent sync", recent.toString(), Modifier.weight(1f))
                        MetricCard("Stopped", stopped.toString(), Modifier.weight(1f))
                    }
                    Text("Recent sync means a report in the past hour, not live device presence.", style = MaterialTheme.typography.bodySmall)
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Lock / unlock activity", style = MaterialTheme.typography.titleMedium)
                            Text("Select a device under Devices to see event history and timestamps.")
                            Button(onClick = { selectedTab = 3; selectedNavigation = 1 }) { Text("View shared call history") }
                        }
                    }
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Call history", style = MaterialTheme.typography.titleMedium)
                            Text("View records explicitly imported and approved in the Collector. No automatic call-history extraction.")
                            OutlinedButton(onClick = { selectedTab = 3; selectedNavigation = 1 }) { Text("View calls") }
                        }
                    }
                }
                if (selectedTab == 1) {
                Card(
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Enroll Collector Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Enter the Collector Firebase Authentication UID.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Device name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = collectorUid,
                            onValueChange = { collectorUid = it },
                            label = { Text("Collector UID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            enabled = !busy &&
                                collectorUid.isNotBlank(),
                            onClick = {
                                runTask {
                                    val adminUid = verifyAdmin()

                                    val documentId =
                                        UUID.randomUUID().toString()

                                    db.collection("devices")
                                        .document(documentId)
                                        .set(
                                            mapOf(
                                                "adminUid" to adminUid,
                                                "collectorUid" to collectorUid.trim(),
                                                "displayName" to displayName.trim(),
                                                "createdAt" to FieldValue.serverTimestamp()
                                            )
                                        )
                                        .await()

                                    collectorUid = ""
                                    loadDevices()

                                    status = "Collector enrolled successfully."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enroll Device")
                        }
                    }
                }

                Text(
                    "Enrolled Devices (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (devices.isEmpty()) {
                    Card {
                        Text(
                            "No enrolled devices found.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runTask {
                                            loadEvents(device)
                                            selectedTab = 2
                                        }
                                    },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        device.name,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        "Device ID: ${device.id}",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        "Last sync: ${device.lastSync}",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        "Status: ${device.syncStatus}",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        "Enrollment: ${if (device.enabled) "Active" else "Disabled"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(enabled = !busy, onClick = {
                                        runTask {
                                            verifyAdmin()
                                            db.collection("devices").document(device.id)
                                                .update("enabled", !device.enabled).await()
                                            loadDevices()
                                            status = if (device.enabled) "Enrollment disabled." else "Enrollment enabled."
                                        }
                                    }) { Text(if (device.enabled) "Disable enrollment" else "Enable enrollment") }
                                    Text(
                                        "Tap to view lock/unlock timeline",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                }
                if (selectedTab == 2 && selectedDevice == null) {
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("No device selected", style = MaterialTheme.typography.titleLarge)
                            Text("Open Devices and tap a device to view its lock and unlock history.")
                            Button(onClick = { selectedTab = 1 }) { Text("Choose device") }
                        }
                    }
                }
                if (selectedTab == 2) selectedDevice?.let { device ->

                    HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Lock / Unlock Timeline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        TextButton(
                            onClick = {
                                val csv = buildString {
                                    append("Timestamp,Event\n")

                                    events.forEach { event ->
                                        append(
                                            "\"${csvEscape(if (event.timestamp > 0L) formatDate(event.timestamp) else "Unknown")}\","
                                        )
                                        append(
                                            "\"${csvEscape(event.eventType)}\","
                                        )
                                        append("\n")
                                    }
                                }

                                val intent = Intent(
                                    Intent.ACTION_SEND
                                ).apply {
                                    type = "text/csv"
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        "Lock timeline - ${device.name}"
                                    )
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        csv
                                    )
                                }

                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        "Export lock events CSV"
                                    )
                                )
                            }
                        ) {
                            Text("Export CSV")
                        }
                    }


                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "ALL", "SCREEN_LOCKED", "SCREEN_UNLOCKED"
                        ).forEach { type ->

                            FilterChip(
                                selected = eventFilter == type,
                                onClick = { eventFilter = type },
                                label = {
                                    Text(
                                        type
                                            .replace("APP_", "")
                                            .replace("SCREEN_", "")
                                            .replace("_", " "),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        "${visibleEvents.size} displayed / ${events.size} loaded",
                        style = MaterialTheme.typography.bodySmall
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(visibleEvents, key = { it.id }) { event ->

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    if (event.eventType == "SCREEN_LOCKED") "Locked" else "Unlocked",
                                    fontWeight = FontWeight.Medium
                                )


                                Text(
                                    if (event.timestamp > 0L)
                                        formatDate(event.timestamp)
                                    else "Unknown timestamp",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            HorizontalDivider()
                        }
                    }
                }
                if (selectedTab == 3) {
                    Text("Call History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("User-approved call records stored in Firebase", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        devices.forEach { device ->
                            FilterChip(
                                selected = callDevice?.id == device.id,
                                onClick = { runTask { loadSharedCalls(device) } },
                                label = { Text(device.name) }
                            )
                        }
                    }
                    if (devices.isEmpty()) {
                        Text("No enrolled devices. Add a collector from Devices.")
                    } else if (callDevice == null) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text("Select a device", style = MaterialTheme.typography.titleMedium)
                                Text("Only records explicitly selected and approved in the Collector are displayed here.")
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${sharedCalls.size} shared calls • ${callDevice?.name.orEmpty()}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { callDevice?.let { selected -> runTask { loadSharedCalls(selected) } } }
                            ) { Text("Refresh") }
                        }
                        if (sharedCalls.isEmpty()) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp)) {
                                    Text("No call records shared", style = MaterialTheme.typography.titleMedium)
                                    Text("On the Collector, choose a CSV, review the call details, and approve uploading selected records.")
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sharedCalls, key = { it.id }) { record ->
                                    ElevatedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(record.direction.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                                                Spacer(Modifier.weight(1f))
                                                Text("${record.durationSeconds}s", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Text(record.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (record.timestamp > 0L) formatDate(record.timestamp) else "Unknown date",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "Shared: " + if (record.sharedAt > 0L) formatDate(record.sharedAt) else "Pending server timestamp",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("Only owner-approved imports are shown. No call recordings or message contents.", style = MaterialTheme.typography.labelSmall)
                }
                if (selectedTab == 4) {
                    Text("Live audio", style = MaterialTheme.typography.titleLarge)
                }
                if (selectedTab == 6) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Connection diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Authentication: ${if (auth.currentUser != null) "Signed in" else "Signed out"}")
                            Text("Account UID: ${auth.currentUser?.uid.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            Text("Enrolled devices: ${devices.size}")
                            Text("Latest result: $status")
                            Button(enabled = !busy, onClick = {
                                runTask {
                                    verifyAdmin()
                                    loadDevices()
                                    status = "Admin authorization and device list query both succeeded."
                                }
                            }) { Text("Test Firebase permissions") }
                            Text("If PERMISSION_DENIED persists: check Firestore admins/{UID} role=admin and publish the latest firebase/firestore.rules from the feature branch.", style = MaterialTheme.typography.bodySmall)
                            Text("Audio connection failures: verify Collector started sharing and keep both phones open. STUN-only connections may require a TURN relay.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (selectedTab == 5) {
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Settings & Privacy", style = MaterialTheme.typography.headlineMedium)
                            Text("Administrator: ${auth.currentUser?.email.orEmpty()}")
                            Text("Enrolled devices: ${devices.size}")
                            Text("Allowed automatic uploads: device lock and unlock events only.")
                            Text("Call history: user-reviewed device call logs or selected CSV imports, stored per enrolled device.")
                            Text("Sync timing: Android-managed periodic work; not guaranteed instantaneous.")
                            HorizontalDivider()
                            Text("To revoke collection, disable a device on the Devices page. The device owner can also withdraw consent in the Collector.")
                            OutlinedButton(onClick = { runTask { loadDevices() } }, enabled = !busy) { Text("Refresh devices") }
                            OutlinedButton(onClick = { selectedTab = 6 }) { Text("Diagnostics & permission test") }
                            TextButton(onClick = { selectedTab = 1 }) { Text("Device setup / pairing (advanced)") }
                            TextButton(onClick = { selectedTab = 2 }) { Text("Lock/unlock timeline") }
                        }
                    }
                }
            }

            // Preserve the WebRTC listening peer when moving to another Admin tab.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .then(if (selectedTab == 4) Modifier else Modifier.height(0.dp))
                    .clipToBounds()
            ) {
                LiveMicrophoneListener(devices.map { it.id to it.name })
            }

            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "Privacy: Lock/unlock metadata syncs separately. Call records appear only after the Collector user reviews and approves sharing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDate(millis: Long): String {
    return SimpleDateFormat(
        "dd MMM yyyy, hh:mm:ss a",
        Locale.getDefault()
    ).format(Date(millis))
}

private fun csvEscape(value: String): String {
    return value.replace("\"", "\"\"")
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

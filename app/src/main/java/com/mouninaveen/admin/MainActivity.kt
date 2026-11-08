package com.mouninaveen.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
            MaterialTheme {
                AdminScreen()
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

    fun runTask(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                status = e.localizedMessage ?: "Operation failed."
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

        events = snapshot.documents.map { document ->
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

        status = "Loaded ${events.size} usage events. Android may delay or omit some events."
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MOUNI & NAVEEN",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Text(
                        "Admin Dashboard",
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

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    listOf("Overview", "Devices", "Activity").forEachIndexed { index, name ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(name) })
                    }
                }
                if (selectedTab == 0) {
                    val now = System.currentTimeMillis()
                    val recent = devices.count { it.lastSyncMillis > now - 60 * 60 * 1000L }
                    val stopped = devices.count { !it.enabled }
                    Text("Fleet overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Devices", devices.size.toString(), Modifier.weight(1f))
                        MetricCard("Recent sync", recent.toString(), Modifier.weight(1f))
                        MetricCard("Stopped", stopped.toString(), Modifier.weight(1f))
                    }
                    Text("Recent sync means a successful report in the past hour, not live device presence.", style = MaterialTheme.typography.bodySmall)
                }
                if (selectedTab != 2) {
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
                                    Text(
                                        "Tap to view activity",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                }
                if (selectedTab != 1) selectedDevice?.let { device ->

                    HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Usage Activity",
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
                                    append("Timestamp,Event,Application,Package\n")

                                    events.forEach { event ->
                                        append(
                                            "\"${csvEscape(if (event.timestamp > 0L) formatDate(event.timestamp) else "Unknown")}\","
                                        )
                                        append(
                                            "\"${csvEscape(event.eventType)}\","
                                        )
                                        append(
                                            "\"${csvEscape(event.app)}\","
                                        )
                                        append(
                                            "\"${csvEscape(event.packageName)}\"\n"
                                        )
                                    }
                                }

                                val intent = Intent(
                                    Intent.ACTION_SEND
                                ).apply {
                                    type = "text/csv"
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        "Usage activity - ${device.name}"
                                    )
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        csv
                                    )
                                }

                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        "Export usage CSV"
                                    )
                                )
                            }
                        ) {
                            Text("Export CSV")
                        }
                    }

                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text("Search app or package") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "ALL", "APP_FOREGROUND", "APP_BACKGROUND",
                            "SCREEN_LOCKED", "SCREEN_UNLOCKED"
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
                                    "${event.eventType.replace('_', ' ')} • ${event.app}",
                                    fontWeight = FontWeight.Medium
                                )

                                Text(
                                    event.packageName,
                                    style = MaterialTheme.typography.bodySmall
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
            }

            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "Privacy: Displays permitted usage-event metadata only. " +
                    "No message contents, calls, photos, microphone, camera, " +
                    "or screen recordings are accessed. Android may delay " +
                    "or omit usage events.",
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

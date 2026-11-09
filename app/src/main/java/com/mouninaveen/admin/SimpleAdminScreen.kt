package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class SimpleDevice(val id: String, val name: String, val collectorUid: String, val enabled: Boolean, val lastSyncAt: Long, val syncStatus: String)
private data class SimpleCall(val id: String, val number: String, val direction: String, val dateMillis: Long, val duration: Int)

@Composable
fun SimpleAdminScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var authenticated by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in to your Admin account.") }
    var devices by remember { mutableStateOf(emptyList<SimpleDevice>()) }
    var calls by remember { mutableStateOf(emptyList<SimpleCall>()) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf("Not checked") }
    var selectedCallIds by remember { mutableStateOf(emptySet<String>()) }
    var deleteMode by remember { mutableStateOf("") }
    var diagnosticsVersion by remember { mutableIntStateOf(0) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAudioHistory by remember { mutableStateOf(false) }

    suspend fun verifyAndLoad() {
        val uid = auth.currentUser?.uid ?: error("Sign in first.")
        val admin = db.collection("admins").document(uid).get().await()
        check(admin.getString("role") == "admin") {
            "Missing Admin access. Create Firestore admins/$uid with role (string) = admin."
        }
        val snapshot = db.collection("devices").whereEqualTo("adminUid", uid).get().await()
        devices = snapshot.documents.map { doc ->
            SimpleDevice(
                doc.id,
                doc.getString("displayName") ?: "My device",
                doc.getString("collectorUid") ?: "",
                doc.getBoolean("enabled") != false,
                doc.getTimestamp("lastSyncAt")?.toDate()?.time ?: 0L,
                doc.getString("lastSyncStatus") ?: "No sync reported"
            )
        }
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
        }
        authenticated = true
        status = if (devices.isEmpty()) "Account ready. Link your own Collector under Settings." else "Connected to Firebase."
    }

    fun run(operation: suspend () -> Unit) {
        if (loading) return
        loading = true
        scope.launch {
            try { operation() }
            catch (e: Exception) {
                status = "Error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally { loading = false }
        }
    }

    LaunchedEffect(status) {
        if (status.isNotBlank()) {
            AdminDiagnostics.record(context, "ADMIN", status)
            diagnosticsVersion++
        }
    }

    LaunchedEffect(auth.currentUser?.uid) {
        if (auth.currentUser != null && !authenticated) {
            try { verifyAndLoad() }
            catch (e: Exception) {
                status = "Account setup required: ${e.localizedMessage}"
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("ADMIN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Device dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (authenticated) "Signed in • ${devices.size} linked device(s)" else "Secure sign in", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        bottomBar = {
            if (authenticated) {
                NavigationBar {
                    listOf("Home", "Calls", "Events", "Live", "More").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(listOf("⌂", "☎", "▣", "◉", "⚙")[i]) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!authenticated) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Admin sign in", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                run {
                                    auth.signInWithEmailAndPassword(email.trim(), password).await()
                                    verifyAndLoad()
                                    password = ""
                                }
                            }
                        ) { Text(if (loading) "Signing in…" else "Sign in") }
                    }
                }
            } else {
                if (tab == 0) {
                    AdminOverview(devices.map { it.id to it.name }) { message ->
                        status = message
                    }
                    Text("Linked devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (devices.isEmpty()) {
                        Text("No Collector device linked. Open More → Advanced to link your account.")
                    }
                    devices.forEach { device ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(if (device.enabled) "Enrollment active" else "Enrollment disabled",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Last lock/unlock sync: " +
                                            if (device.lastSyncAt > 0L) SimpleDateFormat(
                                                "dd MMM yyyy HH:mm", Locale.getDefault()
                                            ).format(Date(device.lastSyncAt)) else "Not yet",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text("Status: ${device.syncStatus}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (tab == 1) {
                    AccountCallsScreen(devices.map { it.id to it.name }) { message ->
                        status = message
                    }
                }
                if (tab == 2) {
                    DeviceEventsScreen(devices.map { it.id to it.name }) { message ->
                        status = message
                    }
                }
                if (tab == 3) {
                    Text("Live microphone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Collector user must explicitly start the session. Admin cannot remotely activate a microphone.", style = MaterialTheme.typography.bodySmall)
                }
                if (tab == 4) {
                    Text("More", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Signed in: ${auth.currentUser?.email.orEmpty()}")
                            OutlinedButton(enabled = !loading, onClick = {
                                run {
                                    verifyAndLoad()
                                    diagnosticText = "Connected — account role and device permissions verified."
                                }
                            }) { Text("Check Firebase connection") }
                            Text(diagnosticText, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                            Text("Connection history is stored locally and excludes audio and call numbers.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }) {
                                Text(if (showDiagnostics) "Hide diagnostic logs" else "View diagnostic logs")
                            }
                            if (showDiagnostics) {
                                key(diagnosticsVersion) {
                                    Text(AdminDiagnostics.read(context), style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { diagnosticsVersion++ }) { Text("Refresh") }
                                    OutlinedButton(onClick = {
                                        AdminDiagnostics.clear(context)
                                        diagnosticsVersion++
                                    }) { Text("Clear logs") }
                                }
                            }
                            HorizontalDivider()
                            Text("Audio session history", style = MaterialTheme.typography.titleMedium)
                            Text("View connection timestamps and clean up Firestore session records.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showAudioHistory = !showAudioHistory }) {
                                Text(if (showAudioHistory) "Hide session history" else "View session history")
                            }

                            TextButton(onClick = { showSetup = !showSetup }) {
                                Text(if (showSetup) "Hide device linking" else "Advanced: Link my own Collector")
                            }
                            if (showSetup) {
                                Text("This uses the SAME Firebase UID for Admin and Collector. Only use on your own device/account.")
                                Button(enabled = !loading && auth.currentUser != null, onClick = {
                                    run {
                                        val uid = auth.currentUser?.uid ?: error("Sign in first.")
                                        val matches = db.collection("devices").whereEqualTo("collectorUid", uid).get().await()
                                        if (matches.isEmpty) {
                                            db.collection("devices").document(UUID.randomUUID().toString()).set(
                                                mapOf("adminUid" to uid, "collectorUid" to uid,
                                                    "displayName" to "My Android phone",
                                                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
                                            ).await()
                                        }
                                        verifyAndLoad()
                                        status = "Your Collector account is linked."
                                    }
                                }) { Text("Link same-account Collector") }
                            }
                            OutlinedButton(onClick = {
                                auth.signOut()
                                authenticated = false
                                devices = emptyList()
                                calls = emptyList()
                                selectedDeviceId = null
                                status = "Signed out."
                            }) { Text("Sign out") }
                        }
                    }
                }
            }
            if (authenticated && tab == 4 && showAudioHistory && selectedDeviceId != null) {
                RecordsPanel(RecordKind.AUDIO_SESSIONS, selectedDeviceId) { message ->
                    status = message
                }
            }
            if (authenticated) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (tab == 3) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    LiveMicrophoneListener(devices.map { it.id to it.name }, onStatus = { message ->
                        AdminDiagnostics.record(context, "AUDIO", message)
                        diagnosticsVersion++
                    })
                }
            }
            if (tab == 4 || status.startsWith("Error") || status.startsWith("Account setup")) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

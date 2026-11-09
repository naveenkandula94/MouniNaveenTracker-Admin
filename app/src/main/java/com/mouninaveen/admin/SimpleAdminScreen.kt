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

private data class SimpleDevice(val id: String, val name: String, val collectorUid: String, val enabled: Boolean)
private data class SimpleCall(val id: String, val number: String, val direction: String, val dateMillis: Long, val duration: Int)

@Composable
fun SimpleAdminScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
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
                doc.getBoolean("enabled") != false
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

    LaunchedEffect(auth.currentUser?.uid) {
        if (auth.currentUser != null && !authenticated) {
            try { verifyAndLoad() }
            catch (e: Exception) {
                status = "Account setup required: ${e.localizedMessage}"
            }
        }
    }

    LaunchedEffect(authenticated, selectedDeviceId, tab) {
        if (authenticated && tab == 1 && selectedDeviceId != null) {
            try {
                val docs = db.collection("devices").document(selectedDeviceId!!)
                    .collection("callLogs")
                    .orderBy("callTimestamp", Query.Direction.DESCENDING)
                    .limit(100).get().await()
                calls = docs.documents.map { doc ->
                    SimpleCall(
                        doc.id,
                        doc.getString("number") ?: "Unknown",
                        doc.getString("direction") ?: "UNKNOWN",
                        doc.getTimestamp("callTimestamp")?.toDate()?.time ?: 0L,
                        doc.getLong("durationSeconds")?.toInt() ?: 0
                    )
                }
            } catch (e: Exception) {
                status = "Cannot load shared calls: ${e.localizedMessage}"
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(tonalElevation = 2.dp) {
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
                    listOf("Home", "Calls", "Live", "Settings").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(listOf("⌂", "☎", "◉", "⚙")[i]) },
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
                    Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${devices.size} linked device(s)", style = MaterialTheme.typography.headlineSmall)
                            Text(if (devices.isEmpty()) "No linked device yet. Go to Settings to link your own Collector account."
                                else "View owner-approved call records or join a live audio session started on Collector.")
                            Button(enabled = !loading, onClick = { run { verifyAndLoad() } }) { Text("Refresh status") }
                        }
                    }
                    devices.forEach { device ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (device.enabled) "Enrollment enabled" else "Enrollment disabled", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (tab == 1) {
                    Text("Shared call history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Only calls deliberately shared by Collector appear here. The owner's automatic private backup is not an Admin feed.", style = MaterialTheme.typography.bodySmall)
                    if (devices.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            devices.forEach { d ->
                                FilterChip(
                                    selected = selectedDeviceId == d.id,
                                    onClick = { selectedDeviceId = d.id },
                                    label = { Text(d.name, maxLines = 1) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${calls.size} shared calls", modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = selectedDeviceId != null, onClick = {
                            val id = selectedDeviceId ?: return@OutlinedButton
                            run {
                                val docs = db.collection("devices").document(id).collection("callLogs")
                                    .orderBy("callTimestamp", Query.Direction.DESCENDING).limit(100).get().await()
                                calls = docs.documents.map { doc ->
                                    SimpleCall(doc.id, doc.getString("number") ?: "Unknown",
                                        doc.getString("direction") ?: "UNKNOWN",
                                        doc.getTimestamp("callTimestamp")?.toDate()?.time ?: 0L,
                                        doc.getLong("durationSeconds")?.toInt() ?: 0)
                                }
                                status = "Shared call history refreshed."
                            }
                        }) { Text("Refresh") }
                    }
                    if (calls.isEmpty()) Text("No owner-approved call records for this device.")
                    calls.forEach { call ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${call.direction} • ${call.number}", fontWeight = FontWeight.SemiBold)
                                Text("${if (call.dateMillis > 0) SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(call.dateMillis)) else "Unknown date"} • ${call.duration}s")
                            }
                        }
                    }
                }
                if (tab == 2) {
                    Text("Live microphone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Collector user must explicitly start the session. Admin cannot remotely activate a microphone.", style = MaterialTheme.typography.bodySmall)
                }
                if (tab == 3) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Signed in: ${auth.currentUser?.email.orEmpty()}")
                            OutlinedButton(enabled = !loading, onClick = {
                                run {
                                    verifyAndLoad()
                                    diagnosticText = "Admin permission and device query: OK"
                                }
                            }) { Text("Test Firebase connection") }
                            Text(diagnosticText, style = MaterialTheme.typography.bodySmall)
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
            if (authenticated) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (tab == 2) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    LiveMicrophoneListener(devices.map { it.id to it.name })
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

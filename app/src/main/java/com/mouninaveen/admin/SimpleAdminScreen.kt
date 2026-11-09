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
import androidx.compose.ui.graphics.Color
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

private data class SimpleDevice(
    val id: String, val name: String, val collectorUid: String, val enabled: Boolean,
    val lastSyncMillis: Long, val lastSyncStatus: String
)
private data class SimpleCall(val id: String, val number: String, val direction: String, val dateMillis: Long, val duration: Int)

@Composable
fun SimpleAdminScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val phonePreference = remember { context.getSharedPreferences("admin_selected_phone", 0) }
    var authenticated by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in to your Admin account.") }
    var devices by remember { mutableStateOf(emptyList<SimpleDevice>()) }
    var calls by remember { mutableStateOf(emptyList<SimpleCall>()) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var newPhoneName by remember { mutableStateOf("") }
    var newCollectorUid by remember { mutableStateOf("") }
    var diagnosticText by remember { mutableStateOf("Not checked") }
    var selectedCallIds by remember { mutableStateOf(emptySet<String>()) }
    var deleteMode by remember { mutableStateOf("") }
    var callsSource by remember { mutableStateOf("private") }
    var showDeviceActivity by remember { mutableStateOf(false) }
    var diagnosticsVersion by remember { mutableIntStateOf(0) }

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
                doc.getString("lastSyncStatus") ?: "Not synced yet"
            )
        }.sortedWith(compareByDescending<SimpleDevice> { it.lastSyncMillis }.thenBy { it.name })
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            val saved = phonePreference.getString(uid, null)
            selectedDeviceId = devices.firstOrNull { it.id == saved }?.id
                ?: devices.firstOrNull()?.id
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

    // Live Firebase device status: no Admin-side collection or polling required.
    DisposableEffect(authenticated, auth.currentUser?.uid) {
        val uid = auth.currentUser?.uid
        if (authenticated && uid != null) {
            val subscription = db.collection("devices")
                .whereEqualTo("adminUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status = "Device status listener: ${error.localizedMessage}"
                    } else if (snapshot != null) {
                        devices = snapshot.documents.map { doc ->
                            SimpleDevice(
                                doc.id,
                                doc.getString("displayName") ?: "My device",
                                doc.getString("collectorUid") ?: "",
                                doc.getBoolean("enabled") != false,
                                doc.getTimestamp("lastSyncAt")?.toDate()?.time ?: 0L,
                                doc.getString("lastSyncStatus") ?: "Not synced yet"
                            )
                        }.sortedWith(
                            compareByDescending<SimpleDevice> { it.lastSyncMillis }
                                .thenBy { it.name }
                        )
                        if (selectedDeviceId == null ||
                            devices.none { it.id == selectedDeviceId }) {
                            val saved = phonePreference.getString(uid, null)
                            selectedDeviceId = devices.firstOrNull { it.id == saved }?.id
                                ?: devices.firstOrNull()?.id
                        }
                    }
                }
            onDispose { subscription.remove() }
        } else onDispose { }
    }

    // Remember the explicitly chosen phone per Firebase UID across app launches.
    // This avoids silently switching live audio to whichever phone sorts first.
    LaunchedEffect(authenticated, selectedDeviceId) {
        val owner = auth.currentUser?.uid
        val id = selectedDeviceId
        if (authenticated && owner != null && id != null) {
            phonePreference.edit().putString(owner, id).apply()
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
            selectedCallIds = emptySet()
            deleteMode = ""
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
            Surface(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars), color = Color(0xFFE3EEFF), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("ADMIN_APP  /  DASHBOARD", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2955A5), fontWeight = FontWeight.Bold)
                    Text("Device overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (authenticated) "${devices.size} linked phone(s)" else "Secure sign in", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        bottomBar = {
            if (authenticated) {
                NavigationBar(containerColor = Color(0xFFEAF0FF)) {
                    // Seven independent destinations are scrollable rather than
                    // squeezing unreadable labels into a narrow phone screen.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        listOf("Home", "Calls", "Messages", "Lock", "Live", "Logs", "Settings")
                            .forEachIndexed { i, label ->
                                NavigationBarItem(
                                    modifier = Modifier.width(84.dp),
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Text(listOf("⌂", "☎", "✉", "▣", "◉", "≡", "⚙")[i]) },
                                    label = { Text(label, maxLines = 1) },
                                    alwaysShowLabel = true
                                )
                            }
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
                if (devices.isNotEmpty()) {
                    val chosen = devices.firstOrNull { it.id == selectedDeviceId }
                    Text("SELECTED DEVICE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF315EA1))
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { deviceMenuExpanded = true }
                        ) {
                            Text("▣  ${chosen?.name ?: "Select a phone"}    ▾")
                        }
                        DropdownMenu(
                            expanded = deviceMenuExpanded,
                            onDismissRequest = { deviceMenuExpanded = false }
                        ) {
                            devices.forEach { phone ->
                                DropdownMenuItem(
                                    text = { Text("${phone.name}  ${if (phone.enabled) "✓" else "(disabled)"}") },
                                    onClick = {
                                        selectedDeviceId = phone.id
                                        selectedCallIds = emptySet()
                                        deleteMode = ""
                                        deviceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (chosen != null) {
                        AdminStatusDot(
                            chosen.lastSyncMillis > 0L &&
                                System.currentTimeMillis() - chosen.lastSyncMillis <= 120_000L,
                            hasEverSynced = chosen.lastSyncMillis > 0L
                        )
                        Text("Phone ID: ${chosen.id.take(8)}… · ${chosen.lastSyncStatus}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Select the same named phone in Personal_App > Account.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (tab == 0) {
                    Text("Overview", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    val recent = devices.count {
                        it.lastSyncMillis > 0L &&
                            System.currentTimeMillis() - it.lastSyncMillis <= 120_000L
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AdminMetricTile(
                            title = "Linked phones",
                            value = "${devices.size}",
                            fill = Color(0xFFE4EDFF),
                            modifier = Modifier.weight(1f)
                        )
                        AdminMetricTile(
                            title = "Checked recently",
                            value = "$recent",
                            fill = Color(0xFFDDF5EB),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Devices", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(enabled = !loading, onClick = {
                            run { verifyAndLoad() }
                        }) { Text("Refresh") }
                    }
                    if (devices.isEmpty()) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text("No phones linked")
                                TextButton(onClick = { tab = 6; showSetup = true }) {
                                    Text("Add phone")
                                }
                            }
                        }
                    }
                    devices.forEach { device ->
                        val recentCheck = device.lastSyncMillis > 0L &&
                            System.currentTimeMillis() - device.lastSyncMillis <= 120_000L
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedDeviceId == device.id)
                                    Color(0xFFF0F0FF) else Color.White
                            )
                        ) {
                            Column(Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold)
                                        Text(if (device.enabled) "Enabled" else "Disabled",
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                    TextButton(onClick = { selectedDeviceId = device.id }) {
                                        Text(if (selectedDeviceId == device.id) "Selected" else "View")
                                    }
                                }
                                AdminStatusDot(recentCheck,
                                    hasEverSynced = device.lastSyncMillis > 0L)
                                Text(if (device.lastSyncMillis > 0L)
                                    SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
                                        .format(Date(device.lastSyncMillis))
                                    else "No sync yet",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (tab == 1) {
                    Text("Call history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = callsSource == "private",
                            onClick = { callsSource = "private" },
                            label = { Text("Backups") }
                        )
                        FilterChip(
                            selected = callsSource == "shared",
                            onClick = { callsSource = "shared" },
                            label = { Text("Shared") }
                        )
                        FilterChip(
                            selected = callsSource == "legacy",
                            onClick = { callsSource = "legacy" },
                            label = { Text("Earlier") }
                        )
                    }
                    if (callsSource == "private" || callsSource == "legacy") {
                        if (callsSource == "private" && selectedDeviceId == null) {
                            Text(
                                "No phone is linked to this Admin account. New backups are separated by phone. Add a phone in Settings; if your previous Personal_App already uploaded records, tap Earlier records to view those older uploads.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(onClick = { callsSource = "legacy" }) {
                                Text("View earlier uploaded records")
                            }
                        } else {
                            val chosen = devices.firstOrNull { it.id == selectedDeviceId }
                            key(selectedDeviceId, callsSource) {
                                OwnerBackupScreen(
                                    deviceId = selectedDeviceId,
                                    deviceName = chosen?.name ?: "Earlier uploads",
                                    legacy = callsSource == "legacy",
                                    onStatus = { status = it }
                                )
                            }
                        }
                    } else {
                    

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
                    if (calls.isEmpty()) {
                        Text("No shared call records stored for this device.")
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                selectedCallIds = if (selectedCallIds.size == calls.size) emptySet()
                                    else calls.map { it.id }.toSet()
                            }) {
                                Text(if (selectedCallIds.size == calls.size) "Clear selection" else "Select all shown")
                            }
                            OutlinedButton(onClick = { deleteMode = "all" }) { Text("Delete all") }
                        }
                        if (selectedCallIds.isNotEmpty()) {
                            Button(
                                enabled = !loading,
                                onClick = { deleteMode = "selected" },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Delete selected (${selectedCallIds.size})") }
                        }
                        if (deleteMode.isNotBlank()) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (deleteMode == "all")
                                            "Permanently delete ALL Admin-shared call records for this device?"
                                        else "Permanently delete ${selectedCallIds.size} selected records?",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text("This does not remove the Collector owner's private backup.",
                                        style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { deleteMode = "" }) { Text("Cancel") }
                                        Button(
                                            enabled = !loading,
                                            onClick = {
                                                val mode = deleteMode
                                                val deviceId = selectedDeviceId ?: return@Button
                                                val chosen = selectedCallIds.toList()
                                                deleteMode = ""
                                                run {
                                                    // Verify that this device still belongs to the signed-in Admin.
                                                    val ownerUid = auth.currentUser?.uid ?: error("Sign in first.")
                                                    val device = db.collection("devices").document(deviceId).get().await()
                                                    check(device.getString("adminUid") == ownerUid) { "Device is not yours." }
                                                    val collection = device.reference.collection("callLogs")
                                                    var deleted = 0
                                                    if (mode == "all") {
                                                        while (true) {
                                                            val page = collection.limit(200).get().await().documents
                                                            if (page.isEmpty()) break
                                                            val batch = db.batch()
                                                            page.forEach { batch.delete(it.reference) }
                                                            batch.commit().await()
                                                            deleted += page.size
                                                        }
                                                    } else {
                                                        if (chosen.isNotEmpty()) {
                                                            val batch = db.batch()
                                                            chosen.forEach { batch.delete(collection.document(it)) }
                                                            batch.commit().await()
                                                            deleted = chosen.size
                                                        }
                                                    }
                                                    calls = collection.orderBy("callTimestamp", Query.Direction.DESCENDING)
                                                        .limit(100).get().await().documents.map { doc ->
                                                            SimpleCall(
                                                                doc.id, doc.getString("number") ?: "Unknown",
                                                                doc.getString("direction") ?: "UNKNOWN",
                                                                doc.getTimestamp("callTimestamp")?.toDate()?.time ?: 0L,
                                                                doc.getLong("durationSeconds")?.toInt() ?: 0
                                                            )
                                                        }
                                                    selectedCallIds = emptySet()
                                                    status = "Deleted $deleted Admin-shared call records."
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Confirm delete") }
                                    }
                                }
                            }
                        }
                        calls.forEach { call ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = call.id in selectedCallIds,
                                        onCheckedChange = { checked ->
                                            selectedCallIds = if (checked) selectedCallIds + call.id
                                                else selectedCallIds - call.id
                                        }
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${call.direction} • ${call.number}", fontWeight = FontWeight.SemiBold)
                                        Text("${if (call.dateMillis > 0) SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(call.dateMillis)) else "Unknown date"} • ${call.duration}s")
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                if (tab == 2) {
                    val phone = devices.firstOrNull { it.id == selectedDeviceId }
                    key(selectedDeviceId) {
                        MessagesScreen(
                            deviceId = selectedDeviceId,
                            deviceName = phone?.name ?: "No linked phone",
                            onStatus = { status = it }
                        )
                    }
                }
                if (tab == 3) {
                    
                    if (selectedDeviceId == null) {
                        Text("Select a linked phone from the dropdown above to view its screen state history.")
                    } else {
                        key(selectedDeviceId) {
                            DeviceDataScreen(
                                deviceId = selectedDeviceId!!,
                                initialKind = "Lock/unlock",
                                fixedKind = true,
                                onStatus = { status = it }
                            )
                        }
                    }
                }
                if (tab == 4) {
                    Text("Live audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Listen to a live session started by the selected phone's owner. Admin cannot activate a microphone remotely.", style = MaterialTheme.typography.bodySmall)
                }
                if (tab == 5) {
                    if (selectedDeviceId != null) {
                        key(selectedDeviceId) {
                            DeviceDataScreen(
                                deviceId = selectedDeviceId!!,
                                initialKind = "Sync logs",
                                fixedKind = true,
                                onStatus = { status = it }
                            )
                        }
                    }
                    Text("Admin app logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Admin diagnostics",
                        style = MaterialTheme.typography.bodySmall)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    }
                }
                if (tab == 6) {
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
                                Text(if (showSetup) "Hide device linking" else "Add or manage phones")
                            }
                            if (showSetup) {
                                
                                OutlinedTextField(
                                    value = newPhoneName, onValueChange = { newPhoneName = it.take(45) },
                                    label = { Text("Phone name (for example Samsung S24)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = newCollectorUid, onValueChange = { newCollectorUid = it.take(128) },
                                    label = { Text("Personal_App UID (blank = same account)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Button(
                                    enabled = !loading && newPhoneName.isNotBlank(),
                                    onClick = {
                                        run {
                                            val uid = auth.currentUser?.uid ?: error("Sign in first.")
                                            val collector = newCollectorUid.trim().ifBlank { uid }
                                            db.collection("devices").document(UUID.randomUUID().toString()).set(
                                                mapOf(
                                                    "adminUid" to uid, "collectorUid" to collector,
                                                    "displayName" to newPhoneName.trim(),
                                                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                                )
                                            ).await()
                                            verifyAndLoad()
                                            newPhoneName = ""
                                            status = "New phone created. Open Personal_App > Account to select it."
                                        }
                                    }
                                ) { Text("Add phone") }
                                Text("Other Firebase UIDs retain their private backup access.",
                                    style = MaterialTheme.typography.bodySmall)
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
                        .then(if (tab == 4) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    LiveMicrophoneListener(
                        devices.map { it.id to it.name },
                        preferredDeviceId = selectedDeviceId,
                        onStatus = { message ->
                        AdminDiagnostics.record(context, "AUDIO", message)
                        diagnosticsVersion++
                    })
                }
            }
            if (tab == 5 || tab == 6 || status.startsWith("Error") || status.startsWith("Account setup")) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

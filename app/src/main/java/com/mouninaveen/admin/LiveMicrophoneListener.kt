package com.mouninaveen.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

private class ListeningSession(
    val peer: LiveAudioPeer,
    val subscriptions: List<ListenerRegistration>
) {
    fun stop() {
        subscriptions.forEach { it.remove() }
        peer.close()
    }
}

@Composable
fun LiveMicrophoneListener(devices: List<Pair<String, String>>) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    var deviceId by remember { mutableStateOf<String?>(null) }
    var availableId by remember { mutableStateOf<String?>(null) }
    var availableRef by remember { mutableStateOf<DocumentReference?>(null) }
    var status by remember { mutableStateOf("Choose a device and search for a live microphone session.") }
    var listening by remember { mutableStateOf<ListeningSession?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { listening?.stop() }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live Microphone", style = MaterialTheme.typography.headlineSmall)
            Text("Listen only after the Collector user has started an approved live session. No remote microphone activation or recordings.")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { item ->
                    FilterChip(
                        selected = deviceId == item.first,
                        onClick = {
                            listening?.stop()
                            listening = null
                            deviceId = item.first
                            availableRef = null
                            availableId = null
                            status = "Tap Find live session to check ${item.second}."
                        },
                        label = { Text(item.second) }
                    )
                }
            }
            Button(
                enabled = deviceId != null && !busy && listening == null,
                onClick = {
                    val id = deviceId ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            check(auth.currentUser != null) { "Admin login required." }
                            val snapshot = db.collection("devices").document(id)
                                .collection("liveAudio")
                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                .limit(5).get().await()
                            val doc = snapshot.documents.firstOrNull {
                                it.getString("status") == "OFFERED" &&
                                    (System.currentTimeMillis() - (it.getTimestamp("createdAt")?.toDate()?.time ?: 0L) < 10 * 60_000L)
                            }
                            if (doc == null) {
                                availableRef = null
                                availableId = null
                                status = "No recent live session. Ask the Collector user to tap Start live microphone."
                            } else {
                                availableRef = doc.reference
                                availableId = doc.id
                                status = "The Collector has started a live session. Tap Listen now."
                            }
                        } catch (e: Exception) {
                            status = "Could not find a session: ${e.localizedMessage}"
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Checking…" else "Find live session") }
            if (availableId != null && listening == null) {
                Button(enabled = !busy, onClick = {
                    val ref = availableRef ?: return@Button
                    busy = true
                    scope.launch {
                        var newPeer: LiveAudioPeer? = null
                        try {
                            val snapshot = ref.get().await()
                            check(snapshot.getString("status") == "OFFERED") { "The microphone session is not available." }
                            val offer = snapshot.getString("offer") ?: error("The offer has not arrived.")
                            val peer = LiveAudioPeer(
                                context, false,
                                onIce = { candidate ->
                                    ref.collection("listenerIce").add(mapOf(
                                        "sdp" to candidate.sdp,
                                        "mid" to (candidate.sdpMid ?: ""),
                                        "line" to candidate.sdpMLineIndex
                                    ))
                                },
                                onStatus = { message ->
                                    (context as android.app.Activity).runOnUiThread { status = "Audio: $message" }
                                }
                            )
                            newPeer = peer
                            val ice = ref.collection("publisherIce").addSnapshotListener { events, _ ->
                                events?.documentChanges?.forEach { change ->
                                    if (change.type == DocumentChange.Type.ADDED) {
                                        val d = change.document
                                        val sdp = d.getString("sdp") ?: return@forEach
                                        peer.addRemoteCandidate(IceCandidate(d.getString("mid") ?: "", d.getLong("line")?.toInt() ?: 0, sdp))
                                    }
                                }
                            }
                            val sessionMonitor = ref.addSnapshotListener { doc, _ ->
                                if (doc?.getString("status") == "ENDED") status = "Collector stopped sharing. Tap Disconnect."
                            }
                            listening = ListeningSession(peer, listOf(ice, sessionMonitor))
                            peer.receiveOffer(offer) { answer ->
                                ref.update("answer", answer, "status", "ANSWERED")
                            }
                            status = "Connecting to Collector live microphone…"
                        } catch (e: Exception) {
                            newPeer?.close()
                            listening?.stop()
                            listening = null
                            status = "Listening failed: ${e.localizedMessage}"
                        } finally { busy = false }
                    }
                }) { Text("Listen now") }
            }
            if (listening != null) {
                Button(onClick = {
                    listening?.stop()
                    listening = null
                    availableRef = null
                    availableId = null
                    status = "Disconnected from audio."
                }) { Text("Disconnect") }
            }
            Text(status)
            Text("Keep both apps open during this version of live audio. A TURN relay may be required on some mobile networks.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.mouninaveen.admin

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

private class ListeningSession(
    val peer: LiveAudioPeer,
    val subscriptions: List<ListenerRegistration>
) {
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        subscriptions.forEach { it.remove() }
        peer.close()
    }
}

@Composable
fun LiveMicrophoneListener(
    devices: List<Pair<String, String>>,
    preferredDeviceId: String? = null,
    onStatus: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    var deviceId by remember { mutableStateOf<String?>(null) }
    var availableId by remember { mutableStateOf<String?>(null) }
    var availableRef by remember { mutableStateOf<DocumentReference?>(null) }
    var status by remember { mutableStateOf("Choose a device and search for a live microphone session.") }
    var listening by remember { mutableStateOf<ListeningSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    var playbackMuted by remember { mutableStateOf(false) }
    var speakerEnabled by remember { mutableStateOf(true) }
    var reconnectUntil by remember { mutableLongStateOf(0L) }
    var joinedSessionId by remember { mutableStateOf<String?>(null) }

    fun joinSession(ref: DocumentReference, automatic: Boolean) {
        if (busy || listening != null) return
        if (!automatic) reconnectUntil = System.currentTimeMillis() + 120_000L
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
                        )).addOnFailureListener { error ->
                            (context as android.app.Activity).runOnUiThread {
                                status = "ICE upload failed: ${error.localizedMessage}"
                            }
                        }
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
                    if (doc?.getString("status") == "ENDED" && joinedSessionId == ref.id) {
                        listening?.stop()
                        listening = null
                        joinedSessionId = null
                        status = "Stream interrupted; checking for a new owner-approved session."
                    }
                }
                listening = ListeningSession(peer, listOf(ice, sessionMonitor))
                joinedSessionId = ref.id
                peer.receiveOffer(offer) { answer ->
                    ref.update("answer", answer, "status", "ANSWERED").addOnFailureListener { error ->
                        (context as android.app.Activity).runOnUiThread { status = "Could not publish WebRTC answer: ${error.localizedMessage}" }
                    }
                }
                status = "Connecting to Collector live microphone…"
            } catch (e: Exception) {
                newPeer?.close()
                listening?.stop()
                listening = null
                joinedSessionId = null
                status = "Listening failed: ${e.localizedMessage}"
            } finally { busy = false }
        }

    }

    // Dispose only the session owned by this effect instance. Reading mutable
    // listening state in onDispose closed a newly connected WebRTC peer.
    LaunchedEffect(preferredDeviceId, devices) {
        val target = preferredDeviceId?.takeIf { id -> devices.any { it.first == id } }
            ?: devices.firstOrNull()?.first
        if (deviceId != target) {
            listening?.stop()
            listening = null
            reconnectUntil = 0L
            joinedSessionId = null
            availableRef = null
            availableId = null
            deviceId = target
            status = "Checking live audio for selected phone."
        }
    }
    LaunchedEffect(status) {
        onStatus(status)
    }
    // Subscribe to active offers rather than requiring the user to refresh repeatedly.
    DisposableEffect(deviceId, reconnectUntil) {
        val id = deviceId
        if (id == null) {
            onDispose { }
        } else {
            val subscription = db.collection("devices").document(id)
                .collection("liveAudio")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(8)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status = "Live-session query failed: ${error.localizedMessage}"
                    } else if (listening == null) {
                        val doc = snapshot?.documents?.firstOrNull {
                            it.getString("status") == "OFFERED" &&
                                (System.currentTimeMillis() -
                                    (it.getTimestamp("createdAt")?.toDate()?.time ?: 0L) < 10 * 60_000L)
                        }
                        if (doc != null) {
                            availableRef = doc.reference
                            availableId = doc.id
                            status = "Personal_App microphone is ready. Tap Listen now."
                            if (System.currentTimeMillis() < reconnectUntil && !busy) {
                                joinSession(doc.reference, automatic = true)
                            }
                        } else {
                            availableRef = null
                            availableId = null
                            status = "No live microphone session. Start one on Collector first."
                        }
                    }
                }
            onDispose { subscription.remove() }
        }
    }

    DisposableEffect(listening) {
        val currentSession = listening
        onDispose { currentSession?.stop() }
    }

    // Output controls belong exclusively to the selected incoming Admin stream.
    // Switching devices disconnects the prior session before applying these.
    LaunchedEffect(listening, playbackMuted, speakerEnabled) {
        listening?.peer?.setPlaybackMuted(playbackMuted)
        if (listening != null) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val outputType = if (speakerEnabled)
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val target = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == outputType }
                    if (target != null) audioManager.setCommunicationDevice(target)
                    else audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = speakerEnabled
                }
            } catch (e: Exception) {
                status = "Audio output routing: ${e.localizedMessage}"
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    LaunchedEffect(listening) {
        val activeSession = listening
        audioLevel = 0f
        while (activeSession != null && isActive) {
            activeSession.peer.sampleAudioLevel { sample ->
                (context as android.app.Activity).runOnUiThread { audioLevel = sample }
            }
            delay(250L)
        }
        audioLevel = 0f
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Listen live", style = MaterialTheme.typography.titleLarge)
            Text("Connect after the Collector user starts audio. This app cannot turn on their microphone.")
            Text(
                "Phone: ${devices.firstOrNull { it.first == deviceId }?.second ?: "Choose a linked phone above"}",
                style = MaterialTheme.typography.bodyMedium
            )
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
            ) { Text(if (busy) "Checking…" else "Refresh session") }
            if (availableId != null && listening == null) {
                Button(enabled = !busy, onClick = {
                    val ref = availableRef ?: return@Button
                    joinSession(ref, automatic = false)
                }) { Text("Listen now") }
            }
            if (listening != null) {
                Button(onClick = {
                    listening?.stop()
                    listening = null
                    reconnectUntil = 0L
                    joinedSessionId = null
                    availableRef = null
                    availableId = null
                    status = "Disconnected from audio."
                }) { Text("Disconnect") }
            }

            if (listening != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Loudspeaker", style = MaterialTheme.typography.titleSmall)
                        Text(if (speakerEnabled) "Speaker output" else "Earpiece / default output",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = speakerEnabled,
                        onCheckedChange = { speakerEnabled = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Mute this phone", style = MaterialTheme.typography.titleSmall)
                        Text("Only mutes playback in Admin_App. Personal_App keeps its own controls.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = playbackMuted,
                        onCheckedChange = { playbackMuted = it }
                    )
                }
            }

            AudioLevelMeter(
                label = "Incoming sound • live volume",
                level = audioLevel,
                active = listening != null
            )
            Text(
                "A moving bar shows received WebRTC audio activity. If connected but flat, " +
                    "check the Personal_App input meter, microphone permission and speaker volume.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(status)
            Text("If this active owner-started session is interrupted, Admin will attempt to reconnect for up to 2 minutes. Select another phone above to listen to it. TURN relay may be needed on mobile networks.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

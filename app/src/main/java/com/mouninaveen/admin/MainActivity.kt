package com.mouninaveen.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { AdminScreen() } } }
}
data class DeviceRow(val id:String, val name:String, val collectorUid:String, val lastSync:String)
data class EventRow(val id:String,val app:String,val pkg:String,val type:String,val time:Long)

@Composable private fun AdminScreen() {
    val auth=FirebaseAuth.getInstance(); val db=FirebaseFirestore.getInstance(); val scope= rememberCoroutineScope(); val context= LocalContext.current
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var signed by remember { mutableStateOf(auth.currentUser!=null) }; var status by remember { mutableStateOf("Sign in with an administrator account provisioned in Firebase Authentication.") }
    var devices by remember { mutableStateOf(listOf<DeviceRow>()) }; var selected by remember { mutableStateOf<DeviceRow?>(null) }; var events by remember { mutableStateOf(listOf<EventRow>()) }
    var collectorUid by remember { mutableStateOf("") }; var displayName by remember { mutableStateOf("Collector phone") }; var busy by remember { mutableStateOf(false) }
    fun loadDevices() { scope.launch { busy=true; try { val uid=auth.currentUser?.uid ?: error("Sign in required"); val snap=db.collection("devices").whereEqualTo("adminUid",uid).get().await(); devices=snap.documents.map { DeviceRow(it.id,it.getString("displayName")?:"Collector",it.getString("collectorUid")?:"",it.getTimestamp("lastSyncAt")?.toDate()?.toString()?:"Never") }; status="${devices.size} enrolled device(s)." } catch(e:Exception){status="Could not load devices: ${e.localizedMessage}"} finally {busy=false} } }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("MouniNaveen Admin", style=MaterialTheme.typography.headlineSmall)
        if(!signed) {
            OutlinedTextField(email,{email=it},label={Text("Administrator email")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(enabled=email.isNotBlank()&&password.isNotBlank()&&!busy,onClick={busy=true;auth.signInWithEmailAndPassword(email.trim(),password).addOnCompleteListener { busy=false; if(it.isSuccessful){signed=true;status="Authenticated. Loading devices…";loadDevices()}else status="Sign-in failed: ${it.exception?.localizedMessage}" }}) { Text("Sign in") }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={loadDevices()},enabled=!busy){Text("Refresh")}; OutlinedButton(onClick={auth.signOut();signed=false;devices= emptyList();selected=null;events= emptyList()}){Text("Sign out")} }
            Text("Enroll a Collector",style=MaterialTheme.typography.titleMedium)
            Text("Enter the Collector Firebase Auth UID (not its password). Create its account first in Firebase Console → Authentication → Users.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(displayName,{displayName=it},label={Text("Device display name")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(collectorUid,{collectorUid=it},label={Text("Collector UID")},modifier=Modifier.fillMaxWidth())
            Button(enabled=collectorUid.isNotBlank()&&!busy,onClick={scope.launch { busy=true; try { val uid=auth.currentUser!!.uid; val id=java.util.UUID.randomUUID().toString(); db.collection("devices").document(id).set(mapOf("adminUid" to uid,"collectorUid" to collectorUid.trim(),"displayName" to displayName.trim().ifBlank { "Collector phone" },"createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())).await(); collectorUid="";status="Device enrolled. Collector can now sign in.";loadDevices() } catch(e:Exception){status="Enrollment failed: ${e.localizedMessage}"} finally{busy=false} }}){Text("Enroll device")}
            HorizontalDivider(); Text("Enrolled devices",style=MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) {
                items(devices){ d -> Card(onClick={selected=d;scope.launch { try { val s=db.collection("devices").document(d.id).collection("usageEvents").orderBy("eventTimestamp",Query.Direction.DESCENDING).limit(500).get().await(); events=s.documents.map { EventRow(it.id,it.getString("applicationLabel")?:"",it.getString("packageName")?:"",it.getString("eventType")?:"",it.getTimestamp("eventTimestamp")?.toDate()?.time?:0L) }; status="Loaded ${events.size} event(s). This is collected history, not live monitoring." }catch(e:Exception){status="Unable to load events: ${e.localizedMessage}"} } }, modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)) { Column(Modifier.padding(12.dp)){Text(d.name,style=MaterialTheme.typography.titleMedium);Text("ID: ${d.id}",style=MaterialTheme.typography.bodySmall);Text("Last sync: ${d.lastSync}",style=MaterialTheme.typography.bodySmall)} } }
            }
            selected?.let { d ->
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text("Activity: ${d.name}",style=MaterialTheme.typography.titleMedium); Spacer(Modifier.weight(1f)); TextButton(onClick={val csv=buildString { append("timestamp,event,app,package\n"); events.forEach { append("${Date(it.time)},${it.type},\"${it.app.replace("\"","\"\"")}\",${it.pkg}\n") } }; val i=Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT,csv); context.startActivity(Intent.createChooser(i,"Share activity CSV"))}){Text("Export CSV")} }
                LazyColumn(Modifier.weight(1f)) { items(events){ e -> Column(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text("${e.type} · ${e.app}");Text("${e.pkg} · ${if(e.time>0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date(e.time)) else "Unknown time"}",style=MaterialTheme.typography.bodySmall)};HorizontalDivider() } } }
            }
        }
        Text(status,style=MaterialTheme.typography.bodySmall)
        Text("Only records from devices enrolled to this administrator are shown. App usage history may be incomplete or delayed by Android.",style=MaterialTheme.typography.bodySmall)
    }
}

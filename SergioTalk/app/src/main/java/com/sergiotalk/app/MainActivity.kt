package com.sergiotalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergiotalk.app.call.CallState
import com.sergiotalk.app.data.Message
import com.sergiotalk.app.data.MessageStore
import com.sergiotalk.app.net.ConnectionState
import com.sergiotalk.app.net.InternetManager
import java.text.SimpleDateFormat
import java.util.*

private const val EVERYONE_ID = "ALL"

class MainActivity : ComponentActivity() {

    private val myId = UUID.randomUUID().toString().take(8)
    private val myName = "User-$myId"
    private val store = MessageStore()
    private lateinit var internetManager: InternetManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* mic permission result - InternetManager checks before recording, nothing to kick off here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        internetManager = InternetManager(myId, myName, store)

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("chat") } // "chat" or "call"

                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = if (screen == "chat") 0 else 1) {
                        Tab(selected = screen == "chat", onClick = { screen = "chat" }, text = { Text("Chat") })
                        Tab(selected = screen == "call", onClick = { screen = "call" }, text = { Text("Call") })
                    }

                    if (screen == "chat") {
                        ChatScreen(myId = myId, myName = myName, store = store, internetManager = internetManager)
                    } else {
                        CallScreen(internetManager = internetManager, store = store)
                    }
                }
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::internetManager.isInitialized) internetManager.disconnect()
    }
}

@Composable
fun ChatScreen(
    myId: String,
    myName: String,
    store: MessageStore,
    internetManager: InternetManager
) {
    val allMessages by store.messages.collectAsState()
    val contacts by store.contacts.collectAsState() // id -> name, discovered via the relay's presence broadcasts
    val connectionState by internetManager.connectionState.collectAsState()

    var selectedContactId by remember { mutableStateOf(EVERYONE_ID) }
    var input by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val visibleMessages = remember(allMessages, selectedContactId) {
        if (selectedContactId == EVERYONE_ID) {
            allMessages.filter { it.destinationId == EVERYONE_ID }
        } else {
            allMessages.filter {
                (it.senderId == myId && it.destinationId == selectedContactId) ||
                    (it.senderId == selectedContactId && it.destinationId == myId)
            }
        }
    }

    val connectionLabel = when (connectionState) {
        is ConnectionState.Disconnected -> "disconnected"
        is ConnectionState.Connecting -> "connecting…"
        is ConnectionState.Connected -> "connected"
        is ConnectionState.Error -> "error"
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "Sergio Talk (you: $myName) — $connectionLabel",
            style = MaterialTheme.typography.titleMedium
        )

        if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Enter your relay server address (same one on both phones). Each of you uses " +
                    "your own internet connection (Wi-Fi or mobile data) to reach it.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("wss://your-relay.onrender.com") }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = { internetManager.connect(serverUrl.trim()) }, enabled = serverUrl.isNotBlank()) {
                    Text("Connect")
                }
            }
            if (connectionState is ConnectionState.Connecting) {
                Text(
                    "Connecting… free relay hosting can take 30-60s to wake up if it's been idle.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (connectionState is ConnectionState.Error) {
                Text(
                    "Couldn't connect: ${(connectionState as ConnectionState.Error).message}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = selectedContactId == EVERYONE_ID,
                    onClick = { selectedContactId = EVERYONE_ID },
                    label = { Text("Everyone") }
                )
            }
            items(contacts.entries.toList()) { (id, name) ->
                FilterChip(
                    selected = selectedContactId == id,
                    onClick = { selectedContactId = id },
                    label = { Text(name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedContactId != EVERYONE_ID) {
            Text(
                text = "Private chat — relayed through the server, not encrypted yet",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleMessages) { msg: Message ->
                val mine = msg.senderId == myId
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = "${if (mine) "You" else msg.senderName} · ${timeFormat.format(Date(msg.timestamp))}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                        Text(text = msg.text, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (selectedContactId == EVERYONE_ID) "Message everyone..." else "Message ${contacts[selectedContactId]}...")
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (input.isNotBlank()) {
                    val message = Message(
                        senderId = myId, senderName = myName,
                        destinationId = selectedContactId, text = input.trim()
                    )
                    store.offer(message)
                    store.display(message)
                    internetManager.relay(message)
                    input = ""
                }
            }) {
                Text("Send")
            }
        }
    }
}

@Composable
fun CallScreen(
    internetManager: InternetManager,
    store: MessageStore
) {
    val callState by internetManager.callState.collectAsState()
    val muted by internetManager.muted.collectAsState()
    val contacts by store.contacts.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // An incoming call takes priority in the UI, since it needs a response.
        val ringing = callState as? CallState.Ringing
        if (ringing != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${ringing.fromName} is calling…", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Button(onClick = { internetManager.answerCall(true) }) { Text("Accept") }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(onClick = { internetManager.answerCall(false) }) { Text("Decline") }
                }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }

        when (val state = callState) {
            is CallState.InCall -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("In call with ${state.peerName}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(onClick = { internetManager.toggleMute() }) { Text(if (muted) "Unmute" else "Mute") }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = { internetManager.hangUp() }) { Text("Hang up") }
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }
            is CallState.Connecting -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Calling…")
                    Button(onClick = { internetManager.hangUp() }) { Text("Cancel") }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }
            else -> {}
        }

        Text("Call over internet", style = MaterialTheme.typography.titleSmall)
        Text(
            "Works from anywhere, as long as both of you are connected via the relay " +
                "(set that up on the Chat tab first)",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts.entries.toList()) { (id, name) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name)
                    Button(
                        onClick = { internetManager.callPeer(id) },
                        enabled = callState == CallState.Idle
                    ) {
                        Text("Call")
                    }
                }
            }
        }
    }
}

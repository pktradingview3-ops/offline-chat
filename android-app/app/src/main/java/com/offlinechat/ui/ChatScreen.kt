package com.offlinechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.offlinechat.chat.ChatEngine
import com.offlinechat.model.ChatMessage
import com.offlinechat.model.Peer
import com.offlinechat.model.PeerStatus

@Composable
fun ChatScreen(engine: ChatEngine) {
    var activePeer by remember { mutableStateOf("broadcast") }

    val peers by engine.peers.collectAsState()
    val messages by engine.messages.collectAsState()
    val connected by engine.connected.collectAsState()
    val statusText by engine.statusText.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar
        Column(
            modifier = Modifier
                .width(140.dp)
                .fillMaxSize()
                .background(Color(0xFF111827))
                .padding(8.dp)
        ) {
            Text("Aap:", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            Text(engine.myId, color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (connected) "Online" else statusText,
                color = if (connected) Color(0xFF22C55E) else Color(0xFFF59E0B),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            PeerRow(
                label = "# Group",
                selected = activePeer == "broadcast",
                dotColor = Color(0xFF22C55E),
                onClick = { activePeer = "broadcast" }
            )

            LazyColumn {
                items(peers) { peer ->
                    PeerRow(
                        label = peer.id,
                        selected = activePeer == peer.id,
                        dotColor = peer.statusColor(),
                        onClick = { activePeer = peer.id }
                    )
                }
            }
        }

        // Chat area
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
            Text(
                text = if (activePeer == "broadcast") "# Group Chat" else activePeer,
                color = Color.White,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )

            val filtered = messages.filter { m ->
                if (activePeer == "broadcast") m.recipientId == "broadcast"
                else m.recipientId != "broadcast" && (m.senderId == activePeer || m.recipientId == activePeer)
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Top
            ) {
                items(filtered) { message -> MessageBubble(message) }
            }

            Composer(onSend = { text -> engine.sendMessage(text, activePeer) })
        }
    }
}

@Composable
private fun PeerRow(label: String, selected: Boolean, dotColor: Color, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .background(if (selected) Color(0xFF1E40AF) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.self) Alignment.End else Alignment.Start
    val bubbleColor = if (message.self) Color(0xFF2563EB) else Color(0xFF1E293B)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        if (!message.self) {
            Text(message.senderId, color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(message.text, color = Color.White)
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message likhein...") }
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                onSend(trimmed)
                text = ""
            }
        }) {
            Text("Bhejein")
        }
    }
}

private fun Peer.statusColor(): Color = when (status) {
    PeerStatus.ONLINE -> Color(0xFF22C55E)
    PeerStatus.CONNECTING -> Color(0xFFF59E0B)
    PeerStatus.OFFLINE -> Color(0xFF64748B)
}

@Composable
fun SetupScreen(onConnect: (String) -> Unit, onAuto: () -> Unit) {
    var urlInput by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Offline Chat", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Automatic mode: app khud is LAN par koi host dhundhega, nahi mila to khud host ban jaayega.",
            color = Color(0xFF94A3B8),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAuto, modifier = Modifier.fillMaxWidth()) {
            Text("Automatic Start (recommended)")
        }
        Spacer(Modifier.height(24.dp))
        Text("Ya manually signaling URL daalein:", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("ws://192.168.1.5:8080") }
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { if (urlInput.isNotBlank()) onConnect(urlInput) }, modifier = Modifier.fillMaxWidth()) {
            Text("Manually Connect")
        }
    }
}

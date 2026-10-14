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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinechat.chat.ChatEngine
import com.offlinechat.model.ChatMessage
import com.offlinechat.model.Peer
import com.offlinechat.model.PeerStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * PTT mic button — press-and-hold to record, release to send.
 *
 * `awaitEachGesture` me parent scroll se gesture cancel ho jaata tha, isliye
 * down/up manually track karte hain. Down consume karke scroll se bhi bach-te hain.
 */
@Composable
private fun PttMicButton(
    isRecording: Boolean,
    duration: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var pressing by remember { mutableStateOf(false) }

    androidx.compose.material3.IconButton(
        onClick = { },
        interactionSource = interaction,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (isRecording) Palette.NeonRed else Palette.Surface3,
                CircleShape
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressing = true
                    onStart()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) break
                    }
                    pressing = false
                    onStop()
                }
            }
    ) {
        Text(
            text = if (isRecording) "⏺" else "🎤",
            fontSize = 20.sp
        )
    }
}

@Composable
fun ChatScreen(engine: ChatEngine) {
    // rememberSaveable, not remember: a plain `remember` loses the open conversation on
    // every rotation, dropping the user back to # Group mid-chat.
    var activePeer by rememberSaveable { mutableStateOf("broadcast") }

    val peers by engine.peers.collectAsState()
    val messages by engine.messages.collectAsState()
    val connected by engine.connected.collectAsState()
    val statusText by engine.statusText.collectAsState()
    val busy by engine.busy.collectAsState()

    // If the peer we're viewing leaves the network, fall back to the group channel
    // rather than leaving the composer wired to a connection that no longer exists.
    LaunchedEffect(peers, activePeer) {
        if (activePeer != "broadcast" && peers.none { it.id == activePeer }) {
            activePeer = "broadcast"
        }
    }

    val filtered = messages.filter { m ->
        if (activePeer == "broadcast") m.recipientId == "broadcast"
        else m.recipientId != "broadcast" && (m.senderId == activePeer || m.recipientId == activePeer)
    }

    val listState = rememberLazyListState()
    // Without this the newest message renders below the fold and the user has to scroll
    // down by hand after every single message — the app reads as broken even when the
    // transport is fine.
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar. Kept narrow on purpose: the test device is 720 px wide, and a
        // 140 dp sidebar took almost half the screen and squeezed the chat area so
        // hard that text wrapped one character per line.
        Column(
            modifier = Modifier
                .width(104.dp)
                .fillMaxSize()
                .background(Color(0xFF111827))
                .padding(8.dp)
        ) {
            Text("Aap:", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            Text(
                engine.myId,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(
                            when {
                                connected -> Color(0xFF22C55E)
                                busy -> Color(0xFFF59E0B)
                                else -> Color(0xFFEF4444)
                            },
                            CircleShape
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        connected -> "Online"
                        busy -> "Koshish jari..."
                        else -> "Offline"
                    },
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // The engine's status text carries the actual reason (retry countdown, host
            // re-election, actionable error). Hiding it behind "Online" threw away the
            // only diagnostic the user had.
            Text(
                text = statusText,
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))

            PeerRow(
                label = "# Group",
                selected = activePeer == "broadcast",
                dotColor = Color(0xFF22C55E),
                onClick = { activePeer = "broadcast" }
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(peers, key = { it.id }) { peer ->
                    PeerRow(
                        label = peer.id,
                        selected = activePeer == peer.id,
                        dotColor = peer.statusColor(),
                        onClick = { activePeer = peer.id }
                    )
                }
            }

            TextButton(onClick = { engine.restart() }, modifier = Modifier.fillMaxWidth()) {
                Text("Dobara Scan", color = Color(0xFF93C5FD), style = MaterialTheme.typography.labelSmall)
            }
        }

        // Chat area
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = if (activePeer == "broadcast") "# Group Chat" else activePeer,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                val onlineCount = peers.count { it.status == PeerStatus.ONLINE }
                Text(
                    text = if (activePeer == "broadcast") {
                        "${peers.size} peer · $onlineCount online"
                    } else {
                        when (peers.firstOrNull { it.id == activePeer }?.status) {
                            PeerStatus.ONLINE -> "online — seedha encrypted connection"
                            PeerStatus.CONNECTING -> "connection ban raha hai..."
                            else -> "offline"
                        }
                    },
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (peers.isEmpty()) {
                            "Koi peer nahi mila abhi.\nSame Wi-Fi par doosra device kholiye."
                        } else {
                            "Koi message nahi.\nNeeche likhkar bhejein."
                        },
                        color = Color(0xFF475569),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    items(filtered, key = { it.id }) { message -> MessageBubble(message) }
                }
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
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                color = Color(0xFF475569),
                style = MaterialTheme.typography.labelSmall
            )
            // An undelivered bubble that looks delivered is worse than no bubble at all:
            // the user believes the message landed and never retries.
            if (message.self && !message.delivered) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "· nahi bheja gaya",
                    color = Color(0xFFF87171),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message likhein...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        onSend(trimmed)
                        text = ""
                    }
                }
            )
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
    var urlInput by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Offline Chat", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Local network par encrypted chat — no internet, no account",
            color = Color(0xFF64748B),
            style = MaterialTheme.typography.bodySmall
        )
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
            placeholder = { Text("192.168.1.5  ya  ws://192.168.1.5:8080") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = { if (urlInput.isNotBlank()) onConnect(urlInput) }
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Port na likhne par :8080 apne aap lag jaayega.",
            color = Color(0xFF475569),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (urlInput.isNotBlank()) onConnect(urlInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = urlInput.isNotBlank()
        ) {
            Text("Manually Connect")
        }
    }
}

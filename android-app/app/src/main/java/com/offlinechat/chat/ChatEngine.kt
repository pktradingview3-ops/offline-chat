package com.offlinechat.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.crypto.CryptoBox
import com.offlinechat.discovery.NsdHelper
import com.offlinechat.identity.generateAnonId
import com.offlinechat.model.ChatMessage
import com.offlinechat.model.IceCandidatePayload
import com.offlinechat.model.Peer
import com.offlinechat.model.PeerStatus
import com.offlinechat.model.SdpPayload
import com.offlinechat.model.SignalMessage
import com.offlinechat.model.WireEnvelope
import com.offlinechat.signaling.SignalingClient
import com.offlinechat.signaling.SignalingServer
import com.offlinechat.webrtc.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private const val TAG = "ChatEngine"
private const val SIGNALING_PORT = 8080

/**
 * Discovery window is RANDOMISED on purpose (2.5s .. 6s).
 *
 * With a fixed 3s window, two phones opened at the same moment both scan, both
 * find nobody (neither is advertising yet), and both promote themselves to host
 * on port 8080 — two isolated islands that can never see each other, and the only
 * recovery is force-closing one app. With jitter, whoever draws the short window
 * becomes host and starts advertising while the other is *still scanning*, so the
 * second phone discovers it and joins as a client.
 */
private const val DISCOVERY_MIN_MS = 2500L
private const val DISCOVERY_JITTER_MS = 3500L

private const val MAX_RECONNECT_ATTEMPTS = 4
private const val BASE_BACKOFF_MS = 1000L
private const val MAX_BACKOFF_MS = 8000L

/** Cap on retained in-memory history. Nothing is persisted; this just bounds RAM. */
private const val MAX_MESSAGES = 1000

class ChatEngine(private val context: Context) : ViewModel() {

    val myId: String = generateAnonId()
    private val keyPair = CryptoBox.generateKeyPair()
    private val wireJson = Json { ignoreUnknownKeys = true }

    private enum class Role { NONE, HOST, CLIENT }

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _statusText = MutableStateFlow("Shuru nahi hua")
    val statusText: StateFlow<String> = _statusText

    /** True while a background reconnect/re-election is in flight — lets the UI say so. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private var signalingServer: SignalingServer? = null
    private var signalingClient: SignalingClient? = null
    private val nsdHelper = NsdHelper(context)

    @Volatile private var started = false
    @Volatile private var autoMode = false
    @Volatile private var role = Role.NONE
    @Volatile private var signalingUrl: String? = null
    @Volatile private var socketGeneration = 0
    @Volatile private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var scanJob: Job? = null

    private val messageSeq = AtomicLong(0)

    /**
     * Per-peer connection state.
     *
     * `pc` is deliberately NOT a constructor parameter: the `PeerConnection.Observer`
     * captures this object, and the observer must be constructed *before* the
     * PeerConnection it observes. The previous code used a `lateinit var entry`
     * captured by the observer and assigned afterwards, which meant any callback
     * that fired early would have crashed with UninitializedPropertyAccessException.
     * Creating the holder first makes that structurally impossible.
     */
    private class ConnEntry(val publicKey: ByteArray) {
        lateinit var pc: PeerConnection
        @Volatile var dc: DataChannel? = null

        /**
         * ICE candidates that arrived before `setRemoteDescription` completed.
         *
         * `addIceCandidate` before a remote description exists is rejected outright,
         * and a dropped host candidate on a LAN — where host candidates are the ONLY
         * candidates, there being no STUN/TURN — means the DataChannel never opens.
         * Signalling is asynchronous, so queue and drain instead of calling directly.
         */
        @Volatile var remoteDescriptionApplied = false
        val pendingCandidates = ArrayDeque<IceCandidate>()
    }

    private val conns = ConcurrentHashMap<String, ConnEntry>()
    private val publicKeys = ConcurrentHashMap<String, String>()

    init {
        WebRtcManager.init(context)
    }

    // ───────────────────────────────────────────────────────────── entry points

    /** Automatic mode: NSD se koi host dhundho, nahi mila to khud host ban jao. */
    fun start() {
        if (started) return
        started = true
        autoMode = true
        scanThenDecide()
    }

    /** Web app jaisa manual connect (testing / cross-device interop ke liye). */
    fun connectManually(rawUrl: String) {
        started = true
        autoMode = false
        stopHosting()
        scanJob?.cancel()
        nsdHelper.stopDiscovery()
        role = Role.CLIENT
        connectAsClient(normalizeSignalingUrl(rawUrl))
    }

    /** UI se "dobara koshish karein" — poora role-detection phir se chalata hai. */
    fun restart() {
        reconnectJob?.cancel()
        reconnectJob = null
        scanJob?.cancel()
        reconnectAttempts = 0
        closeAllConnections()
        stopHosting()
        runCatching { signalingClient?.close() }
        signalingClient = null
        socketGeneration++ // retire every in-flight callback from the old socket
        _connected.value = false
        _peers.value = emptyList()
        publicKeys.clear()
        started = false
        start()
    }

    // ────────────────────────────────────────────────────────── role detection

    /**
     * Runs entirely on [Dispatchers.IO]: it enumerates network interfaces, may bind a
     * listening socket (`NanoHTTPD.start` blocks in a poll loop until the bind
     * succeeds), and talks to NsdManager over binder. Doing any of that on the main
     * thread risks an ANR on a slow bind.
     */
    private fun scanThenDecide() {
        _busy.value = true
        _statusText.value = "Network par host dhundha ja raha hai..."

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val resolved = AtomicBoolean(false)
            val windowMs = DISCOVERY_MIN_MS + Random.nextLong(DISCOVERY_JITTER_MS)
            val myAddresses = localAddresses()

            nsdHelper.stopDiscovery()
            nsdHelper.discoverServices { info ->
                val host = info.host?.hostAddress ?: return@discoverServices
                // Ignore our own advertisement — otherwise a phone that is already
                // hosting would "discover" itself and connect to itself as a client.
                if (host in myAddresses) return@discoverServices
                if (!resolved.compareAndSet(false, true)) return@discoverServices

                nsdHelper.stopDiscovery()
                scanJob?.cancel()
                _busy.value = false
                role = Role.CLIENT
                Log.i(TAG, "Host discovered at $host:${info.port} — joining as client")
                connectAsClient("ws://$host:${info.port}")
            }

            delay(windowMs)
            if (!isActive) return@launch
            if (resolved.compareAndSet(false, true)) {
                nsdHelper.stopDiscovery()
                becomeHost()
            }
        }
    }

    private fun becomeHost() {
        _busy.value = false
        _statusText.value = "Koi host nahi mila — khud host ban raha hai (port $SIGNALING_PORT)..."
        role = Role.HOST
        try {
            val server = SignalingServer(SIGNALING_PORT)
            // startServer(), NOT start(): start() implies a 5s socket read timeout that
            // force-closes every idle peer. See SignalingServer for the full story.
            server.startServer()
            signalingServer = server
            nsdHelper.advertiseService(SIGNALING_PORT)
            Log.i(TAG, "Became host on port $SIGNALING_PORT")
            // Connect to our own server over loopback so host and peer run the exact
            // same code path — no special-casing, one chat implementation for both roles.
            connectAsClient("ws://127.0.0.1:$SIGNALING_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Could not become host", e)
            role = Role.NONE
            runCatching { signalingServer?.stop() }
            signalingServer = null
            nsdHelper.stopAdvertising()
            _statusText.value = "Host nahi ban paaye (port $SIGNALING_PORT shayad busy hai): " +
                "${e.message}. Neeche signaling URL daal kar \"Manually Connect\" try karein."
        }
    }

    private fun stopHosting() {
        if (signalingServer == null && role != Role.HOST) return
        nsdHelper.stopAdvertising()
        runCatching { signalingServer?.stop() }
        signalingServer = null
        if (role == Role.HOST) role = Role.NONE
    }

    // ──────────────────────────────────────────────────────────── signaling I/O

    private fun connectAsClient(url: String) {
        signalingUrl = url
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        openSignalingSocket(url)
    }

    /**
     * Opens (or re-opens) the signaling WebSocket.
     *
     * Every socket gets a monotonically increasing generation number, and callbacks
     * from a stale generation are dropped. Without that, closing the old socket on
     * reconnect fires its own onFailure, which schedules another reconnect, which
     * closes another socket — a self-sustaining retry storm.
     */
    private fun openSignalingSocket(url: String) {
        val generation = ++socketGeneration
        runCatching { signalingClient?.close() }

        _statusText.value = if (role == Role.HOST) {
            "Host ready — apne aap se connect ho raha hai..."
        } else {
            "Signaling se connect ho raha hai: $url"
        }

        val client = SignalingClient(
            onMessage = { msg -> if (generation == socketGeneration) handleSignal(msg) },
            onStateChange = { isOpen -> if (generation == socketGeneration) onSignalingState(isOpen, url) }
        )
        signalingClient = client
        client.connect(url)
    }

    private fun onSignalingState(isOpen: Boolean, url: String) {
        if (isOpen) {
            _connected.value = true
            reconnectAttempts = 0
            _busy.value = false
            _statusText.value = when (role) {
                Role.HOST -> "Host ready — doosre devices isi LAN par jud sakte hain"
                Role.CLIENT -> "Connected"
                Role.NONE -> "Connected"
            }
            // Announce ourselves. On a *re*connect the server has already forgotten us
            // (it evicts on socket close), so skipping this would leave us invisible:
            // connected, but never appearing in anyone's peer list.
            signalingClient?.send(
                SignalMessage(
                    type = "join",
                    peerId = myId,
                    publicKey = CryptoBox.publicKeyToBase64(keyPair.publicKey)
                )
            )
        } else {
            _connected.value = false
            // NOTE: peer statuses are intentionally left alone. The DataChannels are
            // independent of the signaling socket, so an already-established chat keeps
            // working while signaling is down; ICE state changes drive those statuses.
            scheduleReconnect(url)
        }
    }

    private fun scheduleReconnect(url: String) {
        if (reconnectJob?.isActive == true) return

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts = 0
            if (autoMode && role == Role.CLIENT) {
                // HOST RE-ELECTION. The host is really gone; sitting on a dead socket
                // forever is worse than trying to take over. Everyone re-scans first,
                // so if another phone already won the election we just join it.
                Log.i(TAG, "Signaling unreachable after $MAX_RECONNECT_ATTEMPTS tries — re-electing")
                _busy.value = true
                _statusText.value = "Host chala gaya — naya host dhundha ja raha hai..."
                signalingClient = null
                socketGeneration++
                // Stagger the re-scan so several orphaned clients don't all promote at once.
                val stagger = Random.nextLong(1500L)
                viewModelScope.launch {
                    delay(stagger)
                    if (isActive) scanThenDecide()
                }
            } else {
                _busy.value = false
                _statusText.value = when (role) {
                    Role.HOST -> "Apna embedded server se connect nahi ho paaya. App dobara kholkar " +
                        "\"Automatic Start\" try karein."
                    else -> "Signaling node se connect nahi ho paaya ($url). Check karein ki dono " +
                        "devices same Wi-Fi par hain aur host app abhi bhi khula hai. URL galat ho to " +
                        "\"Dobara Scan\" se sahi karein."
                }
            }
            return
        }

        val backoffMs = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS * reconnectAttempts)
        _busy.value = true
        _statusText.value = "Connection toot gaya — ${(backoffMs / 1000.0)}s me dobara koshish " +
            "($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)..."
        reconnectJob = viewModelScope.launch {
            delay(backoffMs)
            reconnectJob = null
            if (isActive) openSignalingSocket(url)
        }
    }

    // ─────────────────────────────────────────────────────── signaling protocol

    private fun handleSignal(msg: SignalMessage) {
        when (msg.type) {
            "peer-list" -> {
                val list = msg.peers.orEmpty()
                list.forEach { publicKeys[it.peerId] = it.publicKey }
                _peers.update { prev ->
                    val ids = prev.map { it.id }.toSet()
                    prev + list.filter { it.peerId !in ids }.map { Peer(it.peerId, it.publicKey) }
                }
                // Main naya peer hu — maujooda sabhi peers ko main hi offer bhejunga.
                // (Server deliberately tells the others only "peer-joined", so exactly
                // one side ever creates an offer — no glare, no duplicate connections.)
                list.forEach { p ->
                    if (!conns.containsKey(p.peerId)) createConnection(p.peerId, p.publicKey, initiator = true)
                }
            }

            "peer-joined" -> {
                val id = msg.peerId ?: return
                val pk = msg.publicKey ?: return
                publicKeys[id] = pk
                _peers.update { prev -> if (prev.any { it.id == id }) prev else prev + Peer(id, pk) }
                // Yahan kuch nahi karte — naya peer khud offer bhejega, hum wait karte hain.
            }

            "offer" -> {
                val from = msg.from ?: return
                val sdp = msg.sdp ?: return
                val entry = conns[from]
                    ?: publicKeys[from]?.let { pk -> createConnection(from, pk, initiator = false) }
                    ?: return // publicKey abhi tak nahi mila — safe ignore

                entry.pc.setRemoteDescription(object : NoopSdpObserver() {
                    override fun onSetSuccess() {
                        entry.remoteDescriptionApplied = true
                        drainPendingCandidates(from, entry)
                        entry.pc.createAnswer(object : NoopSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                entry.pc.setLocalDescription(NoopSdpObserver(), desc)
                                signalingClient?.send(
                                    SignalMessage(
                                        type = "answer",
                                        from = myId,
                                        to = from,
                                        sdp = SdpPayload("answer", desc.description)
                                    )
                                )
                            }

                            override fun onCreateFailure(err: String?) {
                                Log.w(TAG, "createAnswer failed for $from: $err")
                            }
                        }, dataChannelConstraints())
                    }

                    override fun onSetFailure(err: String?) {
                        Log.w(TAG, "setRemoteDescription(offer) failed for $from: $err")
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp.sdp))
            }

            "answer" -> {
                val from = msg.from ?: return
                val sdp = msg.sdp ?: return
                val entry = conns[from] ?: return
                entry.pc.setRemoteDescription(object : NoopSdpObserver() {
                    override fun onSetSuccess() {
                        entry.remoteDescriptionApplied = true
                        drainPendingCandidates(from, entry)
                    }

                    override fun onSetFailure(err: String?) {
                        Log.w(TAG, "setRemoteDescription(answer) failed for $from: $err")
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp.sdp))
            }

            "ice-candidate" -> {
                val from = msg.from ?: return
                val c = msg.candidate ?: return
                val entry = conns[from] ?: return
                val candidate = IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate)
                // Queue until the remote description is in place; see ConnEntry.
                synchronized(entry) {
                    if (entry.remoteDescriptionApplied) {
                        entry.pc.addIceCandidate(candidate)
                    } else {
                        entry.pendingCandidates.addLast(candidate)
                    }
                }
            }

            "leave" -> {
                val id = msg.peerId ?: return
                conns.remove(id)?.let { entry ->
                    runCatching { entry.pc.close() }
                }
                publicKeys.remove(id)
                _peers.update { prev -> prev.filterNot { it.id == id } }
            }

            else -> Unit
        }
    }

    private fun drainPendingCandidates(peerId: String, entry: ConnEntry) {
        val queued = synchronized(entry) {
            val copy = entry.pendingCandidates.toList()
            entry.pendingCandidates.clear()
            copy
        }
        queued.forEach { runCatching { entry.pc.addIceCandidate(it) } }
        if (queued.isNotEmpty()) Log.d(TAG, "Drained ${queued.size} queued ICE candidates for $peerId")
    }

    // ─────────────────────────────────────────────────────────────── WebRTC P2P

    private fun createConnection(peerId: String, publicKeyB64: String, initiator: Boolean): ConnEntry {
        val publicKey = CryptoBox.base64ToPublicKey(publicKeyB64)

        // Holder first, PeerConnection second — the observer below captures `entry`.
        val entry = ConnEntry(publicKey)
        conns[peerId] = entry
        updatePeerStatus(peerId, PeerStatus.CONNECTING)

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> updatePeerStatus(peerId, PeerStatus.OFFLINE)

                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> updatePeerStatus(peerId, PeerStatus.ONLINE)

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient?.send(
                    SignalMessage(
                        type = "ice-candidate",
                        from = myId,
                        to = peerId,
                        candidate = IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel) {
                entry.dc = dc
                setupDataChannel(peerId, dc)
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }

        val pc = WebRtcManager.createPeerConnection(observer)
        if (pc == null) {
            conns.remove(peerId)
            Log.e(TAG, "createPeerConnection returned null for $peerId")
            updatePeerStatus(peerId, PeerStatus.OFFLINE)
            throw IllegalStateException("PeerConnection banane me nakaam — WebRtcManager.init() call hua?")
        }
        entry.pc = pc

        if (initiator) {
            val dc = pc.createDataChannel("chat", DataChannel.Init())
            entry.dc = dc
            setupDataChannel(peerId, dc)
            pc.createOffer(object : NoopSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(NoopSdpObserver(), desc)
                    signalingClient?.send(
                        SignalMessage(
                            type = "offer",
                            from = myId,
                            to = peerId,
                            sdp = SdpPayload("offer", desc.description)
                        )
                    )
                }

                override fun onCreateFailure(err: String?) {
                    Log.w(TAG, "createOffer failed for $peerId: $err")
                    updatePeerStatus(peerId, PeerStatus.OFFLINE)
                }
            }, dataChannelConstraints())
        }

        return entry
    }

    private fun setupDataChannel(peerId: String, dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> updatePeerStatus(peerId, PeerStatus.ONLINE)
                    DataChannel.State.CLOSED -> updatePeerStatus(peerId, PeerStatus.OFFLINE)
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val envelope = runCatching { wireJson.decodeFromString(WireEnvelope.serializer(), text) }.getOrNull()
                    ?: return
                val entry = conns[peerId] ?: return
                // Returns null on tampered/corrupt data (GCM auth tag mismatch) or a
                // key mismatch — dropped silently, exactly like the web client.
                val plain = CryptoBox.decryptMessage(
                    envelope.nonce, envelope.ciphertext, entry.publicKey, keyPair.secretKey
                ) ?: return

                appendMessage(
                    ChatMessage(
                        id = "rx-${messageSeq.incrementAndGet()}",
                        senderId = peerId,
                        recipientId = envelope.recipientId,
                        timestamp = envelope.timestamp,
                        text = plain,
                        self = false,
                        delivered = true
                    )
                )
            }
        })
    }

    private fun updatePeerStatus(peerId: String, status: PeerStatus) {
        _peers.update { prev -> prev.map { if (it.id == peerId) it.copy(status = status) else it } }
    }

    private fun appendMessage(message: ChatMessage) {
        _messages.update { prev ->
            val next = prev + message
            if (next.size > MAX_MESSAGES) next.subList(next.size - MAX_MESSAGES, next.size) else next
        }
    }

    // ───────────────────────────────────────────────────────────────── sending

    /**
     * Encrypts once per recipient (each peer has a different shared secret) and sends
     * over that peer's DataChannel.
     *
     * Returns the number of peers the message actually went out to, so the UI can show
     * an honest "not delivered" instead of displaying a bubble that silently vanished.
     */
    fun sendMessage(text: String, recipientId: String): Int {
        if (text.isBlank()) return 0
        val timestamp = System.currentTimeMillis()

        val targets: List<Pair<String, ConnEntry>> =
            if (recipientId == "broadcast") conns.entries.map { it.key to it.value }
            else conns[recipientId]?.let { listOf(recipientId to it) } ?: emptyList()

        var sentCount = 0
        targets.forEach { (_, entry) ->
            val dc = entry.dc ?: return@forEach
            if (dc.state() != DataChannel.State.OPEN) return@forEach
            val (nonce, ciphertext) = CryptoBox.encryptMessage(text, entry.publicKey, keyPair.secretKey)
            val envelope = WireEnvelope(
                senderId = myId,
                recipientId = recipientId,
                timestamp = timestamp,
                nonce = nonce,
                ciphertext = ciphertext
            )
            val payload = wireJson.encodeToString(WireEnvelope.serializer(), envelope)
            // send() returns false if the channel closed or the buffer is full.
            if (dc.send(DataChannel.Buffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))) {
                sentCount++
            }
        }

        val delivered = if (recipientId == "broadcast") sentCount > 0 else sentCount == 1

        appendMessage(
            ChatMessage(
                // Sequence number, not just the timestamp: two sends inside the same
                // millisecond produced identical ids and collapsed in the message list.
                id = "tx-${messageSeq.incrementAndGet()}",
                senderId = myId,
                recipientId = recipientId,
                timestamp = timestamp,
                text = text,
                self = true,
                delivered = delivered
            )
        )
        return sentCount
    }

    // ─────────────────────────────────────────────────────────────── UI helpers

    /** Accepts "192.168.1.5", "192.168.1.5:9000", "ws://192.168.1.5" etc. */
    private fun normalizeSignalingUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) url = "ws://$url"
        val afterScheme = url.substringAfter("://")
        if (!afterScheme.contains(":")) url = "$url:$SIGNALING_PORT"
        return url
    }

    /** This device's own IPv4 addresses, used to ignore our own NSD advertisement. */
    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            iface.inetAddresses.toList().mapNotNull { it.hostAddress }
        }.toSet()
    }.getOrDefault(emptySet())

    private fun closeAllConnections() {
        conns.values.forEach { entry ->
            runCatching { entry.dc?.close() }
            runCatching { entry.pc.close() }
        }
        conns.clear()
    }

    override fun onCleared() {
        super.onCleared()
        socketGeneration++
        reconnectJob?.cancel()
        scanJob?.cancel()
        closeAllConnections()
        runCatching { signalingClient?.close() }
        runCatching { signalingServer?.stop() }
        nsdHelper.stopAdvertising()
        nsdHelper.stopDiscovery()
    }
}

/**
 * Data-channel-only SDP. Explicitly refusing audio and video keeps the offer to a
 * single `application` m-section, matching what the browser produces with a
 * trackless RTCPeerConnection — important because the web and Android clients must
 * generate SDP the other side can consume.
 */
private fun dataChannelConstraints(): MediaConstraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
}

/** SdpObserver ke boilerplate empty overrides ke liye chhota helper. */
private open class NoopSdpObserver : SdpObserver {
    // Parameter type must match the subclass overrides exactly (non-null SessionDescription),
    // otherwise Kotlin rejects the override: 'onCreateSuccess' overrides nothing.
    override fun onCreateSuccess(p0: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

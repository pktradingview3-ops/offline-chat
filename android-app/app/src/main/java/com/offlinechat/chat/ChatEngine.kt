package com.offlinechat.chat

import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

private const val SIGNALING_PORT = 8080
private const val DISCOVERY_TIMEOUT_MS = 3000L

class ChatEngine(private val context: Context) : ViewModel() {

    val myId: String = generateAnonId()
    private val keyPair = CryptoBox.generateKeyPair()
    private val wireJson = Json { ignoreUnknownKeys = true }

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _statusText = MutableStateFlow("Shuru nahi hua")
    val statusText: StateFlow<String> = _statusText

    private var signalingServer: SignalingServer? = null
    private var signalingClient: SignalingClient? = null
    private val nsdHelper = NsdHelper(context)
    private var started = false

    private class ConnEntry(val pc: PeerConnection, val publicKey: ByteArray) {
        var dc: DataChannel? = null
    }

    private val conns = ConcurrentHashMap<String, ConnEntry>()
    private val publicKeys = ConcurrentHashMap<String, String>()

    init {
        WebRtcManager.init(context)
    }

    /** Pehle NSD se koi existing host dhundhta hai; kuch second me na mile to khud host ban jaata hai. */
    fun start() {
        if (started) return
        started = true
        _statusText.value = "Network scan ho raha hai..."

        var resolved = false
        nsdHelper.discoverServices { serviceInfo ->
            if (resolved) return@discoverServices
            resolved = true
            nsdHelper.stopDiscovery()
            val host = serviceInfo.host?.hostAddress
            if (host != null) connectAsClient("ws://$host:${serviceInfo.port}")
        }

        viewModelScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (!resolved) {
                nsdHelper.stopDiscovery()
                becomeHost()
            }
        }
    }

    /** Web app jaisa manual connect bhi available hai (testing/advanced use ke liye). */
    fun connectManually(url: String) {
        started = true
        connectAsClient(url)
    }

    private fun becomeHost() {
        _statusText.value = "Host ban raha hai (port $SIGNALING_PORT)..."
        try {
            val server = SignalingServer(SIGNALING_PORT)
            server.start()
            signalingServer = server
            nsdHelper.advertiseService(SIGNALING_PORT)
            connectAsClient("ws://127.0.0.1:$SIGNALING_PORT")
        } catch (e: Exception) {
            _statusText.value = "Host start nahi ho paya: ${e.message}"
        }
    }

    private fun connectAsClient(url: String) {
        _statusText.value = "Connect ho raha hai: $url"
        val client = SignalingClient(
            onMessage = { msg -> handleSignal(msg) },
            onStateChange = { isOpen ->
                _connected.value = isOpen
                if (isOpen) {
                    _statusText.value = "Connected"
                    signalingClient?.send(
                        SignalMessage(
                            type = "join",
                            peerId = myId,
                            publicKey = CryptoBox.publicKeyToBase64(keyPair.publicKey)
                        )
                    )
                } else {
                    // A real, actionable reason instead of a bare "Disconnected" that
                    // leaves the user with nothing to do.
                    _statusText.value = "Host start nahi ho paya. Neeche apna signaling " +
                        "URL daal kar \"Manually Connect\" try karein."
                }
            }
        )
        signalingClient = client
        client.connect(url)
    }

    private fun handleSignal(msg: SignalMessage) {
        when (msg.type) {
            "peer-list" -> {
                val list = msg.peers.orEmpty()
                list.forEach { publicKeys[it.peerId] = it.publicKey }
                _peers.update { prev ->
                    val ids = prev.map { it.id }.toSet()
                    prev + list.filter { it.peerId !in ids }.map { Peer(it.peerId, it.publicKey) }
                }
                // Main naya peer hu — maujooda sabhi peers ko main hi offer bhejunga
                list.forEach { p ->
                    if (!conns.containsKey(p.peerId)) createConnection(p.peerId, p.publicKey, initiator = true)
                }
            }

            "peer-joined" -> {
                val id = msg.peerId ?: return
                val pk = msg.publicKey ?: return
                publicKeys[id] = pk
                _peers.update { prev -> if (prev.any { it.id == id }) prev else prev + Peer(id, pk) }
                // Yahan kuch nahi karte — naya peer khud offer bhejega, hum wait karte hain
            }

            "offer" -> {
                val from = msg.from ?: return
                val sdp = msg.sdp ?: return
                var entry = conns[from]
                if (entry == null) {
                    val pk = publicKeys[from] ?: return
                    entry = createConnection(from, pk, initiator = false)
                }
                val pc = entry.pc
                pc.setRemoteDescription(NoopSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp.sdp))
                pc.createAnswer(object : NoopSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(NoopSdpObserver(), desc)
                        signalingClient?.send(
                            SignalMessage(type = "answer", from = myId, to = from, sdp = SdpPayload("answer", desc.description))
                        )
                    }
                }, MediaConstraints())
            }

            "answer" -> {
                val from = msg.from ?: return
                val sdp = msg.sdp ?: return
                conns[from]?.pc?.setRemoteDescription(
                    NoopSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp.sdp)
                )
            }

            "ice-candidate" -> {
                val from = msg.from ?: return
                val c = msg.candidate ?: return
                conns[from]?.pc?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
            }

            "leave" -> {
                val id = msg.peerId ?: return
                conns.remove(id)?.pc?.close()
                publicKeys.remove(id)
                _peers.update { prev -> prev.filterNot { it.id == id } }
            }

            else -> Unit
        }
    }

    private fun createConnection(peerId: String, publicKeyB64: String, initiator: Boolean): ConnEntry {
        val publicKey = CryptoBox.base64ToPublicKey(publicKeyB64)
        lateinit var entry: ConnEntry

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    newState == PeerConnection.IceConnectionState.FAILED
                ) {
                    updatePeerStatus(peerId, PeerStatus.OFFLINE)
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
            ?: throw IllegalStateException("PeerConnection banane me nakaam — WebRtcManager.init() call hua?")

        entry = ConnEntry(pc = pc, publicKey = publicKey)
        conns[peerId] = entry

        if (initiator) {
            val dc = pc.createDataChannel("chat", DataChannel.Init())
            entry.dc = dc
            setupDataChannel(peerId, dc)
            pc.createOffer(object : NoopSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(NoopSdpObserver(), desc)
                    signalingClient?.send(
                        SignalMessage(type = "offer", from = myId, to = peerId, sdp = SdpPayload("offer", desc.description))
                    )
                }
            }, MediaConstraints())
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
                val envelope = runCatching { wireJson.decodeFromString(WireEnvelope.serializer(), text) }.getOrNull() ?: return
                val entry = conns[peerId] ?: return
                val plain = CryptoBox.decryptMessage(envelope.nonce, envelope.ciphertext, entry.publicKey, keyPair.secretKey)
                    ?: return // tampered/corrupt — silently drop
                _messages.update { prev ->
                    prev + ChatMessage(
                        id = "$peerId-${envelope.timestamp}-${(0..999999).random()}",
                        senderId = peerId,
                        recipientId = envelope.recipientId,
                        timestamp = envelope.timestamp,
                        text = plain,
                        self = false
                    )
                }
            }
        })
    }

    private fun updatePeerStatus(peerId: String, status: PeerStatus) {
        _peers.update { prev -> prev.map { if (it.id == peerId) it.copy(status = status) else it } }
    }

    fun sendMessage(text: String, recipientId: String) {
        val timestamp = System.currentTimeMillis()
        val targets: List<Pair<String, ConnEntry>> =
            if (recipientId == "broadcast") conns.entries.map { it.key to it.value }
            else conns[recipientId]?.let { listOf(recipientId to it) } ?: emptyList()

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
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))
        }

        _messages.update { prev ->
            prev + ChatMessage(
                id = "self-$timestamp",
                senderId = myId,
                recipientId = recipientId,
                timestamp = timestamp,
                text = text,
                self = true
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        conns.values.forEach { it.pc.close() }
        conns.clear()
        signalingClient?.close()
        runCatching { signalingServer?.stop() }
        nsdHelper.stopAdvertising()
        nsdHelper.stopDiscovery()
    }
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

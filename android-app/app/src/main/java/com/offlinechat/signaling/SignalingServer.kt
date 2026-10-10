package com.offlinechat.signaling

import com.offlinechat.model.PeerInfo
import com.offlinechat.model.SignalMessage
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Embedded local signaling node (host role) — PRD Section 3.2.
 *
 * PRIVACY GUARANTEE:
 * - Sirf peer-discovery aur WebRTC handshake (offer/answer/ICE) relay karta hai.
 * - Koi chat message yahan se kabhi nahi guzarta — wo seedha peer-to-peer (DataChannel) jaata hai.
 * - Koi data disk par likha nahi jaata — sab kuch sirf RAM (in-memory) me, process band hote hi khatam.
 */
class SignalingServer(port: Int) : NanoWSD(port) {

    private val json = Json { ignoreUnknownKeys = true }
    private val peerSockets = mutableMapOf<String, WebSocket>()
    private val peerPublicKeys = mutableMapOf<String, String>()
    private val lock = Any()

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = PeerSocket(handshake)

    private inner class PeerSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var myId: String? = null

        override fun onOpen() {
            // Kuch nahi — asli identity "join" message par pata chalti hai
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            val id = myId ?: return
            synchronized(lock) {
                peerSockets.remove(id)
                peerPublicKeys.remove(id)
            }
            broadcastExceptMsg(id, SignalMessage(type = "leave", peerId = id))
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            val msg = runCatching { json.decodeFromString(SignalMessage.serializer(), text) }.getOrNull() ?: return
            handle(msg)
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {}

        private fun handle(msg: SignalMessage) {
            when (msg.type) {
                "join" -> {
                    val id = msg.peerId ?: return
                    val pk = msg.publicKey ?: return
                    myId = id
                    val existing: List<PeerInfo>
                    synchronized(lock) {
                        existing = peerPublicKeys.entries
                            .filter { it.key != id }
                            .map { PeerInfo(it.key, it.value) }
                        peerSockets[id] = this
                        peerPublicKeys[id] = pk
                    }
                    sendMsg(SignalMessage(type = "peer-list", peers = existing))
                    broadcastExceptMsg(id, SignalMessage(type = "peer-joined", peerId = id, publicKey = pk))
                }
                "offer", "answer", "ice-candidate" -> {
                    val to = msg.to ?: return
                    val targetSocket = synchronized(lock) { peerSockets[to] }
                    if (targetSocket != null) {
                        runCatching { targetSocket.send(json.encodeToString(SignalMessage.serializer(), msg)) }
                    }
                }
                else -> Unit
            }
        }

        private fun sendMsg(msg: SignalMessage) {
            runCatching { send(json.encodeToString(SignalMessage.serializer(), msg)) }
        }
    }

    private fun broadcastExceptMsg(exceptId: String, msg: SignalMessage) {
        val text = json.encodeToString(SignalMessage.serializer(), msg)
        val targets = synchronized(lock) { peerSockets.filterKeys { it != exceptId }.values.toList() }
        targets.forEach { socket -> runCatching { socket.send(text) } }
    }
}

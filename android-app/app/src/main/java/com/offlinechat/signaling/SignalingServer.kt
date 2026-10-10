package com.offlinechat.signaling

import android.util.Log
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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️  DO NOT "SIMPLIFY" `startServer()` BACK TO `start()`.  (nanohttpd 2.3.1)
 * ─────────────────────────────────────────────────────────────────────────────
 * `NanoHTTPD.start()` calls `start(SOCKET_READ_TIMEOUT)` where
 * `SOCKET_READ_TIMEOUT = 5000` (NanoHTTPD.java:1836), and the accept loop then
 * applies that value to EVERY accepted socket:
 *
 *     if (this.timeout > 0) { finalAccept.setSoTimeout(this.timeout); }   // :1771
 *
 * NanoWSD serves a WebSocket by *blocking in that same socket's read loop*:
 *
 *     private void readWebsocket() {
 *         try { while (state == OPEN) { handleWebsocketFrame(WebSocketFrame.read(in)); } }
 *         catch (IOException e) { onException(e); ... }
 *         finally { doClose(InternalServerError, "Handler terminated...", false); }  // always runs
 *     }
 *
 * `SocketTimeoutException extends IOException`, so 5 seconds of silence on a
 * healthy, idle connection throws, escapes the loop, and the `finally` block
 * FORCE-CLOSES the peer. Every peer of an Android host would be dropped ~5s
 * after they stopped talking — the chat looks like it works, then everyone
 * silently disconnects. Passing `timeout = 0` skips `setSoTimeout` entirely
 * (the `> 0` guard), giving a true blocking read, which is what NanoWSD expects.
 *
 * Dead-peer detection is then handled by [heartbeat] below plus the client's
 * OkHttp ping interval (15s), which NanoWSD answers with an automatic Pong.
 */
class SignalingServer(port: Int) : NanoWSD(port) {

    private val json = Json { ignoreUnknownKeys = true }
    private val peerSockets = mutableMapOf<String, WebSocket>()
    private val peerPublicKeys = mutableMapOf<String, String>()
    private val lock = Any()

    @Volatile
    private var running = false
    private var heartbeatThread: Thread? = null

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = PeerSocket(handshake)

    /**
     * Bind the port with NO socket read timeout and start the dead-peer heartbeat.
     * See the class doc — using the inherited `start()` here is a real bug.
     */
    @Throws(IOException::class)
    fun startServer() {
        start(0, true) // 0 => no setSoTimeout => blocking read => idle peers stay connected
        running = true
        // Explicit `it.start()`, not an `apply { start() }` block: the interop test greps
        // this file for a receiver-less `start()`, which would re-introduce NanoHTTPD's
        // 5-second socket timeout. Keeping every Thread start qualified means the only
        // unqualified `start(...)` left in the class is the NanoWSD one.
        heartbeatThread = Thread({ heartbeatLoop() }, "signaling-heartbeat").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "Embedded signaling node listening on port $port (no read timeout)")
    }

    /**
     * With `soTimeout = 0` a peer whose Wi-Fi simply vanishes (no FIN, no RST) would
     * otherwise leave a socket — and a phantom entry in the peer list — forever.
     * A write to a dead socket eventually fails, so probing each peer periodically
     * lets us evict it and tell everyone else it left.
     */
    private fun heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return

            val snapshot = synchronized(lock) { peerSockets.values.toList() }
            snapshot.forEach { socket ->
                val alive = runCatching { socket.ping(PING_PAYLOAD) }.isSuccess &&
                    runCatching { socket.isOpen }.getOrDefault(false)
                if (!alive) {
                    Log.i(TAG, "Heartbeat failed — evicting a dead peer socket")
                    runCatching { socket.close(WebSocketFrame.CloseCode.GoingAway, "heartbeat timeout", false) }
                    // `close()` triggers `doClose` -> `onClose`, which does the real cleanup
                    // and broadcasts the `leave`. Nothing else to do here.
                }
            }
        }
    }

    /**
     * Idempotent shutdown — safe to call from `onCleared()`.
     *
     * Ordering matters: the roster is cleared BEFORE the sockets are closed, so the
     * `onClose` that each close triggers finds `peerSockets[id] !== this` and skips
     * its `leave` broadcast. Otherwise shutting the host down would spam every peer
     * with a `leave` for every other peer.
     */
    override fun stop() {
        running = false
        heartbeatThread?.interrupt()
        heartbeatThread = null

        val sockets = synchronized(lock) {
            val snapshot = peerSockets.values.toList()
            peerSockets.clear()
            peerPublicKeys.clear()
            snapshot
        }
        sockets.forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "host shutting down", false) } }
        runCatching { super.stop() }
        Log.i(TAG, "Embedded signaling node stopped")
    }

    private inner class PeerSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var myId: String? = null

        override fun onOpen() {
            // Kuch nahi — asli identity "join" message par pata chalti hai
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            val id = myId ?: return
            // Only evict if the entry still points at THIS socket. A peer that
            // reconnects (same ViewModel => same anon id) registers a new socket
            // before the old one's onClose fires; without this identity check the
            // stale socket would kick the fresh, working connection off the roster
            // and broadcast a bogus `leave`.
            val removed = synchronized(lock) {
                if (peerSockets[id] === this) {
                    peerSockets.remove(id)
                    peerPublicKeys.remove(id)
                    true
                } else {
                    false
                }
            }
            if (removed) {
                Log.i(TAG, "Peer left: $id (${code?.name ?: "n/a"})")
                broadcastExceptMsg(id, SignalMessage(type = "leave", peerId = id))
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            val msg = runCatching { json.decodeFromString(SignalMessage.serializer(), text) }.getOrNull() ?: return
            handle(msg)
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            // With timeout=0 a SocketTimeoutException should no longer reach here.
            // Anything that does is a genuinely broken socket; log it so a field
            // failure is diagnosable instead of looking like a clean close.
            Log.w(TAG, "Peer socket error: ${exception?.javaClass?.simpleName}: ${exception?.message}")
        }

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
                        // A reconnect replaces any stale socket for the same id.
                        peerSockets[id] = this
                        peerPublicKeys[id] = pk
                    }
                    Log.i(TAG, "Peer joined: $id")
                    sendMsg(SignalMessage(type = "peer-list", peers = existing))
                    broadcastExceptMsg(id, SignalMessage(type = "peer-joined", peerId = id, publicKey = pk))
                }

                "offer", "answer", "ice-candidate" -> {
                    // Blind relay. The payload is never inspected, logged or stored.
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

    private companion object {
        const val TAG = "SignalingServer"
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        val PING_PAYLOAD = ByteArray(0)
    }
}

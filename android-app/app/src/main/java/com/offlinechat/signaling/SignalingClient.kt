package com.offlinechat.signaling

import com.offlinechat.model.SignalMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Signaling node se WebSocket client — chahe wo isi device ka apna embedded server ho (host role,
 * loopback 127.0.0.1) ya kisi doosre device ka (peer role). Dono cases me isi class ka use hota hai,
 * jisse baaki ka chat logic (ChatEngine) host/peer se independent rehta hai.
 */
class SignalingClient(
    private val onMessage: (SignalMessage) -> Unit,
    private val onStateChange: (Boolean) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStateChange(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.decodeFromString(SignalMessage.serializer(), text) }.getOrNull()
                if (msg != null) onMessage(msg)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStateChange(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStateChange(false)
            }
        })
    }

    fun send(msg: SignalMessage) {
        webSocket?.send(json.encodeToString(SignalMessage.serializer(), msg))
    }

    fun close() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}

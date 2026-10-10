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
                // Log the real cause: without it a failed handshake looks identical to a
                // clean close and there is nothing to debug from.
                android.util.Log.w(
                    "SignalingClient",
                    "WebSocket failed for $url: ${t.javaClass.simpleName}: ${t.message}",
                    t
                )
                onStateChange(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i("SignalingClient", "WebSocket closed: $code $reason")
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

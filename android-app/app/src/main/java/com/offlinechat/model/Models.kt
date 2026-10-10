package com.offlinechat.model

import kotlinx.serialization.Serializable

/**
 * IMPORTANT: In sabhi shapes ko web-app/src/types.ts ke JSON output se EXACTLY match karna
 * zaroori hai — yehi Android aur Web ke beech interoperability ka wire contract hai.
 */

@Serializable
data class PeerInfo(val peerId: String, val publicKey: String)

@Serializable
data class SdpPayload(val type: String, val sdp: String)

@Serializable
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

@Serializable
data class SignalMessage(
    val type: String, // "join" | "peer-list" | "peer-joined" | "offer" | "answer" | "ice-candidate" | "leave"
    val peerId: String? = null,
    val publicKey: String? = null,
    val peers: List<PeerInfo>? = null,
    val from: String? = null,
    val to: String? = null,
    val sdp: SdpPayload? = null,
    val candidate: IceCandidatePayload? = null
)

@Serializable
data class WireEnvelope(
    val type: String = "text",
    val senderId: String,
    val recipientId: String, // "broadcast" ya specific peerId
    val timestamp: Long,
    val nonce: String,      // base64 AES-GCM IV
    val ciphertext: String  // base64
)

enum class PeerStatus { CONNECTING, ONLINE, OFFLINE }

data class Peer(
    val id: String,
    val publicKey: String,
    val status: PeerStatus = PeerStatus.CONNECTING
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val timestamp: Long,
    val text: String,
    val self: Boolean,
    /**
     * False when the message never actually left the device (no open DataChannel to the
     * recipient). Without this the bubble renders as if it were sent while nothing went
     * out — the single most confusing failure mode in a LAN chat app.
     */
    val delivered: Boolean = true
)

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
    val type: String, // "join" | "peer-list" | "peer-joined" | "offer" | "answer" | "ice-candidate" | "leave" | "relay"
    val peerId: String? = null,
    val publicKey: String? = null,
    val peers: List<PeerInfo>? = null,
    val from: String? = null,
    val to: String? = null,
    val sdp: SdpPayload? = null,
    val candidate: IceCandidatePayload? = null,
    val envelope: WireEnvelope? = null
)

@Serializable
data class WireEnvelope(
    val type: String = "text", // "text" | "file" | "voice" | "call"
    val senderId: String,
    val recipientId: String, // "broadcast" ya specific peerId
    val timestamp: Long,
    val nonce: String,      // base64 AES-GCM IV
    val ciphertext: String, // base64
    /**
     * File metadata — sirf tab set hota hai jab `type == "file"`.
     * File ko 16 KB chunks me tod kar bheja jaata hai (RFC 8831 recommendation).
     */
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    /** Voice message duration (seconds) — sirf `type == "voice"` par set. */
    val duration: Int? = null
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
    /** File metadata — sirf file messages par set hota hai. */
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    /** File ka local path — sirf receiver par set hota hai (save hone ke baad). */
    val localPath: String? = null,
    /** Delivery state — offline peer ke liye PENDING, warna SENT. */
    val status: MessageStatus = MessageStatus.SENT,
    /** Voice message duration (seconds) — sirf voice messages par set. */
    val duration: Int? = null
)

enum class MessageStatus { SENT, PENDING }

// ─────────────────────────────────────────────────────────────────────────────
// CALL (audio/video)
// ─────────────────────────────────────────────────────────────────────────────

enum class CallState { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }
enum class CallMedia { AUDIO, VIDEO }

@Serializable
data class CallSignal(
    val action: String,        // "invite" | "accept" | "reject" | "end"
    val video: Boolean = false // video call hai ya audio-only
)

data class CallInfo(
    val peerId: String,
    val peerName: String,
    val media: CallMedia,
    val state: CallState,
    val incoming: Boolean,
    val since: Long = 0L,
    val micOn: Boolean = true,
    val camOn: Boolean = true
) {
    val isVideo: Boolean get() = media == CallMedia.VIDEO
}

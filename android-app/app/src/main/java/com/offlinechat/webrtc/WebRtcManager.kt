package com.offlinechat.webrtc

import android.content.Context
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

/**
 * PRD Section 3.4 — WebRTC DataChannel, sirf local network. Koi STUN/TURN ki zaroorat nahi
 * (same subnet par NAT traversal nahi chahiye) — isliye iceServers khaali list hai.
 */
object WebRtcManager {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun init(context: Context) {
        if (factory != null) return
        synchronized(this) {
            if (factory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
    }

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory?.createPeerConnection(rtcConfig, observer)
    }
}

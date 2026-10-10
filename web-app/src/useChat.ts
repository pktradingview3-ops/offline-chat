import { useCallback, useEffect, useRef, useState } from 'react';
import { generateAnonId } from './identity';
import {
  generateKeyPair,
  publicKeyToBase64,
  base64ToPublicKey,
  encryptMessage,
  decryptMessage,
} from './crypto';
import { SignalingClient } from './signaling';
import { SignalMessage, Peer, ChatMessage, WireEnvelope } from './types';

interface PeerConn {
  pc: RTCPeerConnection;
  dc: RTCDataChannel | null;
  publicKey: Uint8Array;
}

// Purely local network — koi STUN/TURN server ki zaroorat nahi (koi NAT traversal nahi chahiye).
const RTC_CONFIG: RTCConfiguration = { iceServers: [] };

export function useChat(signalingUrl: string) {
  const [myId] = useState(() => generateAnonId());
  const [keyPair] = useState(() => generateKeyPair());
  const [peers, setPeers] = useState<Peer[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const signalRef = useRef<SignalingClient | null>(null);
  const connsRef = useRef<Map<string, PeerConn>>(new Map());
  const publicKeysRef = useRef<Map<string, string>>(new Map());

  const updatePeerStatus = useCallback((peerId: string, status: Peer['status']) => {
    setPeers((prev) => prev.map((p) => (p.id === peerId ? { ...p, status } : p)));
  }, []);

  const setupDataChannel = useCallback(
    (peerId: string, dc: RTCDataChannel) => {
      dc.onopen = () => updatePeerStatus(peerId, 'online');
      dc.onclose = () => updatePeerStatus(peerId, 'offline');
      dc.onmessage = async (event: MessageEvent) => {
        try {
          const envelope: WireEnvelope = JSON.parse(event.data);
          const conn = connsRef.current.get(peerId);
          if (!conn) return;
          const text = await decryptMessage(envelope.nonce, envelope.ciphertext, conn.publicKey, keyPair.secretKey);
          if (text === null) return; // tampered ya corrupt — silently drop
          setMessages((prev) => [
            ...prev,
            {
              id: `${peerId}-${envelope.timestamp}-${Math.random().toString(36).slice(2)}`,
              senderId: peerId,
              recipientId: envelope.recipientId,
              timestamp: envelope.timestamp,
              text,
              self: false,
            },
          ]);
        } catch {
          // malformed payload, ignore
        }
      };
    },
    [keyPair, updatePeerStatus]
  );

  const createConnection = useCallback(
    (peerId: string, publicKeyB64: string, initiator: boolean): PeerConn => {
      const pc = new RTCPeerConnection(RTC_CONFIG);
      const publicKey = base64ToPublicKey(publicKeyB64);
      const entry: PeerConn = { pc, dc: null, publicKey };
      connsRef.current.set(peerId, entry);

      pc.onicecandidate = (event) => {
        if (event.candidate) {
          signalRef.current?.send({
            type: 'ice-candidate',
            from: myId,
            to: peerId,
            candidate: event.candidate.toJSON(),
          });
        }
      };

      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
          updatePeerStatus(peerId, 'offline');
        }
      };

      if (initiator) {
        const dc = pc.createDataChannel('chat', { ordered: true });
        entry.dc = dc;
        setupDataChannel(peerId, dc);
        pc.createOffer()
          .then((offer) => pc.setLocalDescription(offer).then(() => offer))
          .then((offer) => {
            signalRef.current?.send({ type: 'offer', from: myId, to: peerId, sdp: offer });
          })
          .catch(() => setError('Connection banane me dikkat aayi.'));
      } else {
        pc.ondatachannel = (event) => {
          entry.dc = event.channel;
          setupDataChannel(peerId, event.channel);
        };
      }

      return entry;
    },
    [myId, setupDataChannel, updatePeerStatus]
  );

  useEffect(() => {
    if (!signalingUrl) return;

    const signal = new SignalingClient();
    signalRef.current = signal;
    setError(null);

    signal
      .connect(signalingUrl)
      .then(() => {
        setConnected(true);
        signal.send({ type: 'join', peerId: myId, publicKey: publicKeyToBase64(keyPair.publicKey) });
      })
      .catch(() => {
        setConnected(false);
        setError('Signaling node se connect nahi ho paaya. URL aur network check karein.');
      });

    signal.onMessage(async (msg: SignalMessage) => {
      switch (msg.type) {
        case 'peer-list': {
          const list = msg.peers || [];
          list.forEach((p) => publicKeysRef.current.set(p.peerId, p.publicKey));
          setPeers((prev) => {
            const existingIds = new Set(prev.map((p) => p.id));
            const merged = [...prev];
            list.forEach((p) => {
              if (!existingIds.has(p.peerId)) {
                merged.push({ id: p.peerId, publicKey: p.publicKey, status: 'connecting' });
              }
            });
            return merged;
          });
          // Main naya peer hu — maujooda sabhi peers ko main hi offer bhejunga
          list.forEach((p) => {
            if (!connsRef.current.has(p.peerId)) {
              createConnection(p.peerId, p.publicKey, true);
            }
          });
          break;
        }

        case 'peer-joined': {
          if (!msg.peerId || !msg.publicKey) return;
          publicKeysRef.current.set(msg.peerId, msg.publicKey);
          setPeers((prev) =>
            prev.some((p) => p.id === msg.peerId)
              ? prev
              : [...prev, { id: msg.peerId!, publicKey: msg.publicKey!, status: 'connecting' }]
          );
          // Yahan kuch nahi karte — naya peer khud offer bhejega, hum wait karte hain
          break;
        }

        case 'offer': {
          if (!msg.from || !msg.sdp) return;
          let conn = connsRef.current.get(msg.from);
          if (!conn) {
            const pk = publicKeysRef.current.get(msg.from);
            if (!pk) return; // publicKey abhi tak nahi mila, safe ignore
            conn = createConnection(msg.from, pk, false);
          }
          await conn.pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
          const answer = await conn.pc.createAnswer();
          await conn.pc.setLocalDescription(answer);
          signal.send({ type: 'answer', from: myId, to: msg.from, sdp: answer });
          break;
        }

        case 'answer': {
          if (!msg.from || !msg.sdp) return;
          const conn = connsRef.current.get(msg.from);
          if (conn) await conn.pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
          break;
        }

        case 'ice-candidate': {
          if (!msg.from || !msg.candidate) return;
          const conn = connsRef.current.get(msg.from);
          if (conn) {
            try {
              await conn.pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
            } catch {
              // late/duplicate candidate, safe ignore
            }
          }
          break;
        }

        case 'leave': {
          if (!msg.peerId) return;
          const conn = connsRef.current.get(msg.peerId);
          conn?.pc.close();
          connsRef.current.delete(msg.peerId);
          publicKeysRef.current.delete(msg.peerId);
          setPeers((prev) => prev.filter((p) => p.id !== msg.peerId));
          break;
        }

        default:
          break;
      }
    });

    return () => {
      signal.close();
      connsRef.current.forEach((c) => c.pc.close());
      connsRef.current.clear();
      publicKeysRef.current.clear();
      setPeers([]);
      setConnected(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signalingUrl]);

  const sendMessage = useCallback(
    (text: string, recipientId: string | 'broadcast' = 'broadcast') => {
      const timestamp = Date.now();
      const entries: [string, PeerConn][] =
        recipientId === 'broadcast'
          ? Array.from(connsRef.current.entries())
          : connsRef.current.has(recipientId)
          ? [[recipientId, connsRef.current.get(recipientId)!]]
          : [];

      entries.forEach(([, conn]) => {
        const dc = conn.dc;
        if (!dc || dc.readyState !== 'open') return;
        encryptMessage(text, conn.publicKey, keyPair.secretKey).then(({ nonce, ciphertext }) => {
          const envelope: WireEnvelope = {
            type: 'text',
            senderId: myId,
            recipientId,
            timestamp,
            nonce,
            ciphertext,
          };
          if (dc.readyState === 'open') dc.send(JSON.stringify(envelope));
        });
      });

      setMessages((prev) => [
        ...prev,
        { id: `self-${timestamp}-${Math.random().toString(36).slice(2)}`, senderId: myId, recipientId, timestamp, text, self: true },
      ]);
    },
    [keyPair, myId]
  );

  return { myId, peers, messages, connected, error, sendMessage };
}

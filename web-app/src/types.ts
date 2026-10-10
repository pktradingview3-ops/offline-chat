export interface Peer {
  id: string;
  nickname?: string;
  publicKey: string; // base64
  status: 'connecting' | 'online' | 'offline';
}

export interface ChatMessage {
  id: string;
  senderId: string;
  recipientId: string | 'broadcast';
  timestamp: number;
  text: string; // sirf decrypted local memory me — kabhi network par plaintext nahi jaata
  self: boolean;
}

export interface SignalMessage {
  type: 'join' | 'peer-list' | 'peer-joined' | 'offer' | 'answer' | 'ice-candidate' | 'leave';
  peerId?: string;
  publicKey?: string;
  peers?: { peerId: string; publicKey: string }[];
  from?: string;
  to?: string;
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit;
}

export interface WireEnvelope {
  type: 'text';
  senderId: string;
  recipientId: string | 'broadcast';
  timestamp: number;
  nonce: string; // base64
  ciphertext: string; // base64
}

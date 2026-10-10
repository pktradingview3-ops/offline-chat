/**
 * Offline Chat App — Local Signaling Node
 *
 * PRIVACY GUARANTEE:
 * - Ye server sirf peer-discovery aur WebRTC handshake (offer/answer/ICE) relay karta hai.
 * - Koi chat message yahan se kabhi nahi guzarta — wo seedha peer-to-peer (WebRTC DataChannel) jaata hai.
 * - Koi data disk par likha nahi jaata. Sab kuch sirf RAM (in-memory) me hai, process band hote hi khatam.
 * - Server ko internet ki zaroorat nahi — sirf local Wi-Fi/LAN network par chalega.
 *
 * Run: npm install && npm start
 * Default port: 8080  (badalne ke liye: PORT=9090 npm start)
 */

const WebSocket = require('ws');

const PORT = process.env.PORT ? Number(process.env.PORT) : 8080;
const wss = new WebSocket.Server({ port: PORT });

// peerId -> { ws, publicKey }   (sirf RAM me, kabhi persist nahi hota)
const peers = new Map();

function send(ws, msg) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function broadcastExcept(exceptId, msg) {
  for (const [id, peer] of peers.entries()) {
    if (id !== exceptId) send(peer.ws, msg);
  }
}

wss.on('connection', (ws) => {
  let myId = null;

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return; // malformed data, ignore silently
    }

    switch (msg.type) {
      case 'join': {
        if (!msg.peerId || !msg.publicKey) return;
        myId = msg.peerId;
        peers.set(myId, { ws, publicKey: msg.publicKey });

        // 1. Naye peer ko maujooda sabhi peers ki list bhejo
        const existingPeers = Array.from(peers.entries())
          .filter(([id]) => id !== myId)
          .map(([id, p]) => ({ peerId: id, publicKey: p.publicKey }));
        send(ws, { type: 'peer-list', peers: existingPeers });

        // 2. Baaki sabko sirf "ek naya peer aaya hai" batao — woh khud offer nahi bhejenge,
        //    naya peer hi unhe offer karega (isse "glare"/double-connection nahi hogi).
        broadcastExcept(myId, { type: 'peer-joined', peerId: myId, publicKey: msg.publicKey });
        break;
      }

      case 'offer':
      case 'answer':
      case 'ice-candidate': {
        // Sirf blind relay — content ko kabhi read/log/store nahi karte
        if (!msg.to) return;
        const target = peers.get(msg.to);
        if (target) send(target.ws, msg);
        break;
      }

      default:
        break; // unknown message type, ignore
    }
  });

  ws.on('close', () => {
    if (myId && peers.has(myId)) {
      peers.delete(myId);
      broadcastExcept(myId, { type: 'leave', peerId: myId });
    }
  });

  ws.on('error', () => {
    // connection drop ho gaya — 'close' event khud handle kar lega
  });
});

console.log(`Signaling node chal raha hai — is LAN ke andar in URLs se connect hoga:`);
console.log(`  ws://<is-machine-ka-LAN-IP>:${PORT}`);
console.log(`Apna LAN IP dekhne ke liye: 'ip addr' (Linux) ya 'ipconfig' (Windows) chalao.`);

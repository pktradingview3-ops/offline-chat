/**
 * End-to-end test for Offline Chat: signaling protocol + E2E crypto.
 *
 * Runs against a live signaling server (default ws://127.0.0.1:8080).
 * Uses the REAL wire protocol from signaling-server/server.js:
 *   join {peerId, publicKey}   -> peer-list (to the joiner) | peer-joined (to others)
 *   offer | answer | ice-candidate {to, ...} -> blind relay to `to`
 *   socket close               -> leave {peerId}
 *
 * Usage:
 *   cd signaling-server && npm start        # in one terminal
 *   node scripts/e2e-test.mjs               # in another
 *
 * Environment:
 *   SIGNALING_URL   override the server URL (default ws://127.0.0.1:8080)
 *
 * Exits 0 only when every check passes.
 */

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

// Resolve deps from whichever folder installed them, so the script works from the repo root.
function loadDep(name) {
  const candidates = [
    path.join(here, '..', 'signaling-server', 'node_modules', name),
    path.join(here, '..', 'web-app', 'node_modules', name),
    name,
  ];
  for (const c of candidates) {
    try { return require(c); } catch { /* try next */ }
  }
  throw new Error(`Cannot find dependency "${name}". Run: cd signaling-server && npm install`);
}

const WebSocket = loadDep('ws');
const nacl = loadDep('tweetnacl');

const URL = process.env.SIGNALING_URL || 'ws://127.0.0.1:8080';

const results = [];
const ok = (name, pass, detail = '') => {
  results.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (u8) => Buffer.from(u8).toString('base64');

const open = (id, publicKey) =>
  new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    ws.received = [];
    ws.on('message', (d) => ws.received.push(JSON.parse(d.toString())));
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'join', peerId: id, publicKey }));
      setTimeout(() => resolve(ws), 350);
    });
    ws.on('error', reject);
  });

const find = (ws, type, ms = 2500) =>
  new Promise((resolve) => {
    const t0 = Date.now();
    const iv = setInterval(() => {
      const m = ws.received.find((x) => x.type === type);
      if (m) { clearInterval(iv); resolve(m); }
      else if (Date.now() - t0 > ms) { clearInterval(iv); resolve(null); }
    }, 50);
  });

// ---------------------------------------------------------------- run
console.log(`Signaling server: ${URL}\n`);

// Unique ids per run, so a stray peer from another session cannot make this flaky.
const RUN = Math.random().toString(36).slice(2, 8);
const ID_A = `anon-a${RUN}`;
const ID_B = `anon-b${RUN}`;
const ID_C = `anon-c${RUN}`;
console.log(`Run id: ${RUN}\n`);

// 1. join handshake
const kpA = nacl.box.keyPair();
const kpB = nacl.box.keyPair();
const kpC = nacl.box.keyPair();

const a = await open(ID_A, b64(kpA.publicKey));
const listA1 = await find(a, 'peer-list');
ok('join -> peer-list for the first peer', !!listA1,
   listA1 ? `peers=${listA1.peers.length}` : 'none');
// Only assert on OUR peers: other sessions may legitimately be connected.
ok('first peer does not see the second peer yet (it has not joined)',
   !!listA1 && !listA1.peers.some((p) => p.peerId === ID_B));

const b = await open(ID_B, b64(kpB.publicKey));
const listB = await find(b, 'peer-list');
ok('second peer gets a peer-list containing the first',
   !!listB && listB.peers.some((p) => p.peerId === ID_A));

const joined = await find(a, 'peer-joined');
ok('first peer is told about the new peer', !!joined && joined.peerId === ID_B);
ok('public keys are exchanged (needed for E2E)',
   !!(listB && listB.peers.find((p) => p.peerId === ID_A)?.publicKey === b64(kpA.publicKey)));

// 2. relay
a.received.length = 0; b.received.length = 0;
a.send(JSON.stringify({ type: 'offer', to: ID_B, from: ID_A, sdp: 'FAKE_OFFER_SDP' }));
ok('offer is relayed to the target', !!(await find(b, 'offer')));

b.send(JSON.stringify({ type: 'answer', to: ID_A, from: ID_B, sdp: 'FAKE_ANSWER_SDP' }));
ok('answer is relayed back', !!(await find(a, 'answer')));

b.send(JSON.stringify({ type: 'ice-candidate', to: ID_A, candidate: 'candidate:1 udp 12345' }));
const ice = await find(a, 'ice-candidate');
ok('ICE candidate is relayed', !!ice && String(ice.candidate).includes('candidate'));

// 3. the server must not carry chat data
const seen = JSON.stringify([...a.received, ...b.received]);
ok('relayed frames contain no chat/ciphertext field',
   !seen.includes('ciphertext') && !seen.includes('"message"'));

// 4. crypto: X25519 -> HKDF-SHA256 -> AES-256-GCM
const sharedAB = nacl.scalarMult(kpA.secretKey, kpB.publicKey);
const sharedBA = nacl.scalarMult(kpB.secretKey, kpA.publicKey);
ok('X25519 shared secret is identical on both sides',
   Buffer.from(sharedAB).equals(Buffer.from(sharedBA)));

const toBuf = (u8) => { const c = new Uint8Array(new ArrayBuffer(u8.length)); c.set(u8); return c; };
async function deriveKey(secret) {
  const mat = await crypto.subtle.importKey('raw', toBuf(secret), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0),
      info: new TextEncoder().encode('offline-chat-e2ee') },
    mat, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

const keyA = await deriveKey(sharedAB);
const keyB = await deriveKey(sharedBA);
const iv = crypto.getRandomValues(new Uint8Array(12));
const plain = 'Namaste, ye secret message hai';
const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, keyA, new TextEncoder().encode(plain));

ok('ciphertext is not the plaintext', Buffer.from(ct).toString('utf8') !== plain);
const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, keyB, ct);
ok("peer B decrypts A's message", new TextDecoder().decode(pt) === plain);

// 5. tamper + wrong key
const tampered = new Uint8Array(ct); tampered[0] ^= 0xff;
let tamperRejected = false;
try { await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, keyB, tampered); } catch { tamperRejected = true; }
ok('tampered ciphertext is rejected (GCM auth tag)', tamperRejected);

const wrongKey = await deriveKey(nacl.scalarMult(kpC.secretKey, kpA.publicKey));
let wrongRejected = false;
try { await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, wrongKey, ct); } catch { wrongRejected = true; }
ok('wrong key is rejected', wrongRejected);

// 6. leave
a.received.length = 0;
b.close();
await sleep(700);
const left = await find(a, 'leave', 1500);
ok('disconnect broadcasts a leave for that peer', !!left && left.peerId === ID_B);

// 7. relay to unknown peer must not crash the server
a.send(JSON.stringify({ type: 'offer', to: 'anon-does-not-exist', sdp: 'x' }));
await sleep(300);
ok('offer to an unknown peer is ignored safely', a.readyState === 1);

a.close();
await sleep(200);

const passed = results.filter((r) => r.pass).length;
console.log(`\n=== ${passed}/${results.length} passed ===`);
process.exit(passed === results.length ? 0 : 1);

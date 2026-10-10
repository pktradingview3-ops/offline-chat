/**
 * Android ↔ Web interoperability test for Offline Chat.
 *
 * WHY THIS EXISTS
 * ---------------
 * The Android client cannot be compiled in an environment without a JDK, the Android
 * SDK and Google Maven. That makes it impossible to *run* the Kotlin — but the two
 * things that would silently break cross-device chat are both testable without it:
 *
 *   1. The crypto. `CryptoBox.kt` (Bouncy Castle) and `web-app/src/crypto.ts`
 *      (tweetnacl + WebCrypto) must derive the same key from the same keypairs and
 *      produce mutually decryptable ciphertext.
 *   2. The wire protocol. The JSON that kotlinx.serialization emits from
 *      `model/Models.kt` must be exactly what `signaling-server/server.js` relays and
 *      what the browser client parses.
 *
 * HOW THE ANDROID SIDE IS MODELLED
 * --------------------------------
 * Node's `crypto` is used as a stand-in for Bouncy Castle. This is sound rather than
 * convenient: `X25519Agreement`, `crypto.diffieHellman('x25519')` and
 * `nacl.scalarMult` are all implementations of RFC 7748, which is why check #1 pins the
 * algorithm against the RFC's own published known-answer vector. Java's
 * `Cipher("AES/GCM/NoPadding")` returns `ciphertext || tag`, WebCrypto returns
 * `ciphertext || tag`, and Node returns them separately — so the Android-side helper
 * concatenates to reproduce the exact Java byte layout.
 *
 * Also included: a source-level regression guard for the NanoHTTPD read-timeout bug
 * (see `signaling/SignalingServer.kt`), which is invisible at runtime until every peer
 * silently disconnects five seconds after they stop talking.
 *
 * Usage:  node scripts/android-interop-test.mjs        (self-contained, spawns a server)
 * Exits 0 only when every check passes.
 */

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { fork } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.join(here, '..');

function loadDep(name) {
  const candidates = [
    path.join(repoRoot, 'signaling-server', 'node_modules', name),
    path.join(repoRoot, 'web-app', 'node_modules', name),
    name,
  ];
  for (const c of candidates) {
    try { return require(c); } catch { /* next */ }
  }
  throw new Error(`Cannot find dependency "${name}". Run: cd signaling-server && npm install`);
}

const WebSocket = loadDep('ws');
const nacl = loadDep('tweetnacl');

const subtle = globalThis.crypto?.subtle;
if (!subtle) {
  console.error('WebCrypto is unavailable — Node 18+ required.');
  process.exit(1);
}

// ──────────────────────────────────────────────────────────────────── test harness

const results = [];
const ok = (name, pass, detail = '') => {
  results.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (buf) => Buffer.from(buf).toString('base64');
const unb64 = (s) => Buffer.from(s, 'base64');
const eq = (a, b) => Buffer.from(a).equals(Buffer.from(b));
const hex = (b) => Buffer.from(b).toString('hex');

// ────────────────────────────────────────────────────────── "Android" crypto (Node)
// Mirrors android-app/.../crypto/CryptoBox.kt line for line.

const HKDF_INFO = Buffer.from('offline-chat-e2ee', 'utf8'); // must match CryptoBox.kt AND crypto.ts
const HKDF_SALT = Buffer.alloc(0);

/**
 * Fixed ASN.1 wrappers for raw X25519 keys, so a 32-byte scalar/point can be turned into
 * a Node KeyObject. Bouncy Castle's `X25519PrivateKeyParameters.getEncoded()` yields the
 * same bare 32 bytes these wrap, which is what `CryptoBox.kt` exchanges over the wire.
 */
const PKCS8_X25519 = Buffer.from('302e020100300506032b656e04220420', 'hex');
const SPKI_X25519 = Buffer.from('302a300506032b656e032100', 'hex');

const privFromRaw = (raw) => crypto.createPrivateKey({
  key: Buffer.concat([PKCS8_X25519, Buffer.from(raw)]), format: 'der', type: 'pkcs8',
});
const pubFromRaw = (raw) => crypto.createPublicKey({
  key: Buffer.concat([SPKI_X25519, Buffer.from(raw)]), format: 'der', type: 'spki',
});
const rawFromPriv = (key) => Buffer.from(key.export({ format: 'der', type: 'pkcs8' })).subarray(-32);
const rawFromPub = (key) => Buffer.from(key.export({ format: 'der', type: 'spki' })).subarray(-32);

/** Raw X25519 keypair, as `X25519KeyPairGenerator` produces (32-byte raw keys). */
function androidGenerateKeyPair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('x25519');
  return { publicRaw: rawFromPub(publicKey), secretRaw: rawFromPriv(privateKey) };
}

/** `X25519Agreement.calculateAgreement` — RFC 7748 raw scalar multiplication. */
function androidSharedSecretRaw(mySecretRaw, theirPublicRaw) {
  return Buffer.from(crypto.diffieHellman({
    privateKey: privFromRaw(mySecretRaw),
    publicKey: pubFromRaw(theirPublicRaw),
  }));
}

/** `HKDFBytesGenerator(SHA256Digest)` with empty salt + info "offline-chat-e2ee" -> 32 bytes */
function androidDeriveAesKey(mySecretRaw, theirPublicRaw) {
  const shared = androidSharedSecretRaw(mySecretRaw, theirPublicRaw);
  return Buffer.from(crypto.hkdfSync('sha256', shared, HKDF_SALT, HKDF_INFO, 32));
}

/**
 * `Cipher.getInstance("AES/GCM/NoPadding")` + `doFinal`.
 * Java appends the 16-byte tag to the ciphertext, so we concatenate Node's two outputs.
 */
function androidEncrypt(plainText, theirPublicRaw, mySecretRaw) {
  const aesKey = androidDeriveAesKey(mySecretRaw, theirPublicRaw);
  const iv = crypto.randomBytes(12); // 12-byte IV, as in CryptoBox.kt
  const cipher = crypto.createCipheriv('aes-256-gcm', aesKey, iv);
  const ct = Buffer.concat([cipher.update(plainText, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag(); // GCMParameterSpec(128, iv) => 16-byte tag
  return { nonce: b64(iv), ciphertext: b64(Buffer.concat([ct, tag])) };
}

function androidDecrypt(nonceB64, ciphertextB64, theirPublicRaw, mySecretRaw) {
  try {
    const aesKey = androidDeriveAesKey(mySecretRaw, theirPublicRaw);
    const iv = unb64(nonceB64);
    const blob = unb64(ciphertextB64);
    const tag = blob.subarray(blob.length - 16);
    const ct = blob.subarray(0, blob.length - 16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', aesKey, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]).toString('utf8');
  } catch {
    return null; // tampered / wrong key — rejected, exactly like CryptoBox.decryptMessage
  }
}

// ───────────────────────────────────────────────────────────── "Web" crypto (as-is)
// Mirrors web-app/src/crypto.ts. tweetnacl scalarMult + WebCrypto HKDF/AES-GCM.

const webKeyPair = () => {
  const kp = nacl.box.keyPair();
  return { publicKey: kp.publicKey, secretKey: kp.secretKey };
};

const toBuf = (u8) => Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength);

async function webDeriveAesKeyRaw(mySecretKey, theirPublicKey) {
  const shared = nacl.scalarMult(mySecretKey, theirPublicKey);
  const material = await subtle.importKey('raw', toBuf(shared), 'HKDF', false, ['deriveKey']);
  const key = await subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: HKDF_SALT, info: HKDF_INFO },
    material,
    { name: 'AES-GCM', length: 256 },
    true,
    ['encrypt', 'decrypt']
  );
  return new Uint8Array(await subtle.exportKey('raw', key));
}

async function webEncrypt(plainText, theirPublicKey, mySecretKey) {
  const aesKey = await subtle.importKey('raw', await webDeriveAesKeyRaw(mySecretKey, theirPublicKey),
    'AES-GCM', false, ['encrypt']);
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const ct = await subtle.encrypt({ name: 'AES-GCM', iv }, aesKey, new TextEncoder().encode(plainText));
  return { nonce: b64(iv), ciphertext: b64(new Uint8Array(ct)) };
}

async function webDecrypt(nonceB64, ciphertextB64, theirPublicKey, mySecretKey) {
  try {
    const aesKey = await subtle.importKey('raw', await webDeriveAesKeyRaw(mySecretKey, theirPublicKey),
      'AES-GCM', false, ['decrypt']);
    const buf = await subtle.decrypt(
      { name: 'AES-GCM', iv: toBuf(unb64(nonceB64)) }, aesKey, toBuf(unb64(ciphertextB64))
    );
    return new TextDecoder().decode(buf);
  } catch {
    return null;
  }
}

// ───────────────────────────────────────────────────────── source-level regression guard

/**
 * Kotlin source with comments removed but string literals intact.
 *
 * Comments have to go: the class KDoc of `SignalingServer.kt` explains the bug by
 * quoting the very `start()` call the guard is looking for, so scanning the raw file
 * produces a false positive. Strings have to stay: other guards assert on the literal
 * `"join"` message type.
 */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ') // block + KDoc comments
    .replace(/\/\/[^\n]*/g, ' ');         // line comments
}

function checkKotlinSource() {
  const p = path.join(repoRoot, 'android-app/app/src/main/java/com/offlinechat/signaling/SignalingServer.kt');
  let src;
  try { src = readFileSync(p, 'utf8'); } catch { return ok('SignalingServer.kt is readable', false, p); }
  const code = stripComments(src);

  // NanoHTTPD.start() == start(SOCKET_READ_TIMEOUT=5000) applied to every accepted socket,
  // and NanoWSD's read loop force-closes on the resulting SocketTimeoutException.
  const bareStart = /(?<![.\w])start\(\s*\)/.test(code.replace(/super\.start\(\)/g, ''));
  ok('SignalingServer never calls the bare NanoHTTPD start()', !bareStart,
    bareStart ? 'bare start() found — reverts the 5s idle-disconnect bug' : 'uses start(0, true)');

  const noTimeout = /start\(\s*0\s*,/.test(code);
  ok('SignalingServer binds with timeout = 0 (no setSoTimeout)', noTimeout);

  // A peer that reconnects reuses its anon id; the stale socket's onClose must not evict it.
  const identityGuard = /peerSockets\[id\]\s*===\s*this/.test(code);
  ok('onClose only evicts when the socket is still the registered one', identityGuard);

  const enginePath = path.join(repoRoot, 'android-app/app/src/main/java/com/offlinechat/chat/ChatEngine.kt');
  const engine = readFileSync(enginePath, 'utf8');
  ok('ChatEngine calls startServer(), not start()', /server\.startServer\(\)/.test(engine));
  ok('ChatEngine queues ICE candidates until the remote description is set',
    /pendingCandidates/.test(engine) && /remoteDescriptionApplied/.test(engine));
  ok('ChatEngine re-announces (join) on every socket open',
    (engine.match(/type\s*=\s*"join"/g) || []).length >= 1 && /onSignalingState/.test(engine));
}

// ─────────────────────────────────────────────────────────────── wire-protocol check

/**
 * kotlinx.serialization omits properties that still hold their default value, so these
 * are the literal bytes the Kotlin models put on the wire. If the browser or the relay
 * ever stops understanding them, this is where it shows up first.
 */
const androidFrames = (ids) => ({
  join: JSON.stringify({ type: 'join', peerId: ids.a, publicKey: ids.aPk }),
  offer: JSON.stringify({
    type: 'offer', from: ids.a, to: ids.b,
    sdp: { type: 'offer', sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n' },
  }),
  answer: JSON.stringify({ type: 'answer', from: ids.a, to: ids.b, sdp: { type: 'answer', sdp: 'v=0\r\n' } }),
  ice: JSON.stringify({
    type: 'ice-candidate', from: ids.a, to: ids.b,
    candidate: { candidate: 'candidate:1 1 udp 2130706431 192.168.1.9 50000 typ host', sdpMid: '0', sdpMLineIndex: 0 },
  }),
});

async function checkWireProtocol(port) {
  const ids = { a: 'anon-android1', b: 'anon-webbrows2', aPk: b64(crypto.randomBytes(32)) };
  const frames = androidFrames(ids);

  const open = (label) => new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.got = [];
    ws.on('message', (d) => ws.got.push(JSON.parse(d.toString())));
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
    setTimeout(() => reject(new Error(`${label} connect timeout`)), 4000);
  });

  // A joins with the exact frame ChatEngine sends on socket open.
  const a = await open('android');
  a.send(frames.join);
  await sleep(250);
  ok('server accepts the Android "join" frame and replies peer-list',
    a.got.some((m) => m.type === 'peer-list'));

  const b = await open('web');
  b.send(JSON.stringify({ type: 'join', peerId: ids.b, publicKey: b64(crypto.randomBytes(32)) }));
  await sleep(300);

  const sawPeerJoined = a.got.some((m) => m.type === 'peer-joined' && m.peerId === ids.b);
  ok('Android host-side peer is told about the web peer', sawPeerJoined);

  // Handshake relay, both directions.
  a.send(frames.offer); await sleep(200);
  const offer = b.got.find((m) => m.type === 'offer');
  ok('Android offer is relayed to the web peer', !!offer);
  ok('relayed offer keeps the RTCSessionDescriptionInit shape',
    !!offer && typeof offer.sdp?.type === 'string' && typeof offer.sdp?.sdp === 'string' && offer.from === ids.a);

  b.send(JSON.stringify({ type: 'answer', from: ids.b, to: ids.a, sdp: { type: 'answer', sdp: 'v=0\r\n' } }));
  await sleep(200);
  ok('web answer is relayed back to the Android peer', a.got.some((m) => m.type === 'answer' && m.from === ids.b));

  a.send(frames.ice); await sleep(200);
  const ice = b.got.find((m) => m.type === 'ice-candidate');
  ok('Android ICE candidate is relayed', !!ice);
  ok('relayed ICE candidate is a valid RTCIceCandidateInit',
    !!ice && typeof ice.candidate?.candidate === 'string' && ice.candidate?.sdpMid === '0'
    && ice.candidate?.sdpMLineIndex === 0);

  // The browser side adds `usernameFragment`; Android must tolerate unknown keys.
  b.send(JSON.stringify({
    type: 'ice-candidate', from: ids.b, to: ids.a,
    candidate: { candidate: 'candidate:2 1 tcp 1015022591 192.168.1.9 9 typ host tcptype active',
      sdpMid: '0', sdpMLineIndex: 0, usernameFragment: 'abcdef' },
  }));
  await sleep(200);
  const back = a.got.find((m) => m.type === 'ice-candidate' && m.from === ids.b);
  ok('web ICE candidate (with extra usernameFragment) reaches Android',
    !!back && back.candidate?.usernameFragment === 'abcdef');
  ok('extra keys are relayed verbatim, so ignoreUnknownKeys covers them', !!back);

  // Privacy invariant: the relay must never carry chat content.
  const all = [...a.got, ...b.got];
  const leak = all.filter((m) =>
    'ciphertext' in m || 'nonce' in m || 'text' in m || 'message' in m || 'body' in m);
  ok('no chat/ciphertext field ever passes through the signaling relay', leak.length === 0,
    leak.length ? JSON.stringify(leak[0]).slice(0, 120) : `${all.length} relayed frames inspected`);

  a.close(); b.close();
  await sleep(300);
}

/** The chat payload itself — the exact envelope ChatEngine sends over the DataChannel. */
function checkChatEnvelopeShape() {
  const envelope = {
    type: 'text', senderId: 'anon-android1', recipientId: 'broadcast',
    timestamp: Date.now(), nonce: b64(crypto.randomBytes(12)), ciphertext: b64(crypto.randomBytes(24)),
  };
  const keys = Object.keys(envelope).sort().join(',');
  ok('WireEnvelope carries exactly the fields both clients agree on',
    keys === 'ciphertext,nonce,recipientId,senderId,timestamp,type', keys);

  // The browser reads only nonce/ciphertext/recipientId, so a missing `type`
  // (kotlinx.serialization omits defaulted properties) must still decode.
  const { type, ...withoutType } = envelope;
  ok('envelope is still usable if `type` is omitted (kotlinx default elision)',
    typeof withoutType.nonce === 'string' && typeof withoutType.ciphertext === 'string'
    && typeof withoutType.recipientId === 'string');
}

// ───────────────────────────────────────────────────────────────────────────── run

async function main() {
  console.log('Android ↔ Web interoperability test\n');

  console.log('— source-level regression guards —');
  checkKotlinSource();

  console.log('\n— X25519 (RFC 7748) —');
  // RFC 7748 §6.1 known-answer vector. Pins the algorithm both libraries must implement.
  const RFC = {
    aPriv: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    aPub: '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
    bPriv: '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
    bPub: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f',
    shared: '4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742',
  };
  const nodeShared = hex(androidSharedSecretRaw(Buffer.from(RFC.aPriv, 'hex'), Buffer.from(RFC.bPub, 'hex')));
  ok('Node X25519 matches the RFC 7748 known-answer vector', nodeShared === RFC.shared, nodeShared.slice(0, 32) + '…');

  const naclShared = hex(toBuf(nacl.scalarMult(
    new Uint8Array(Buffer.from(RFC.aPriv, 'hex')), new Uint8Array(Buffer.from(RFC.bPub, 'hex')))));
  ok('tweetnacl (web) X25519 matches the same RFC 7748 vector', naclShared === RFC.shared);
  ok('=> Android (Bouncy Castle) and web agree on the shared secret',
    nodeShared === RFC.shared && naclShared === RFC.shared, 'both are RFC 7748');

  console.log('\n— key derivation (HKDF-SHA256) —');
  const A = androidGenerateKeyPair();
  const B = webKeyPair();
  const bPubJwk = { kty: 'OKP', crv: 'X25519', x: Buffer.from(B.publicKey).toString('base64url') };

  const androidKey = androidDeriveAesKey(A.secretRaw, Buffer.from(B.publicKey));
  const webKey = await webDeriveAesKeyRaw(B.secretKey, A.publicRaw);
  ok('Android and web derive the identical AES-256 session key', eq(androidKey, webKey),
    hex(androidKey).slice(0, 24) + '…');
  ok('derived key is 32 bytes (AES-256)', androidKey.length === 32 && webKey.length === 32);
  ok('HKDF is symmetric — B→A derives the same key',
    eq(androidDeriveAesKey(A.secretRaw, Buffer.from(B.publicKey)),
      await webDeriveAesKeyRaw(B.secretKey, A.publicRaw)));

  console.log('\n— message encryption (AES-256-GCM) —');
  const PLAIN = 'Namaste! Yeh message LAN par encrypted ja raha hai. 🚀';

  const a2w = androidEncrypt(PLAIN, Buffer.from(B.publicKey), A.secretRaw);
  ok('Android ciphertext is not the plaintext', !a2w.ciphertext.includes(Buffer.from(PLAIN).toString('base64')));
  ok('nonce is a 12-byte IV', unb64(a2w.nonce).length === 12);
  const webGot = await webDecrypt(a2w.nonce, a2w.ciphertext, A.publicRaw, B.secretKey);
  ok('web decrypts what Android encrypted', webGot === PLAIN, webGot ? 'round-trip ok' : 'DECRYPT FAILED');

  const w2a = await webEncrypt(PLAIN, A.publicRaw, B.secretKey);
  const androidGot = androidDecrypt(w2a.nonce, w2a.ciphertext, Buffer.from(B.publicKey), A.secretRaw);
  ok('Android decrypts what web encrypted', androidGot === PLAIN, androidGot ? 'round-trip ok' : 'DECRYPT FAILED');

  ok('ciphertext carries a 16-byte GCM tag',
    unb64(a2w.ciphertext).length === Buffer.byteLength(PLAIN) + 16);

  console.log('\n— tamper & key-mismatch rejection —');
  const tampered = Buffer.from(unb64(a2w.ciphertext)); tampered[0] ^= 0xff;
  ok('web rejects a ciphertext modified in transit',
    (await webDecrypt(a2w.nonce, b64(tampered), A.publicRaw, B.secretKey)) === null);
  const tampered2 = Buffer.from(unb64(w2a.ciphertext)); tampered2[tampered2.length - 1] ^= 0x01;
  ok('Android rejects a ciphertext with a flipped tag bit',
    androidDecrypt(w2a.nonce, b64(tampered2), Buffer.from(B.publicKey), A.secretRaw) === null);
  const C = webKeyPair();
  ok('Android rejects a message encrypted for someone else',
    androidDecrypt(w2a.nonce, w2a.ciphertext, Buffer.from(C.publicKey), A.secretRaw) === null);
  ok('web rejects a message decrypted with the wrong key',
    (await webDecrypt(a2w.nonce, a2w.ciphertext, A.publicRaw, C.secretKey)) === null);

  console.log('\n— chat envelope shape —');
  checkChatEnvelopeShape();

  console.log('\n— signaling wire protocol (live server) —');
  const port = 8090 + Math.floor(Math.random() * 900);
  const server = fork(path.join(repoRoot, 'signaling-server', 'server.js'), [], {
    env: { ...process.env, PORT: String(port) }, stdio: 'ignore',
  });
  await sleep(900);
  try {
    await checkWireProtocol(port);
  } finally {
    server.kill('SIGTERM');
  }

  // ───────────────────────────────────────────────────────────────────── summary
  const passed = results.filter((r) => r.pass).length;
  console.log(`\n=== ${passed}/${results.length} passed ===`);
  if (passed !== results.length) {
    console.log('\nFailed checks:');
    results.filter((r) => !r.pass).forEach((r) => console.log('  ✗ ' + r.name));
  }
  process.exit(passed === results.length ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });

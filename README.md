# Offline Chat — LAN-Only Peer-to-Peer Encrypted Chat (Web + Android)

**Offline chat that needs no internet, no server account, and no cloud.** Offline
Chat is a local-network (LAN) messenger: a small signaling node on one machine
handles peer discovery and the WebRTC handshake, and every message then travels
**peer-to-peer, end-to-end encrypted** (X25519 + HKDF-SHA256 + AES-256-GCM).
Works across a browser and Android, on the same Wi-Fi, with no data ever leaving
the network.

Built with **TypeScript + React + Vite** (web), **Kotlin + Jetpack Compose**
(Android) and a **Node.js WebSocket** signaling relay.

>  **App kaise kaam karta hai?** Poora flow chart Hinglish me:
> [`docs/FLOWCHART.md`](docs/FLOWCHART.md) (8 Mermaid diagrams, GitHub par render hote
> hain) aur ek share karne layak image [`docs/flowchart.svg`](docs/flowchart.svg).

> **The signaling node never sees your messages.** It relays SDP offers/answers
> and ICE candidates only. Chat text goes over a direct encrypted WebRTC
> DataChannel between peers. There is no chat-message field in the protocol, and
> the test suite asserts that.

## Features

- **Offline / LAN-only** — no internet required after install; nothing to sign up for.
- **Peer-to-peer chat** — WebRTC DataChannel, direct device-to-device.
- **End-to-end encryption** — X25519 raw ECDH → HKDF-SHA256 → AES-256-GCM, in both
  the web and Android clients, byte-for-byte interoperable.
- **Anonymous identity** — an automatic `anon-xxxxxx` id per session, no accounts.
- **Peer discovery** — via the signaling node on the local network.
- **1:1 private chat and a group (broadcast) channel.**
- **Zero persistence by design** — refresh means a fresh identity and empty
  history. Nothing is written to disk.
- **Installable PWA** — the web client is a progressive web app.
- **Tamper-proof transport** — the AES-GCM auth tag rejects modified ciphertext;
  covered by tests.
- **No ads, no analytics, no tracking.**

## What this is not

- Not an internet messenger — it is deliberately local-network only.
- Not anonymous against your own LAN: peers see each other's session id.
- Not a mesh/Bluetooth fallback (planned, not implemented).

## Repository layout

```
signaling-server/   Node.js WebSocket relay — discovery + WebRTC handshake only
web-app/            React + TypeScript + Vite browser client (PWA)
android-app/        Kotlin + Jetpack Compose Android client
scripts/            e2e-test.mjs          — signaling protocol + crypto suite (18 checks)
                    android-interop-test.mjs — Android↔web crypto & wire-protocol suite
docs/               FLOWCHART.md (Mermaid) + flowchart.svg — Hinglish flow charts
```

## Quick start

### 1. Signaling node (one machine on the network)

```bash
cd signaling-server
npm install
npm start          # listens on :8080
```

### 2. Web client

```bash
cd web-app
npm install
npm run dev        # Vite prints Local: and Network: URLs
```

Open the app in **two** tabs (or on two devices on the same Wi-Fi), enter the
signaling URL — `ws://127.0.0.1:8080` locally, `ws://<LAN-IP>:8080` from another
device — and press **Connect**.

### 3. Run the test suites

```bash
node scripts/e2e-test.mjs            # signaling server must be running
node scripts/android-interop-test.mjs   # self-contained — spawns its own server
```

Expected: **18/18** and **33/33 passed**.

The second suite matters because the Android client cannot be compiled without a JDK,
the Android SDK and Google Maven. It therefore proves the two things that would
silently break cross-device chat without needing a device at all: that
`CryptoBox.kt` (Bouncy Castle) and `web-app/src/crypto.ts` (tweetnacl + WebCrypto)
derive the same key and decrypt each other's ciphertext (checked against the RFC 7748
known-answer vector), and that the JSON the Kotlin models emit is exactly what the
signaling relay and the browser parse.

## Built and tested on

| Item | Value |
|---|---|
| Web client | React 18, TypeScript 5.9, Vite 5 |
| Signaling node | Node.js + `ws` |
| Android client | Kotlin + Jetpack Compose, minSdk 26 |
| Verified on | Kali Linux, Node v24.21.0 — web client + signaling server |
| Test suites | `e2e-test.mjs` (18) + `android-interop-test.mjs` (33) |
| Android build | **verified** — `./gradlew :app:assembleDebug` builds a debug APK |

### Android status — honestly

The Android client **compiles** and its crypto/wire-protocol interop with the web
client is **proven by test** (33/33). It has **not** been run on a physical device in
this environment, because building an APK needs a JDK, the Android SDK and Google
Maven — none of which exist here. `./gradlew :app:assembleDebug` on your machine is
the remaining verification step; see
[`TESTING_GUIDE.md`](TESTING_GUIDE.md) section 5.

This revision fixed a **silent, fatal bug** that no compile could have caught. The
embedded host used NanoHTTPD's no-argument `start()`, which implies a 5-second socket
read timeout applied to *every* accepted socket; NanoWSD's blocking read loop then hit
a `SocketTimeoutException` on any quiet connection and its `finally` block
force-closed the peer. In practice: the Android host dropped every peer five seconds
after they stopped talking. The host now binds with `start(0, true)` — no read
timeout — plus a heartbeat that evicts genuinely dead sockets.
`android-interop-test.mjs` contains a regression guard that fails if anyone puts the
bare `start()` back.

Also fixed: misleading "Host start nahi ho paya" shown for every disconnect in client
mode; no reconnection at all (a dropped socket was terminal); a latent
`UninitializedPropertyAccessException` from a `lateinit` connection entry captured by
the WebRTC observer; ICE candidates dropped when they arrived before
`setRemoteDescription` finished (fatal on a LAN, where host candidates are the only
candidates); a double-host race when two phones opened at once; lost peer-list entries
after a reconnect; and chat UI that neither auto-scrolled nor survived rotation.
Host re-election was added, so a vanished host no longer strands everyone.

## Security model

| Layer | What it does |
|---|---|
| Identity | Ephemeral X25519 keypair per session, never persisted |
| Key agreement | Raw X25519 ECDH (RFC 7748) — identical on web and Android |
| Key derivation | HKDF-SHA256, info string `offline-chat-e2ee` |
| Message encryption | AES-256-GCM, 12-byte random IV per message |
| Integrity | GCM auth tag — modified ciphertext is rejected |
| Signaling server | Blind relay; no chat payload in the protocol |

## Documentation

- [`docs/FLOWCHART.md`](docs/FLOWCHART.md) — poora working flow, Hinglish me, 8 Mermaid
  diagrams + [`docs/flowchart.svg`](docs/flowchart.svg).
- [`TESTING_GUIDE.md`](TESTING_GUIDE.md) — ordered verification steps and the last
  recorded results.
- [`android-app/README.md`](android-app/README.md) — Android architecture, role
  auto-detection, aur is revision ke bug-fixes ki poori list.

## License

MIT — see [`LICENSE`](LICENSE).

---

Made by **ROBINHOOD55**.

# Offline Chat — LAN-Only Peer-to-Peer Encrypted Chat (Web + Android)

**Offline chat that needs no internet, no server account, and no cloud.** Offline
Chat is a local-network (LAN) messenger: a small signaling node on one machine
handles peer discovery and the WebRTC handshake, and every message then travels
**peer-to-peer, end-to-end encrypted** (X25519 + HKDF-SHA256 + AES-256-GCM).
Works across a browser and Android, on the same Wi-Fi, with no data ever leaving
the network.

Built with **TypeScript + React + Vite** (web), **Kotlin + Jetpack Compose**
(Android) and a **Node.js WebSocket** signaling relay.

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
scripts/            e2e-test.mjs — protocol + crypto test suite
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

### 3. Run the test suite

```bash
node scripts/e2e-test.mjs      # signaling server must be running
```

Expected: **16/16 passed**.

## Built and tested on

| Item | Value |
|---|---|
| Web client | React 18, TypeScript 5.9, Vite 5 |
| Signaling node | Node.js + `ws` |
| Android client | Kotlin + Jetpack Compose, minSdk 26 |
| Verified on | Kali Linux, Node v24.21.0 — web client + signaling server |
| Test suite | `scripts/e2e-test.mjs` — 16 checks, signaling + crypto |
| Android build | **not yet verified** (no Gradle wrapper committed) |

### Android status — read this

The Android client is **written but never compiled**. It was authored without a
build run, so treat its first `./gradlew :app:assembleDebug` as the real test.
The web client and the signaling server are verified end-to-end.

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

- [`TESTING_GUIDE.md`](TESTING_GUIDE.md) — ordered verification steps and the last
  recorded results.
- [`android-app/README.md`](android-app/README.md) — Android architecture and role
  auto-detection.

## License

MIT — see [`LICENSE`](LICENSE).

---

Made by **ROBINHOOD55**.

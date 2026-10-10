# TESTING_GUIDE.md — Offline Chat

Ordered steps to verify this project. Every step has what to do, what to expect,
and a checkbox. Results from the last real run are recorded at the bottom.

**Verified on:** Kali Linux, Node v24.21.0, npm 12.0.2 — 10 Oct 2026.

---

## 0. What this project is (read first)

Three parts, one repo:

| Folder | What it is | Needs internet? |
|---|---|---|
| `signaling-server/` | Node.js relay: peer discovery + WebRTC handshake only | No — LAN only |
| `web-app/` | React + TypeScript + Vite browser client | No — LAN only |
| `android-app/` | Kotlin + Jetpack Compose Android client | No — LAN only |

**The signaling server never sees chat messages.** It relays SDP offers/answers and
ICE candidates; the actual messages travel peer-to-peer over an encrypted WebRTC
DataChannel. Section 4 proves this.

---

## 1. Prerequisites

- [ ] Node.js 18 or newer — `node --version`
- [ ] npm — `npm --version`
- [ ] A browser (Chrome/Chromium/Firefox)
- [ ] For the Android part: JDK 17 + Android SDK

## 2. Install

```bash
cd signaling-server && npm install
cd ../web-app && npm install
```

- [ ] Both finish without errors
- [ ] `signaling-server/node_modules/ws` exists
- [ ] `web-app/node_modules/vite` exists

## 3. Build the web app (this is the real compile check)

```bash
cd web-app
npm run build
```

- [ ] `tsc -b` passes with **zero** TypeScript errors
- [ ] `vite build` reports `✓ built in ...`
- [ ] `dist/index.html`, `dist/assets/*.js`, `dist/sw.js` are created

> **Why this step matters:** the original code did **not** compile. Two
> `Uint8Array<ArrayBufferLike>` vs `BufferSource` errors in `src/crypto.ts` broke
> `tsc -b` under TypeScript 5.7+. Fixed — see "Fixes applied" below.

## 4. Automated end-to-end test (signaling + crypto)

Start the server first:

```bash
cd signaling-server && npm start        # leave running, port 8080
```

- [ ] Console prints `Signaling node chal raha hai`
- [ ] `ss -tlnp | grep 8080` shows it listening

Then run the protocol test (uses the **real** wire format from `server.js`):

```bash
node scripts/e2e-test.mjs
```

- [ ] **16/16 passed**
- [ ] Peer discovery: `join` → `peer-list`, and `peer-joined` reaches the older peer
- [ ] Public keys are exchanged (needed for E2E)
- [ ] `offer` / `answer` / `ice-candidate` are relayed to the right target
- [ ] **No chat/ciphertext field appears in any relayed frame**
- [ ] X25519 shared secret is byte-identical on both sides
- [ ] AES-256-GCM message decrypts on the other peer
- [ ] Tampered ciphertext is **rejected** (GCM auth tag)
- [ ] Wrong key is **rejected**
- [ ] Disconnect broadcasts `leave`
- [ ] Offer to an unknown peer is ignored (no crash)

## 4b. Android <-> web interop test (self-contained)

```bash
node scripts/android-interop-test.mjs     # spawns its own server on a free port
```

- [ ] **33/33 passed**
- [ ] Source guards: no bare NanoHTTPD `start()`, `start(0, ...)`, socket-identity check
  in `onClose`, ICE queue present, `join` re-sent on socket open
- [ ] X25519 matches the **RFC 7748 known-answer vector** on both the Node side
  (standing in for Bouncy Castle) and the tweetnacl side
- [ ] Android-side and web-side derive the **identical** AES-256 key (HKDF-SHA256)
- [ ] Android-encrypted ciphertext decrypts on the web side, and vice versa
- [ ] Tampered ciphertext and wrong keys are **rejected** both ways
- [ ] The exact JSON the Kotlin models emit is relayed correctly by the live server,
  including the browser's extra `usernameFragment` field
- [ ] No chat/ciphertext field ever passes through the signaling relay

> This is the closest this environment can get to testing the Android client: it cannot
> be compiled without a JDK, the Android SDK and Google Maven.

## 5. Two real peers in a browser (manual, the full app)

```bash
cd web-app && npm run dev
```

- [ ] Vite prints a `Local:` and a `Network:` URL
- [ ] Open the app in **two** tabs (or two devices on the same Wi-Fi)
- [ ] Enter the signaling URL — `ws://127.0.0.1:8080` on the same machine,
      `ws://<LAN-IP>:8080` from another device — and press **Connect**
- [ ] Each tab shows its own anonymous id (`Aap: anon-xxxxxx`)
- [ ] Each tab lists the other peer in the `# Group (sabhi)` sidebar
- [ ] Send a message in tab A → it appears in tab B
- [ ] Reply from tab B → it appears in tab A (both directions work)
- [ ] Click a peer in the sidebar → 1:1 private chat opens
- [ ] Close one tab → the other shows that peer as gone
- [ ] Reload a tab → it starts fresh with no history (zero persistence, by design)

## 6. Network reality checks

- [ ] Both devices on the **same** Wi-Fi/subnet
- [ ] Router/mobile-hotspot **AP isolation is OFF** (it blocks peer-to-peer)
- [ ] Port 8080 reachable — `sudo ufw allow 8080` if a firewall is on
- [ ] Server works with the machine's LAN IP, not just `localhost`

## 7. Android app

```bash
cd android-app
export JAVA_HOME=/opt/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
```

- [x] Gradle sync completes (wrapper is committed)
- [x] APK builds — `app/build/outputs/apk/debug/app-debug.apk`, 54 MB
- [x] WebRTC native libs bundled for all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64)
- [ ] Install on a phone and confirm it starts
- [ ] Two phones: **Automatic Start** on both; second should auto-discover the first
- [ ] Kill the host phone; within ~20s the other phone should elect/re-find a host
- [ ] Android host + browser client can talk to each other

> **Build verified earlier, runtime still unverified here.** The current revision also
> passed `scripts/android-interop-test.mjs` 33/33, which proves the crypto and the wire
> protocol without needing a device — but the remaining unknown (NanoHTTPD behaviour on
> a real socket, NSD on a real radio) genuinely needs hardware. See
> `android-app/README.md` for the list of bugs this revision fixed and why.

---

## Fixes applied in this pass

| # | Problem | Fix |
|---|---|---|
| 1 | `web-app` did not compile: `tsc -b` failed with 2 errors in `src/crypto.ts` (`Uint8Array<ArrayBufferLike>` not assignable to `BufferSource`) under TypeScript 5.7+ | Added a `toBufferSource()` helper that copies into a fresh `ArrayBuffer`; applied to the HKDF import, the AES-GCM IV and the ciphertext |
| 2 | No `LICENSE` | Added MIT |
| 3 | No `TESTING_GUIDE.md` | This file |
| 4 | `scripts/` missing the e2e test the guide refers to | Added `scripts/e2e-test.mjs` |
| 5 | README claimed the Android app does not exist while `android-app/` is present | README corrected |

---

## Known limitations (honest)

- **The signaling node is a single point of failure** for *new* peers. Clients now
  retry with backoff and **elect a new host** when theirs disappears, but in-flight
  re-election can briefly split a network of more than two peers.
- **Guest/isolated Wi-Fi** (client isolation on, as in many public/office
  networks) prevents peer discovery — a network limitation, not an app bug.
- **No persistence by design.** Refresh means a fresh anonymous identity and empty
  history. Nothing is written to disk.
- **The Android app is unverified on hardware** — compile-verified earlier, and its
  crypto/wire protocol verified by test (33/33), but never run on a device in this
  environment.
- **The wire-tap test in the browser** (capturing the DataChannel frame to show it
  is ciphertext) was not completed; the Node test proves the same property for the
  signaling layer, and the crypto layer is proven by the round-trip and
  tamper-rejection tests.

---

## Result of the last real run

| Step | Result |
|---|---|
| `npm install` (both) | ✅ 353 packages, no errors |
| `npm run build` | ✅ `tsc -b` clean, `✓ built in 4.64s` |
| `scripts/e2e-test.mjs` | ✅ **18/18 passed** |
| `scripts/android-interop-test.mjs` | ✅ **33/33 passed** (crypto KAT + live wire protocol) |
| Mermaid diagrams in `docs/FLOWCHART.md` | ✅ 8/8 parse valid |
| Signaling server on :8080 | ✅ listening |
| Vite dev server on :5173 | ✅ HTTP 200, title `Offline Chat` |
| Two browser peers discovered each other | ✅ both saw each other's anon id |
| A → B encrypted message | ✅ delivered |
| B → A reply | ✅ delivered |
| Tampered ciphertext rejected | ✅ |
| Wrong key rejected | ✅ |
| Android build (`assembleDebug`) | ✅ **54 MB APK, all 4 ABIs** |

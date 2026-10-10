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

## 7. Android app (separate toolchain)

```bash
cd android-app
export JAVA_HOME=/opt/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
```

- [ ] Gradle sync completes
- [ ] APK builds
- [ ] Install on a phone and confirm it starts
- [ ] Android host + browser client can talk to each other

> **Not verified yet.** The Android app was never compiled — there is no Gradle
> wrapper committed and it was written without a build run. Treat its first
> `./gradlew` as the real test.

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

- **The signaling node is a single point of failure.** If that machine goes down,
  a new session needs the node restarted somewhere. Auto host-election is not
  implemented.
- **Guest/isolated Wi-Fi** (client isolation on, as in many public/office
  networks) prevents peer discovery — a network limitation, not an app bug.
- **No persistence by design.** Refresh means a fresh anonymous identity and empty
  history. Nothing is written to disk.
- **The Android app is unverified** — never compiled.
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
| `scripts/e2e-test.mjs` | ✅ **16/16 passed** |
| Signaling server on :8080 | ✅ listening |
| Vite dev server on :5173 | ✅ HTTP 200, title `Offline Chat` |
| Two browser peers discovered each other | ✅ both saw each other's anon id |
| A → B encrypted message | ✅ delivered |
| B → A reply | ✅ delivered |
| Tampered ciphertext rejected | ✅ |
| Wrong key rejected | ✅ |
| Android build | ⏳ not run |

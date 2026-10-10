# Offline Chat — Android App

Web app ke saath **cryptographically interoperable** (dono X25519 + HKDF-SHA256 +
AES-256-GCM) aur same signaling wire-protocol (JSON) follow karta hai. Flow charts ke
liye [`docs/FLOWCHART.md`](../docs/FLOWCHART.md) dekhiye.

> ⚠️ **Sabse zaroori baat, honestly:** is sandbox me na JDK hai, na Android SDK, na
> Google Maven tak pahunch — isliye **APK yahan compile/run karke test nahi ho saka**.
> Jo yahan prove kiya gaya hai wo hai **crypto + wire-protocol interop**:
> `node ../scripts/android-interop-test.mjs` (33/33) Bouncy Castle wale crypto path ko
> Node ke X25519/HKDF/AES-GCM se model karke web client ke saath milata hai, aur Kotlin
> models ka exact JSON live signaling server se guzarta hai.
> **Aapki machine par pehla Gradle build hi asli compile-check hai.**
> `./gradlew :app:assembleDebug` chalao; koi red error aaye to error message paste kar
> dena.

---

## Architecture (short recap)

- **Role auto-detect**: App start hote hi **2.5s se 6s ke beech ka random window** NSD/mDNS
  scan karta hai.
  - Koi existing host mila → usse **client** ban kar connect ho jaata hai.
  - Kuch nahi mila → khud **host** ban jaata hai (embedded signaling server port 8080,
    NSD advertise, aur khud bhi `127.0.0.1` par client ki tarah connect — isse poora
    chat-logic host/peer dono cases me same rehta hai).
- **Random window kyun?** Fixed 3s me do phone ek saath khulte to dono ko kuch na milta
  aur **dono host** ban jaate — do alag islands. Jitter se ek pehle host banta hai,
  doosra use dhundh leta hai.
- **Host chala gaya to?** Ab **host re-election** hai: client 4 retry ke baad phir se
  scan karta hai; koi naya host mila to usse jud jaata hai, warna khud host ban jaata
  hai. (Pehle sabko app restart karna padta tha.)
- Web browser NSD discover nahi kar sakta, isliye web app "Manually Connect" se
  `ws://<android-host-ip>:8080` daal kar isi Android host se jud sakta hai — **yehi
  Android ↔ Web interoperability ka tareeka hai.**

## Setup (Android Studio)

1. Android Studio me **Open** → `android-app` folder select karo.
2. Gradle sync karo (wrapper 8.9 bundled hai).
3. Sync ke baad **Run** (▶) — test ke liye kam se kam do real devices/emulators, same
   Wi-Fi par.

## Test kaise karein

1. Device A par app kholo → **Automatic Start**. Window khatam hote hi host ban jaayega.
2. Device B par bhi **Automatic Start** → Device A khud discover ho kar connect hoga.
3. Group (# Group) aur 1:1 chat dono try karo.
4. Web app ke saath: web app me `ws://<Device-A-ka-LAN-IP>:8080` daalo (IP phone ki
   Wi-Fi settings me milega).
5. Host wala phone band karo → doosre devices ko kuch second me **naya host** mil jaana
   chahiye, chat bina restart ke chalu rehni chahiye.

## Is version me kya hai

- ✅ Automatic anonymous ID (`anon-xxxxxx`) + X25519 keypair per session
- ✅ NSD/mDNS auto-discovery (Android↔Android), **MulticastLock** ke saath
- ✅ Embedded local signaling server (host role, auto-detect)
- ✅ WebRTC DataChannel P2P chat (1:1 + group/broadcast)
- ✅ E2EE — X25519 + HKDF-SHA256 + AES-256-GCM (web app ke saath byte-for-byte)
- ✅ Web app ke saath manual-URL interop
- ✅ **Auto-reconnect** with exponential backoff + **host re-election**
- ✅ **ICE candidate queueing** (setRemoteDescription se pehle aaye candidates phenkte nahi)
- ✅ Honest delivery state — na bheja gaya message laal nishan ke saath dikhta hai
- ✅ Chat auto-scroll, rotation-safe UI state, "Dobara Scan" button
- ✅ Host ka WebSocket server ab idle peers ko **nahi** kaatta (neeche dekhiye)

## Abhi bhi nahi hai

- ❌ File sharing, multiple rooms, Bluetooth mesh (PRD Phase 2/3)
- ❌ Message persistence (by design — RAM-only, refresh = nayi identity)
- ❌ Runtime location permission for NSD (kisi Android build par zaroorat pade to add karna hoga)

---

## Is revision me fix kiye gaye bugs

Yeh list isliye likhi hai taaki aage koi inhe **dobara introduce na kar de**.

### 1. 🔴 Fatal: NanoHTTPD ka 5-second read timeout (sabse bara bug)

`SignalingServer` pehle `NanoWSD(port)` ke baad `start()` bulata tha. NanoHTTPD 2.3.1 me:

```java
public void start() throws IOException { start(NanoHTTPD.SOCKET_READ_TIMEOUT); }  // 5000
// accept loop me:  if (timeout > 0) finalAccept.setSoTimeout(timeout);           // :1771
```

aur NanoWSD ka WebSocket **usi socket par blocking read** karta hai:

```java
try { while (state == OPEN) handleWebsocketFrame(WebSocketFrame.read(in)); }
catch (IOException e) { onException(e); }
finally { doClose(InternalServerError, ...); }   // hamesha chalta hai
```

`SocketTimeoutException extends IOException`, isliye **5 second chup rehne par healthy
connection force-close** ho jaati thi. Yaani Android host har peer ko 5s baad kaat deta
tha — chat "chal raha" lagta, phir sab silently disconnect. OkHttp client ka 15s ping
ise bacha nahi sakta tha (15 > 5).

**Fix:** `startServer()` ab `start(0, true)` bulata hai (`timeout > 0` guard ki wajah se
`setSoTimeout` lagta hi nahi), plus ek heartbeat thread jo sach-me-mare sockets ko
ping karke evict karta hai. `android-interop-test.mjs` me **regression guard** hai jo
bare `start()` wapas aane par fail hota hai.

### 2. Reconnect bilkul nahi tha

WebSocket tootta to app hamesha ke liye atak jaata tha. Ab exponential backoff
(1s→8s, 4 koshishein), phir auto-mode me **host re-election**. Har naye socket par
dobara `join` bheja jaata hai — warna server ke `peers` map me hum hote hi nahi aur
kisi ki peer-list me kabhi nahi aate.

### 3. Har disconnect par galat message

Client mode me bhi "Host start nahi ho paya" dikhta tha. Ab status role-aware aur
actionable hai, aur retry countdown bhi dikhta hai.

### 4. Latent crash: `lateinit var entry`

`PeerConnection.Observer` ek `lateinit` connection entry ko capture karta tha jo
observer banne ke **baad** assign hoti thi — early callback par
`UninitializedPropertyAccessException`. Ab holder pehle banta hai, `PeerConnection`
baad me. Structurally impossible ho gaya.

### 5. ICE candidates phenk diye jaate the

`addIceCandidate` ko remote description se pehle call karne par wo **reject** hota hai.
LAN par STUN/TURN nahi hote — sirf host candidates — isliye ek candidate gira matlab
DataChannel kabhi open hi nahi hota. Ab candidates queue hote hain aur
`onSetSuccess` ke baad drain.

### 6. Reconnect par purana socket naye ko kaat deta tha

Server `onClose` par roster se peer hata deta tha. Reconnect = same anon id, naya
socket; purane socket ka `onClose` baad me aata aur **zinda connection ko roster se
hata kar jhootha `leave` broadcast** karta tha. Ab `onClose` identity check karta hai:
`peerSockets[id] === this`.

### 7. UI: auto-scroll, rotation, duplicate ids

- Naya message viewport ke neeche render hota tha, user ko manually scroll karna padta
  tha → ab `animateScrollToItem`.
- Rotation par `remember` wala selected peer aur `started` flag reset ho jaata tha →
  ab `rememberSaveable`.
- Ek hi millisecond me bheje do messages ka id same (`self-$timestamp`) → ab monotonic
  sequence.

---

## Dependencies — verified

| Dependency | Version | Note |
|---|---|---|
| `io.getstream:stream-webrtc-android` | 1.3.9 | Maven Central par **maujood** (tag verified) |
| `org.nanohttpd:nanohttpd-websocket` | 2.3.1 | `fi.iki.elonen.NanoWSD` sahi package hai isi version ke liye (master par `org.nanohttpd.*` hai) |
| `org.bouncycastle:bcprov-jdk18on` | 1.78.1 | X25519/HKDF API stable |
| AGP / Gradle / Kotlin / Compose compiler | 8.5.2 / 8.9 / 1.9.24 / 1.5.14 | mutually compatible set |

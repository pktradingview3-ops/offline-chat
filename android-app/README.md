# Offline Chat — Android App

PRD Section 6 ke structure ke hisaab se banaya gaya. Web app ke saath **cryptographically
interoperable** hai (dono X25519 + HKDF-SHA256 + AES-256-GCM use karte hain) aur same
signaling wire-protocol (JSON) follow karta hai.

> ⚠️ **Sabse zaroori baat, honestly:** is sandbox me na Android SDK hai, na Gradle ka
> internet access — isliye **is code ko yahan compile/run karke test nahi kiya ja saka**.
> Maine har API (WebRTC, NanoHTTPD, Bouncy Castle, NSD) ko carefully, well-documented aur
> widely-used patterns follow karke likha hai, lekin ek naye Android project ka **pehla
> Gradle sync/build hi iska asli test hoga**. Chhoti dependency-version ya API-signature
> mismatch aana bilkul normal hai badi Android projects me — agar Android Studio koi red
> error dikhaye, error message mujhe paste kar dena, turant fix karunga.

---

## Architecture (short recap)

- **Role auto-detect**: App start hote hi 3 second ke liye network scan karta hai (NSD/mDNS).
  - Koi existing host mila → usse client ban kar connect ho jaata hai.
  - Kuch nahi mila → khud host ban jaata hai (apna embedded signaling server start karta hai,
    NSD se advertise karta hai, aur khud bhi usi se `127.0.0.1` par client ki tarah connect
    hota hai — isse poora chat-logic host/peer dono cases me same rehta hai).
- Web browser NSD discover nahi kar sakta, isliye web app "Manually Connect" se
  `ws://<android-host-ip>:8080` daal kar isi Android host se juud sakta hai — **yehi
  Android ↔ Web interoperability ka tareeka hai.**

## Setup (Android Studio)

1. Android Studio me **Open** → `android-app` folder select karo.
2. Studio Gradle wrapper khud generate/sync karega (ya "Gradle Sync" par click karo).
   Agar wrapper missing ka error aaye: **File → Sync Project with Gradle Files**, ya
   terminal me (agar system par Gradle installed hai):
   ```bash
   gradle wrapper --gradle-version 8.7
   ```
3. Sync complete hone ke baad **Run** (▶) dabao — kam se kam do real devices ya
   emulators (dono same Wi-Fi/network par) chahiye honge test ke liye.

## Test kaise karein

1. Device A par app kholo → "Automatic Start" dabao. 3 second baad ye khud host ban
   jaayega (koi doosra host na mile to).
2. Device B par bhi app kholo → "Automatic Start" — isse Device A discover ho jaana
   chahiye aur automatically connect ho jaana chahiye.
3. Group ya 1:1 chat try karo.
4. Web app ke saath test karne ke liye: web app kholo, "Connect" screen me
   `ws://<Device-A-ka-LAN-IP>:8080` daalo (Device A ka IP phone ki Wi-Fi settings me
   milega).

## Jo is version me hai (MVP, web app jaisa hi)

- ✅ Automatic anonymous ID
- ✅ NSD/mDNS auto-discovery (Android↔Android)
- ✅ Embedded local signaling server (host role, auto-detect)
- ✅ WebRTC DataChannel P2P chat (1:1 + group/broadcast)
- ✅ E2EE — X25519 + HKDF-SHA256 + AES-256-GCM (web app ke saath interoperable)
- ✅ Web app ke saath manual-URL interop

## Jo abhi nahi hai

- ❌ Host chale jaane par automatic re-election (koi doosra device naya host nahi
  banega apne aap — is case me sabko app restart karna hoga)
- ❌ File sharing, multiple rooms, Bluetooth fallback (PRD Phase 2/3)
- ❌ Runtime permission handling UI (INTERNET/NSD permissions install-time granted
  hote hain, koi runtime prompt nahi chahiye — lekin agar kabhi location permission
  ki zaroorat pade NSD ke liye kisi Android version par, wo add karna hoga)

## Highest-risk areas (agar build fail ho to pehle yahan dekhna)

1. **WebRTC library version** (`io.getstream:stream-webrtc-android:1.3.9`) — agar
   Maven Central par ye exact version na mile, `libs.versions` check karke latest
   `1.3.x` use kar lena, API same rehta hai.
2. **NanoHTTPD WebSocket** (`org.nanohttpd:nanohttpd-websocket:2.3.1`) — well-established
   library hai, lekin agar `NanoWSD` ya `WebSocketFrame` naming me dikkat aaye,
   NanoHTTPD ki GitHub wiki check karna.
3. **Bouncy Castle** — `X25519Agreement`/`HKDFBytesGenerator` API stable hai kai saalon
   se, risk kam hai.
4. **Compose/Kotlin/AGP version combinations** — maine ek reasonably recent, mutually
   compatible set choose kiya hai (AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00),
   lekin Android Studio ka apna bundled version thoda alag ho to warning/suggestion
   aa sakta hai version bump karne ka — wo safely accept kar sakte ho.

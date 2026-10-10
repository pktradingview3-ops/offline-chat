# Offline Chat — App kaise kaam karta hai (Flow Charts)

Poora system **Hinglish** me, diagrams ke saath. GitHub in diagrams ko apne aap render
kar deta hai (Mermaid). Har diagram ke neeche ek short explanation bhi hai.

> **Ek line me:** ek device *host* banta hai aur LAN par ek chhota signaling node chalata
> hai. Baaki devices use dhundh kar usse jud jaate hain. Signaling node sirf *parichay*
> (handshake) karwata hai — asli chat **seedha device-to-device, encrypted** jaata hai.

---

## 1. Poora architecture — kaun kya karta hai

```mermaid
flowchart LR
    subgraph LAN["Aapka Local Network (Wi-Fi / Hotspot) — internet ki zaroorat nahi"]
        direction TB

        subgraph A["Android A — HOST"]
            A1["Offline Chat app"]
            A2["Embedded Signaling Server<br/>NanoWSD, port 8080"]
            A3["NSD/mDNS advertise<br/>_offlinechat._tcp."]
            A1 --- A2
            A2 --- A3
        end

        subgraph B["Android B — CLIENT"]
            B1["Offline Chat app"]
            B2["NSD/mDNS discovery"]
            B1 --- B2
        end

        subgraph W["Web Browser — CLIENT"]
            W1["React web app"]
        end

        N["Node.js signaling server<br/>(optional, PC par)"]
    end

    B2 -. "host dhundha" .-> A3
    B1 == "WebSocket<br/>sirf handshake" ==> A2
    W1 == "WebSocket<br/>ws://A-ka-IP:8080" ==> A2
    A1 <-. "WebRTC DataChannel<br/>E2E ENCRYPTED CHAT" .-> B1
    A1 <-. "WebRTC DataChannel<br/>E2E ENCRYPTED CHAT" .-> W1
    B1 <-. "WebRTC DataChannel<br/>E2E ENCRYPTED CHAT" .-> W1
```

**Samajhne wali baat:** moti (`==>`) line sirf handshake ke liye hai. Chat hamesha
dohri (`<-.->`) encrypted DataChannel par jaata hai — **host ke server se hokar nahi**.
Isliye host bhi aapka message padh nahi sakta.

Browser mDNS discover nahi kar sakta, isliye web app ko host ka IP **manually** daalna
padta hai (`ws://192.168.1.5:8080`). Android-to-Android apne aap discover ho jaata hai.

---

## 2. App khulte hi kya hota hai — role auto-detection

Yeh sabse important flow hai. App khud decide karta hai ki use **host** banna hai ya
**client**.

```mermaid
flowchart TD
    S(["App khula"]) --> SS{"Pehli baar<br/>setup screen?"}
    SS -- "Automatic Start" --> ID["Naya anonymous ID banao<br/>anon-xxxxxx<br/>+ X25519 keypair"]
    SS -- "Manually Connect" --> MU["User ka URL normalize karo<br/>port na ho to :8080 jodo"]
    MU --> CC

    ID --> J["Dispatchers.IO par jaao<br/>(main thread block na ho)"]
    J --> W["Random wait window chuno<br/>2.5s se 6s ke beech"]
    W --> D["NSD/mDNS discovery shuru<br/>MulticastLock acquire"]

    D --> Q{"Window khatam hone se<br/>pehle koi host mila?"}

    Q -- "HAAN, host mila" --> OWN{"Wo host kya<br/>hum khud hain?"}
    OWN -- "haan, apna hi IP" --> D
    OWN -- "nahi, doosra device" --> CC["CLIENT ban jao<br/>ws://host-ip:port par connect"]

    Q -- "NAHI, koi nahi mila" --> H["HOST ban jao"]
    H --> H1["Embedded signaling server<br/>start(0, true) — koi read timeout nahi"]
    H1 --> H2{"Port 8080 bind<br/>ho paya?"}
    H2 -- "nahi, busy" --> HE["Error dikhao:<br/>Manually Connect try karein"]
    H2 -- "haan" --> H3["NSD par advertise karo<br/>_offlinechat._tcp."]
    H3 --> H4["Khud bhi 127.0.0.1:8080 par<br/>client ki tarah connect ho jao"]
    H4 --> CC

    CC --> RDY(["Chat screen ready"])
    HE --> RDY
```

### Random wait window kyun? (yeh ek asli bug fix hai)

Pehle window **fixed 3 second** thi. Do phone ek saath kholiye to:

| Fixed 3s (purana) | Random 2.5–6s (naya) |
|---|---|
| Dono 3s scan karte hain | Ek ko chhoti window milti hai, ek ko badi |
| Koi advertise nahi kar raha, isliye dono ko kuch nahi milta | Chhoti window wala pehle **host** ban kar advertise shuru karta hai |
| **Dono host ban jaate hain** → do alag islands, kabhi nahi milte | Badi window wala abhi bhi scan kar raha hai → use host **mil jaata hai** |
| Recovery: app force-close karo | Recovery: kuch nahi karna, apne aap jud jaata hai |

**Host khud bhi `127.0.0.1` par client ki tarah connect kyun hota hai?** Taaki host aur
client dono ka chat-logic **bilkul same** rahe — ek hi code path, do tarah ke bugs nahi.

---

## 3. Do device ka milna — discovery + WebRTC handshake

```mermaid
sequenceDiagram
    autonumber
    participant B as Android B (naya peer)
    participant S as Signaling node (host par)
    participant A as Android A (pehle se juda)

    Note over B: NSD se host ka IP:port mila
    B->>S: WebSocket connect
    S-->>B: socket open

    B->>S: join {peerId: anon-b2, publicKey: <X25519 pub>}
    Note over S: publicKey RAM me rakha,<br/>disk par kabhi nahi

    S-->>B: peer-list [{peerId: anon-a1, publicKey}]
    S-->>A: peer-joined {peerId: anon-b2, publicKey}

    Note over A: Jaan-boojh kar offer NAHI bhejta.<br/>Naya peer hi bhejega — isse<br/>"glare" (double connection) nahi hoti.

    B->>B: PeerConnection banao + DataChannel "chat"
    B->>S: offer {from: b2, to: a1, sdp}
    S->>A: offer (blind relay — content padha nahi jaata)

    A->>A: setRemoteDescription(offer)
    Note over A: Tab tak aaye ICE candidates<br/>queue me rakho, phenko nahi
    A->>S: answer {from: a1, to: b2, sdp}
    S->>B: answer (blind relay)

    B->>A: ice-candidate (host candidate)
    A->>B: ice-candidate (host candidate)

    Note over A,B: ICE seedha LAN par milta hai —<br/>koi STUN/TURN server nahi chahiye
    A-->>B: DataChannel OPEN
    B-->>A: DataChannel OPEN
    Note over A,B: Ab dono peer ONLINE (hara dot)
```

### Yahan do cheezein dhyan dene layak hain

**1. Offer sirf ek taraf se.** Server naye peer ko `peer-list` bhejta hai aur purane
peers ko sirf `peer-joined`. Naya peer hi offer bhejta hai. Agar dono taraf se offer
jaata to do alag PeerConnection ban jaate ("glare") aur messages duplicate aate.

**2. ICE candidate queue.** `setRemoteDescription` **asynchronous** hai. Agar ICE
candidate usse pehle pahunch jaaye aur seedha `addIceCandidate` call ho to wo **reject**
ho jaata hai. LAN par STUN/TURN nahi hote, isliye sirf *host candidates* hi hote hain —
ek bhi candidate gira to DataChannel **kabhi open hi nahi hota**. Isliye candidates ko
queue karke `onSetSuccess` ke baad drain kiya jaata hai.

---

## 4. Message bhejna — end-to-end encryption ka poora safar

```mermaid
sequenceDiagram
    autonumber
    participant U as Aap (Android A)
    participant E as ChatEngine
    participant C as CryptoBox (Bouncy Castle)
    participant D as WebRTC DataChannel
    participant P as Android B / Browser
    participant C2 as CryptoBox (B)

    U->>E: "Namaste" + recipientId
    E->>C: encryptMessage(text, B-ka-publicKey, A-ka-secretKey)

    Note over C: 1. X25519 raw ECDH (RFC 7748)<br/>sharedSecret = scalarMult(mySecret, theirPublic)
    Note over C: 2. HKDF-SHA256<br/>salt = khaali, info = "offline-chat-e2ee"<br/>=> 32-byte AES key
    Note over C: 3. AES-256-GCM<br/>12-byte random IV har message ke liye

    C-->>E: {nonce, ciphertext+tag}

    E->>E: WireEnvelope banao<br/>{type, senderId, recipientId, timestamp, nonce, ciphertext}
    E->>D: send(JSON envelope)

    Note over E,D: Signaling node is raste me<br/>KAHI NAHI hai — seedha P2P

    D->>P: encrypted envelope
    P->>C2: decryptMessage(nonce, ciphertext, A-ka-publicKey, B-ka-secretKey)
    Note over C2: Wahi 3 step ulte order me.<br/>Shared secret dono taraf SAME banta hai.
    C2-->>P: "Namaste"
    P->>P: UI me dikhao

    Note over C,C2: GCM auth tag verify hota hai —<br/>ek bit bhi badla to decrypt NULL,<br/>message chup-chaap drop
```

### Encryption kyun dono platform par ek jaisa kaam karta hai

| Step | Android | Web browser | Same kyun? |
|---|---|---|---|
| Key agreement | Bouncy Castle `X25519Agreement` | tweetnacl `scalarMult` | Dono **RFC 7748** implement karte hain |
| Key derivation | `HKDFBytesGenerator(SHA256)` | WebCrypto `HKDF` | Dono **RFC 5869**, same salt/info |
| Cipher | `Cipher("AES/GCM/NoPadding")` | WebCrypto `AES-GCM` | Dono **NIST SP 800-38D** |
| Info string | `"offline-chat-e2ee"` | `"offline-chat-e2ee"` | Hard-coded, dono jagah same |

Algorithm standardized hain, isliye library alag hone par bhi output **byte-for-byte**
same aata hai. Yeh cheez `scripts/android-interop-test.mjs` me RFC 7748 ke official
test-vector ke saath verify ki gayi hai.

---

## 5. Group chat vs 1:1 private chat

```mermaid
flowchart TD
    M(["Message likha + Bhejein dabaya"]) --> R{"recipientId<br/>kya hai?"}

    R -- "broadcast" --> G["GROUP: sabhi peers ko bhejo"]
    G --> G1["Har peer ke liye ALAG encryption<br/>(har peer ka shared secret alag hota hai)"]
    G1 --> G2["envelope.recipientId = broadcast"]
    G2 --> G3["Jitne DataChannel OPEN hain<br/>sab par bhejo"]

    R -- "kisi ek peer ka id" --> P["1:1 PRIVATE: sirf us peer ko"]
    P --> P1["Us peer ke shared secret se encrypt"]
    P1 --> P2["envelope.recipientId = uska id"]

    G3 --> SENT{"Kitne peers tak<br/>actually pahuncha?"}
    P2 --> SENT

    SENT -- "0 (koi channel open nahi)" --> BAD["Bubble dikhega par<br/>LAAL nishan: nahi bheja gaya"]
    SENT -- "1 ya zyada" --> GOOD["Bubble + timestamp"]

    GOOD --> SELF["Aapki apni local list me bhi jodo<br/>(kyunki wo network se wapas nahi aata)"]
    BAD --> SELF

    SELF --> VIEW{"Chat screen par<br/>kaunsa filter?"}
    VIEW -- "# Group khula hai" --> VG["Sirf recipientId == broadcast wale"]
    VIEW -- "koi peer khula hai" --> VP["jisme senderId ya recipientId<br/>wo peer ho"]
```

**Dhyan dein:** group me bhi message **ek saath sabko** nahi jaata — har peer ke liye
alag-alag encrypt hota hai, kyunki har peer ke saath shared secret alag hai. Iska matlab
group me bhi forward secrecy ka structure bana rehta hai: ek peer ki key leak hone se
baaki peers ke messages safe rehte hain.

**"nahi bheja gaya" wala laal nishan kyun zaroori hai?** Pehle message bubble hamesha
normal dikhta tha, chahe DataChannel band ho. User ko lagta tha message chala gaya — wo
dobara koshish hi nahi karta tha. Ab sach dikhta hai.

---

## 6. Connection tootne par kya hota hai — reconnect + host re-election

```mermaid
flowchart TD
    L(["WebSocket toot gaya<br/>onFailure / onClosed"]) --> GEN{"Kya yeh callback<br/>current socket ka hai?"}
    GEN -- "nahi, purana socket" --> DROP["Ignore karo<br/>(warna retry-storm ban jaata hai)"]
    GEN -- "haan" --> ST["connected = false<br/>Status text me wajah dikhao"]

    ST --> NOTE["Peer status NAHI badalte —<br/>DataChannel signaling se<br/>azad hai, chat chalu reh sakta hai"]
    NOTE --> ATT{"Koshish<br/>4 se zyada?"}

    ATT -- "nahi" --> BO["Exponential backoff<br/>1s, 2s, 3s, 4s (max 8s)"]
    BO --> RT["Dobara connect karo"]
    RT --> RJ{"Juda?"}
    RJ -- "haan" --> JOIN["Phir se join bhejo<br/>(server ne humein bhula diya tha)"]
    JOIN --> OK(["Online"])
    RJ -- "nahi" --> ATT

    ATT -- "haan, haar maan li" --> MODE{"Kaunsa mode tha?"}

    MODE -- "Automatic + CLIENT" --> RE["HOST RE-ELECTION"]
    RE --> RE1["Thoda random ruk jao<br/>(sab ek saath host na banein)"]
    RE1 --> RE2["Phir se NSD scan karo"]
    RE2 --> RE3{"Koi naya host mila?"}
    RE3 -- "haan" --> CC["Uske client ban jao"]
    RE3 -- "nahi" --> BH["Khud host ban jao<br/>server + advertise"]
    CC --> OK
    BH --> OK

    MODE -- "Manual URL" --> ERR["Saaf error dikhao:<br/>same Wi-Fi? host app khula hai?<br/>URL sahi hai?"]
    MODE -- "HOST khud tha" --> ERR2["Error: apne embedded server<br/>se connect nahi ho paaya"]

    OK --> DONE(["Chat phir se chalu"])
    ERR --> DONE2(["User Dobara Scan daba sakta hai"])
    ERR2 --> DONE2
```

### Teen problem jo pehle thi, ab nahi

| Pehle | Ab |
|---|---|
| Connection tootta to app **hamesha ke liye atak** jaata | Automatic retry with backoff, phir host re-election |
| Har disconnect par ek hi message: *"Host start nahi ho paya"* — chahe aap client ho | Role ke hisaab se **sahi aur kaam ki** wajah |
| Host chala gaya to sabko app **restart** karna padta tha | Baaki devices apne aap naya host chun lete hain |

**"Phir se join bhejo" kyun zaroori hai?** Reconnect par server ka `peers` map humein
bhula chuka hota hai (socket close par entry hat jaati hai). Bina dobara `join` bheje
hum connected to dikhte, par kisi ki peer-list me **kabhi nahi aate** — yaani chat dead.

---

## 7. Security model — kisko kya pata chalta hai

```mermaid
flowchart LR
    subgraph SEES["Kisko KYA dikhta hai"]
        direction TB
        H["Signaling node (host phone)"]
        R["Router / network sniffer"]
        P["Doosra peer"]
    end

    subgraph HAS["Uske paas kya hota hai"]
        direction TB
        H1["sirf public keys<br/>SDP offer/answer<br/>ICE candidates"]
        R1["sirf encrypted ciphertext<br/>+ handshake"]
        P1["jo message USE bheja gaya<br/>(1:1 me sirf apna)"]
    end

    subgraph NO["Kisi ke paas NAHI hota"]
        direction TB
        N1["plaintext message"]
        N2["private keys"]
        N3["chat history disk par"]
        N4["account / phone number"]
    end

    H --> H1
    R --> R1
    P --> P1
    H1 -.-> NO
    R1 -.-> NO
```

| Cheez | Kahan hai | Kab tak |
|---|---|---|
| Private X25519 key | Sirf RAM, app ke andar | App band = khatam |
| Chat messages | Sirf RAM, dono devices par | App band = khatam |
| Public keys | Signaling node ki RAM me | Node band = khatam |
| Disk par | **Kuch nahi** | — |

**Imaandaari:** yeh app aapke **apne LAN ke andar** anonymous nahi hai — doosre peers
ko aapka session id (`anon-xxxxxx`) aur IP dikhta hai. Jo cheez chhupti hai wo message
ka **content** hai, aur wo bhi LAN ke bahar walon se.

---

## 8. Ek nazar me poora flow

```mermaid
flowchart LR
    A["1. App khula"] --> B["2. Anonymous ID<br/>+ X25519 keypair"]
    B --> C["3. LAN par host<br/>dhundha (NSD)"]
    C --> D{"Mila?"}
    D -- "haan" --> E["4a. Client bano"]
    D -- "nahi" --> F["4b. Host bano<br/>+ advertise"]
    E --> G["5. join + publicKey"]
    F --> G
    G --> H["6. WebRTC handshake<br/>offer/answer/ICE"]
    H --> I["7. Encrypted DataChannel<br/>OPEN"]
    I --> J["8. Chat shuru<br/>1:1 ya Group"]
    J --> K{"Toota?"}
    K -- "haan" --> L["9. Retry / re-elect"]
    L --> C
    K -- "nahi" --> J
```

---

## In diagrams ko dekhna kaise hai

- **GitHub par:** yeh file kholiye — Mermaid diagrams apne aap render ho jaayenge.
- **Locally:** VS Code me *Markdown Preview Mermaid Support* extension, ya
  <https://mermaid.live> par diagram ka code paste kar dijiye.
- **Share karne ke liye:** [`docs/flowchart.svg`](flowchart.svg) — poora flow ek image me,
  WhatsApp/Telegram par bhej sakte hain.

## Code se mapping

| Diagram | Code |
|---|---|
| 2 — role auto-detection | `chat/ChatEngine.kt` → `scanThenDecide()`, `becomeHost()` |
| 3 — handshake | `chat/ChatEngine.kt` → `handleSignal()`, `createConnection()` |
| 4 — encryption | `crypto/CryptoBox.kt` |
| 5 — group vs 1:1 | `chat/ChatEngine.kt` → `sendMessage()`, `ui/ChatScreen.kt` |
| 6 — reconnect | `chat/ChatEngine.kt` → `scheduleReconnect()` |
| Host ka server | `signaling/SignalingServer.kt` |
| NSD discovery | `discovery/NsdHelper.kt` |

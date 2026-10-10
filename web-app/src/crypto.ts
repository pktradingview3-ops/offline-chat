/**
 * End-to-End Encryption module — PRD Section 8 (exact match, dono platforms me).
 *
 * Flow:
 *   1. X25519 raw ECDH (RFC 7748) — `nacl.scalarMult` deterministic hai, isliye Android
 *      (Bouncy Castle X25519Agreement) ke saath byte-for-byte shared secret match karta hai.
 *   2. HKDF-SHA256 se shared secret → AES-256 session key derive hoti hai (Web Crypto API).
 *   3. AES-256-GCM se actual message encrypt/decrypt hota hai.
 *
 * Koi bhi plaintext, key, ya intermediate value is module ke bahar kabhi persist/log nahi hota.
 */
import nacl from 'tweetnacl';
import { encodeBase64, decodeBase64 } from 'tweetnacl-util';

export interface KeyPair {
  publicKey: Uint8Array; // 32 bytes, X25519
  secretKey: Uint8Array; // 32 bytes, X25519
}

// Android app me bhi HKDF "info" string EXACTLY yahi honi chahiye — dono taraf match hona zaroori hai.
const HKDF_INFO = new TextEncoder().encode('offline-chat-e2ee');
const HKDF_SALT = new Uint8Array(0);

export function generateKeyPair(): KeyPair {
  // nacl.box.keyPair() ek standard X25519 keypair generate karta hai — yahi format
  // nacl.scalarMult (raw ECDH) ke saath bhi compatible hai.
  const kp = nacl.box.keyPair();
  return { publicKey: kp.publicKey, secretKey: kp.secretKey };
}

export function publicKeyToBase64(pk: Uint8Array): string {
  return encodeBase64(pk);
}

export function base64ToPublicKey(b64: string): Uint8Array {
  return decodeBase64(b64);
}

/**
 * TypeScript 5.7+ me `Uint8Array` generic ho gaya: tweetnacl `Uint8Array<ArrayBufferLike>`
 * deta hai, jabki Web Crypto ka `BufferSource` `ArrayBuffer`-backed view maangta hai
 * (`SharedArrayBuffer` accept nahi hota). Ek fresh `ArrayBuffer` me copy karne se type
 * exact match ho jaata hai. Runtime behaviour bilkul same rehta hai.
 */
function toBufferSource(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  const copy = new Uint8Array(new ArrayBuffer(bytes.length));
  copy.set(bytes);
  return copy;
}

async function deriveAesKey(mySecretKey: Uint8Array, theirPublicKey: Uint8Array): Promise<CryptoKey> {
  // Raw X25519 shared secret — standardized (RFC 7748), Android ke Bouncy Castle
  // X25519Agreement se exactly match karta hai, chahe implementation library kuch bhi ho.
  const sharedSecret = nacl.scalarMult(mySecretKey, theirPublicKey);

  const hkdfMaterial = await crypto.subtle.importKey('raw', toBufferSource(sharedSecret), 'HKDF', false, ['deriveKey']);

  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: HKDF_SALT, info: HKDF_INFO },
    hkdfMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

export async function encryptMessage(
  plainText: string,
  theirPublicKey: Uint8Array,
  mySecretKey: Uint8Array
): Promise<{ nonce: string; ciphertext: string }> {
  const aesKey = await deriveAesKey(mySecretKey, theirPublicKey);
  const iv = crypto.getRandomValues(new Uint8Array(12)); // AES-GCM standard 12-byte IV
  const encoded = new TextEncoder().encode(plainText);
  const ciphertextBuf = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aesKey, encoded);
  return {
    nonce: encodeBase64(iv),
    ciphertext: encodeBase64(new Uint8Array(ciphertextBuf)), // GCM tag already appended at the end
  };
}

export async function decryptMessage(
  nonceB64: string,
  ciphertextB64: string,
  theirPublicKey: Uint8Array,
  mySecretKey: Uint8Array
): Promise<string | null> {
  try {
    const aesKey = await deriveAesKey(mySecretKey, theirPublicKey);
    const iv = decodeBase64(nonceB64);
    const ciphertext = decodeBase64(ciphertextB64);
    const plainBuf = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: toBufferSource(iv) }, aesKey, toBufferSource(ciphertext));
    return new TextDecoder().decode(plainBuf);
  } catch {
    return null; // tampered, galat key, ya corrupt data — reject
  }
}

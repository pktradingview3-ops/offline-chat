/**
 * Random, anonymous, non-traceable user ID.
 * Koi personal information, device info, ya IP kabhi ismein encode nahi hoti.
 * Har baar app khulne par naya ID banta hai (session-scoped, PRD Section 5.1).
 */
export function generateAnonId(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  let suffix = '';
  for (const b of bytes) suffix += chars[b % chars.length];
  return `anon-${suffix}`;
}

/**
 * The RFC 6238 code (SHA-1, 6 digits, 30 s steps) a script gives as an account's second factor. Studio accepts
 * the previous, current or next step and refuses a step it has already seen (ADR-0143), so a second sign-in
 * within the same 30 s takes the next step, waiting for the clock when that is still too far ahead.
 */
import { createHmac } from 'node:crypto';

const STEP_SECONDS = 30;
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

let lastStep = -1;

function base32Decode(text: string): Buffer {
  const bytes: number[] = [];
  let bits = 0;
  let value = 0;
  for (const char of text.toUpperCase().replace(/=+$/, '')) {
    value = (value << 5) | BASE32.indexOf(char);
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((value >> bits) & 0xff);
    }
  }
  return Buffer.from(bytes);
}

/** The code for one 30-second step of the secret. */
export function totpAt(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const mac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = mac[mac.length - 1]! & 0xf;
  return String((mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

/** A code Studio has not seen from this process: the first step after the last one given. */
export async function nextTotp(secret: string): Promise<string> {
  const current = () => Math.floor(Date.now() / 1000 / STEP_SECONDS);
  const step = Math.max(current(), lastStep + 1);
  while (step > current() + 1) await new Promise((resolve) => setTimeout(resolve, 1000));
  lastStep = step;
  return totpAt(secret, step);
}

import { createHmac } from 'node:crypto';

/** Mã TOTP 6 số (RFC 6238, SHA-1, bước 30 giây) cho khoá OTP dạng chuỗi của realm dev (WEB-056). */
export function totp(secret: string, now = Date.now()): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(now / 30_000)));
  const h = createHmac('sha1', Buffer.from(secret, 'utf8')).update(counter).digest();
  const o = h[h.length - 1] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

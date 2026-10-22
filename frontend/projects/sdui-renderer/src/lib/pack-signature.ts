import { UiPack } from './ui-pack';

/** Trường toàn vẹn máy chủ gắn sau khi ký; nội dung được ký là gói không có các trường này. */
const INTEGRITY_FIELDS = new Set(['contentHash', 'signature', 'publishedAt']);

const SHORT_ESCAPES: Record<string, string> = {
  '"': '\\"',
  '\\': '\\\\',
  '\b': '\\b',
  '\f': '\\f',
  '\n': '\\n',
  '\r': '\\r',
  '\t': '\\t',
};

/** Chuỗi JSON theo đúng cách Jackson ghi (ký tự điều khiển khác dùng `\u00XX` chữ hoa). */
function quote(s: string): string {
  const body = s.replace(
    // eslint-disable-next-line no-control-regex
    /["\\\u0000-\u001f]/g,
    (c) => SHORT_ESCAPES[c] ?? `\\u${c.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}`,
  );
  return `"${body}"`;
}

/**
 * JSON chuẩn tắc khớp `UiPackCompiler.canonical` của mission-core: khoá sắp xếp ở mọi mức theo
 * thứ tự đơn vị UTF-16, không khoảng trắng. Số thực dạng `1.0` mà máy chủ ghi sẽ thành `1` sau khi
 * trình duyệt đọc JSON, nên UI Pack không dùng số thực có phần thập phân bằng 0 (xem wiki trang 03).
 */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return typeof value === 'string' ? quote(value) : String(JSON.stringify(value) ?? 'null');
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort();
  return `{${keys.map((k) => `${quote(k)}:${canonicalJson(obj[k])}`).join(',')}}`;
}

/** Byte được ký của gói: JSON chuẩn tắc UTF-8 sau khi bỏ trường toàn vẹn. */
export function signedContent(pack: UiPack): Uint8Array<ArrayBuffer> {
  const copy: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(pack)) if (!INTEGRITY_FIELDS.has(k)) copy[k] = v;
  return new TextEncoder().encode(canonicalJson(copy)) as Uint8Array<ArrayBuffer>;
}

function fromBase64(b64: string): Uint8Array<ArrayBuffer> {
  const bin = atob(b64.replace(/\s+/g, ''));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export type SignatureCheck = 'ok' | 'missing' | 'invalid' | 'unsupported';

/**
 * Kiểm chữ ký ES256 (ECDSA P-256, SHA-256, chữ ký dạng r‖s 64 byte như
 * `SHA256withECDSAinP1363Format` của máy chủ) bằng WebCrypto. `keys` ánh xạ mã khoá sang khoá
 * công khai SubjectPublicKeyInfo dạng base64; có `keyId` (từ `current.json`) thì chỉ dùng khoá đó.
 * WebCrypto chỉ có trong ngữ cảnh an toàn (HTTPS hoặc localhost); thiếu thì trả `unsupported`.
 */
export async function verifyPackSignature(
  pack: UiPack,
  keys: Record<string, string>,
  keyId?: string,
): Promise<SignatureCheck> {
  if (!pack.signature) return 'missing';
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return 'unsupported';
  try {
    const data = signedContent(pack);
    if (
      pack.contentHash &&
      hex(await subtle.digest('SHA-256', data)) !== pack.contentHash.toLowerCase()
    ) {
      return 'invalid';
    }
    const signature = fromBase64(pack.signature);
    const candidates = keyId && keys[keyId] ? [keys[keyId]] : Object.values(keys);
    for (const spki of candidates) {
      const key = await subtle.importKey(
        'spki',
        fromBase64(spki),
        { name: 'ECDSA', namedCurve: 'P-256' },
        false,
        ['verify'],
      );
      if (await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signature, data))
        return 'ok';
    }
  } catch {
    /* khoá hoặc chữ ký sai định dạng: coi như không hợp lệ */
  }
  return 'invalid';
}

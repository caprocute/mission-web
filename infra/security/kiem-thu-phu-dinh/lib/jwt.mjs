// QA-13 · Giải mã và giả mạo JWT để kiểm phía máy chủ từ chối (ASVS V3.5.3, V6.2). Chỉ dùng thư viện chuẩn Node.
import { createHmac, generateKeyPairSync, sign } from 'node:crypto';

const b64u = (buf) => Buffer.from(buf).toString('base64url');

export function giaiMa(token) {
  const [h, p] = token.split('.');
  return {
    header: JSON.parse(Buffer.from(h, 'base64url').toString('utf8')),
    payload: JSON.parse(Buffer.from(p, 'base64url').toString('utf8')),
  };
}

/** Token `alg: none`, không chữ ký. */
export function khongKy(payload) {
  return `${b64u(JSON.stringify({ alg: 'none', typ: 'JWT' }))}.${b64u(JSON.stringify(payload))}.`;
}

/** Ký lại bằng khoá RSA hoặc EC tự sinh nhưng giữ `kid` của Keycloak: máy chủ phải từ chối vì chữ ký sai. */
export function kyBangKhoaLa(header, payload) {
  const ec = header.alg?.startsWith('ES');
  const { privateKey } = ec
    ? generateKeyPairSync('ec', { namedCurve: 'P-256' })
    : generateKeyPairSync('rsa', { modulusLength: 2048 });
  const alg = ec ? 'ES256' : 'RS256';
  const h = b64u(JSON.stringify({ ...header, alg }));
  const p = b64u(JSON.stringify(payload));
  const sig = sign('sha256', Buffer.from(`${h}.${p}`), ec ? { key: privateKey, dsaEncoding: 'ieee-p1363' } : privateKey);
  return `${h}.${p}.${b64u(sig)}`;
}

/** Nhầm thuật toán: ký HS256 với "khoá" là chuỗi công khai bất kỳ (tấn công RS/HS). */
export function kyHs256(header, payload, khoa) {
  const h = b64u(JSON.stringify({ ...header, alg: 'HS256' }));
  const p = b64u(JSON.stringify(payload));
  const sig = createHmac('sha256', khoa).update(`${h}.${p}`).digest();
  return `${h}.${p}.${b64u(sig)}`;
}

/** Sửa phần thân nhưng giữ nguyên chữ ký gốc. */
export function suaThan(token, sua) {
  const [h, , s] = token.split('.');
  const { payload } = giaiMa(token);
  return `${h}.${b64u(JSON.stringify(sua({ ...payload })))}.${s}`;
}

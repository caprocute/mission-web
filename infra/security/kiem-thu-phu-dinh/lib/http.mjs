// QA-13 · Gọi API kèm token và các kiểm tra dùng chung cho phản hồi lỗi (RFC 9457, không lộ chi tiết nội bộ).
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { cauHinh } from './cau-hinh.mjs';

export async function goi(duongDan, { token, method = 'GET', headers = {}, body, json } = {}) {
  const h = { accept: 'application/json, application/problem+json', ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  let du = body;
  if (json !== undefined) {
    h['content-type'] = 'application/json';
    du = JSON.stringify(json);
  }
  const res = await fetch(`${cauHinh.api}${duongDan}`, { method, headers: h, body: du, redirect: 'manual' });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return { status: res.status, headers: res.headers, text, data };
}

export const khoa = () => randomUUID();

// Dấu vết lộ chi tiết nội bộ trong thân lỗi (ASVS V7.4.1, V14.3.2).
const DAU_VET_NOI_BO = [
  /\bat [a-z]+\.[\w.$]+\(/i, // stack trace Java
  /Exception\b/,
  /org\.springframework|org\.hibernate|org\.postgresql|java\.lang/,
  /SQLSTATE|syntax error at or near|PSQLException/i,
  /\bselect\b[\s\S]{0,40}\bfrom\b/i,
];

export function khongLoNoiBo(r) {
  for (const re of DAU_VET_NOI_BO) assert.doesNotMatch(r.text, re, `thân lỗi lộ chi tiết nội bộ: ${re}`);
}

/** Lỗi đúng dạng Problem Details: mã trạng thái nằm trong tập cho phép, content-type problem+json, không lộ nội bộ. */
export function laProblem(r, choPhep) {
  const ds = Array.isArray(choPhep) ? choPhep : [choPhep];
  assert.ok(ds.includes(r.status), `mong đợi HTTP ${ds.join('/')}, nhận ${r.status}: ${r.text.slice(0, 300)}`);
  if (r.text) {
    assert.match(r.headers.get('content-type') ?? '', /application\/problem\+json/, 'lỗi phải trả application/problem+json');
    assert.equal(r.data?.status, r.status, 'trường status của Problem Details phải khớp mã HTTP');
  }
  khongLoNoiBo(r);
}

/** 401 của resource server: có WWW-Authenticate Bearer, không trả dữ liệu. */
export function laTuChoiXacThuc(r) {
  assert.equal(r.status, 401, `mong đợi 401, nhận ${r.status}: ${r.text.slice(0, 200)}`);
  assert.match(r.headers.get('www-authenticate') ?? '', /^Bearer/i, 'thiếu WWW-Authenticate: Bearer');
  khongLoNoiBo(r);
}

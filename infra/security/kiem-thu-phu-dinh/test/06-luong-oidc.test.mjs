// QA-13 · ASVS V2.1, V2.2, V3.5 (OAuth và OIDC trong ASVS 4.0.3): client web chỉ dùng Authorization
// Code + PKCE S256 (ADR-08, SEC-01). Các ca không đăng nhập sai mật khẩu nhiều lần để không khoá tài khoản thử trên dev;
// chống dò mật khẩu đã có RealmTemplateKeycloakIT kiểm trên realm dựng từ mẫu.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { cauHinh, thieu } from '../lib/cau-hinh.mjs';
import { doiCode, pkce, urlUyQuyen } from '../lib/oidc.mjs';

const skip = thieu('issuer');

async function moTrang(url) {
  const r = await fetch(url, { redirect: 'manual' });
  return { status: r.status, location: r.headers.get('location'), text: await r.text() };
}

describe('Luồng đăng nhập OIDC của client web', { skip }, () => {
  test('discovery chỉ quảng bá thuật toán ký bất đối xứng, có PKCE S256', async () => {
    const d = await (await fetch(`${cauHinh.issuer}/.well-known/openid-configuration`)).json();
    assert.ok(d.code_challenge_methods_supported.includes('S256'));
    assert.ok(!d.id_token_signing_alg_values_supported.includes('none'));
    assert.equal(d.issuer, cauHinh.issuer);
  });

  test('direct grant (mật khẩu gửi thẳng) bị từ chối với client web', async () => {
    const r = await fetch(`${cauHinh.issuer}/protocol/openid-connect/token`, {
      method: 'POST',
      body: new URLSearchParams({ grant_type: 'password', client_id: cauHinh.clientId, username: cauHinh.nguoiDung, password: 'sai-co-chu-dich' }),
    });
    assert.equal(r.status, 400);
    const j = await r.json();
    assert.ok(['unauthorized_client', 'invalid_client'].includes(j.error), JSON.stringify(j));
  });

  test('implicit (response_type=token) bị từ chối', async () => {
    const r = await moTrang(urlUyQuyen({ responseType: 'token', challenge: pkce().challenge }));
    // Keycloak trả lỗi về redirect_uri trong fragment (response_mode mặc định của implicit) và không kèm access_token.
    const u = r.location ? new URL(r.location, cauHinh.issuer) : null;
    const tham = new URLSearchParams(u ? `${u.search.slice(1)}&${u.hash.slice(1)}` : '');
    assert.equal(tham.get('access_token'), null, 'không được cấp token qua implicit');
    assert.ok(['unauthorized_client', 'unsupported_response_type'].includes(tham.get('error')) || r.status === 400,
      `implicit không bị chặn: HTTP ${r.status} ${r.location ?? ''}`);
  });

  test('thiếu code_challenge bị từ chối', async () => {
    const r = await moTrang(urlUyQuyen({}));
    const loi = r.location ? new URL(r.location, cauHinh.issuer).searchParams.get('error') : null;
    assert.equal(loi, 'invalid_request', `HTTP ${r.status} ${r.location ?? ''}`);
  });

  test('PKCE plain bị từ chối', async () => {
    const r = await moTrang(urlUyQuyen({ challenge: 'gia-tri-ro-khong-bam-qa13-0000000000000000000', method: 'plain' }));
    const loi = r.location ? new URL(r.location, cauHinh.issuer).searchParams.get('error') : null;
    assert.equal(loi, 'invalid_request', `HTTP ${r.status} ${r.location ?? ''}`);
  });

  test('redirect_uri lạ bị từ chối, không chuyển hướng ra ngoài', async () => {
    const u = new URL(urlUyQuyen({ challenge: pkce().challenge }));
    u.searchParams.set('redirect_uri', 'https://ke-gian.example/cb');
    const r = await moTrang(u.toString());
    assert.ok(!r.location || !r.location.startsWith('https://ke-gian.example'), `chuyển hướng ra ngoài: ${r.location}`);
    assert.ok(r.status === 400 || /Invalid parameter: redirect_uri|invalid_redirect_uri/i.test(r.text), `HTTP ${r.status}`);
  });

  test('đổi code giả không kèm code_verifier bị từ chối', async () => {
    const r = await doiCode('code-gia-qa13', null);
    assert.equal(r.status, 400);
    assert.equal((await r.json()).error, 'invalid_grant');
  });
});

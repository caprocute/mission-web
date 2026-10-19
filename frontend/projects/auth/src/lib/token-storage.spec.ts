import { VtTokenStorage } from './token-storage';

/** WEB-006, SEC-07: token chỉ trong bộ nhớ; nonce và PKCE verifier sống qua chuyển hướng. */
describe('VtTokenStorage', () => {
  beforeEach(() => sessionStorage.clear());

  it('access_token, refresh_token, id_token không ghi vào Web Storage', () => {
    const s = new VtTokenStorage();
    for (const k of ['access_token', 'refresh_token', 'id_token']) s.setItem(k, 'bí mật');
    expect(s.getItem('access_token')).toBe('bí mật');
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(new VtTokenStorage().getItem('access_token')).toBeNull();
  });

  it('nonce và PKCE_verifier giữ trong sessionStorage, xoá được', () => {
    const s = new VtTokenStorage();
    s.setItem('nonce', 'n1');
    s.setItem('PKCE_verifier', 'v1');
    expect(new VtTokenStorage().getItem('PKCE_verifier')).toBe('v1');
    s.removeItem('nonce');
    expect(sessionStorage.getItem('nonce')).toBeNull();
  });
});

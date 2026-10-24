#!/usr/bin/env python3
"""Lấy access token của một tài khoản thử realm `mission` qua client SPA `mission-web` (INF-22).

Đi đúng luồng của trình duyệt: Authorization Code + PKCE (S256), điền biểu mẫu đăng nhập rồi biểu mẫu OTP
của Keycloak, bắt mã ở redirect URI mà không mở trang đích, rồi đổi mã lấy token. Mã OTP sinh từ khoá TOTP
thử (RFC 6238, SHA-1, 6 số, bước 30 giây, khoá dạng chuỗi như frontend/e2e/totp.ts). Chỉ dùng thư viện chuẩn.

Biến môi trường: KC_URL (gốc Keycloak trình duyệt dùng, quyết định claim iss; mặc định KEYCLOAK_PUBLIC_URL, rồi
http://127.0.0.1:35080), KC_REALM (mission),
KC_CLIENT_ID (mission-web), KC_REDIRECT_URI (http://localhost:4200/), SMOKE_USER (canbo),
KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET. In access token ra stdout; lỗi in ra stderr, mã thoát 1.
Không in mật khẩu, mã OTP hay token vào nhật ký.
"""
import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import os
import re
import secrets
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional


def totp(secret: str, now: Optional[float] = None) -> str:
    counter = struct.pack(">Q", int((time.time() if now is None else now) // 30))
    digest = hmac.new(secret.encode("utf-8"), counter, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


class _StopAtRedirect(urllib.request.HTTPRedirectHandler):
    """Theo chuyển hướng của Keycloak nhưng dừng ở redirect URI của SPA để lấy mã."""

    def __init__(self, redirect_uri: str):
        self.redirect_uri = redirect_uri
        self.location: Optional[str] = None

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith(self.redirect_uri):
            self.location = newurl
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class _LoopbackPolicy(http.cookiejar.DefaultCookiePolicy):
    """Gửi cả cookie gắn cờ Secure qua http như trình duyệt làm với máy cục bộ (Keycloak dev chạy http)."""

    def return_ok_secure(self, cookie, request):
        return True


def _form_action(page: str) -> str:
    m = re.search(r'<form[^>]*\baction="([^"]+)"', page)
    if not m:
        raise RuntimeError("không thấy biểu mẫu đăng nhập trên trang Keycloak")
    return html.unescape(m.group(1))


def main() -> int:
    kc = (os.environ.get("KC_URL") or os.environ.get("KEYCLOAK_PUBLIC_URL") or "http://127.0.0.1:35080").rstrip("/")
    realm = os.environ.get("KC_REALM", "mission")
    # Keycloak đặt KC_HOSTNAME theo KEYCLOAK_PUBLIC_URL: biểu mẫu đăng nhập trỏ về host đó và cookie phiên gắn
    # với host đó, nên gọi bằng host khác (127.0.0.1 so với localhost) thì bước gửi biểu mẫu bị 400. Theo đúng
    # issuer realm công bố để token có iss mà APISIX và service chấp nhận.
    with urllib.request.urlopen(f"{kc}/realms/{realm}/.well-known/openid-configuration", timeout=30) as resp:
        issuer = json.load(resp)["issuer"].rstrip("/")
    if issuer != f"{kc}/realms/{realm}":
        print(f"lay-token-thu: issuer của realm là {issuer}, dùng gốc này thay cho {kc}", file=sys.stderr)
        kc = issuer[: -len(f"/realms/{realm}")]
    client_id = os.environ.get("KC_CLIENT_ID", "mission-web")
    redirect_uri = os.environ.get("KC_REDIRECT_URI", "http://localhost:4200/")
    user = os.environ.get("SMOKE_USER", "canbo")
    password = os.environ.get("KC_TEST_USER_PASSWORD", "")
    otp_secret = os.environ.get("KC_TEST_OTP_SECRET", "")
    if not password or not otp_secret:
        print("thiếu KC_TEST_USER_PASSWORD hoặc KC_TEST_OTP_SECRET", file=sys.stderr)
        return 1

    base = f"{kc}/realms/{realm}/protocol/openid-connect"
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    stop = _StopAtRedirect(redirect_uri)
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy=_LoopbackPolicy())), stop
    )
    query = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": "openid",
            "state": secrets.token_urlsafe(16),
            "nonce": secrets.token_urlsafe(16),
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
    )

    def post(url: str, fields: dict) -> Optional[str]:
        try:
            with opener.open(url, urllib.parse.urlencode(fields).encode()) as resp:
                return resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if stop.location:
                return None
            raise RuntimeError(f"Keycloak trả {e.code} khi gửi biểu mẫu") from None

    with opener.open(f"{base}/auth?{query}") as resp:
        page = resp.read().decode("utf-8", "replace")
    page = post(_form_action(page), {"username": user, "password": password, "credentialId": ""})
    if stop.location is None and page is not None:
        if 'name="otp"' not in page:
            raise RuntimeError("đăng nhập không qua (sai mật khẩu hoặc tài khoản bị khoá?)")
        post(_form_action(page), {"otp": totp(otp_secret)})
    if stop.location is None:
        raise RuntimeError("không nhận được mã ủy quyền sau bước OTP")
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(stop.location).query)
    if "code" not in params:
        raise RuntimeError(f"Keycloak trả lỗi: {params.get('error', ['?'])[0]}")

    body = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "code": params["code"][0],
            "code_verifier": verifier,
        }
    ).encode()
    with urllib.request.urlopen(f"{base}/token", body) as resp:
        print(json.load(resp)["access_token"])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001 - in lỗi gọn cho script khói, không in dấu vết có dữ liệu nhập
        print(f"lay-token-thu: {e}", file=sys.stderr)
        sys.exit(1)

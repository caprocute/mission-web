"""Keycloak vận hành giả lập (realm platform-ops) cho kiểm thử vtctl trên stack thật (INF-26).

Có discovery, JWKS, luồng thiết bị RFC 8628 (tự duyệt, không có người dùng thật) và đổi refresh token. Token RS256
mang realm_access.roles, acr (aal2 khi xin acr_values=aal2) và auth_time như Keycloak thật, để tenant-registry kiểm
quyền và bước lên bằng đúng mã của nó. Người dùng của lần đăng nhập kế tiếp đặt qua POST /_dieu-khien/nguoi-dung
(kiểm thử bốn mắt cần hai quản trị nền tảng khác nhau). Chỉ dùng trong kiểm thử; khoá sinh lúc chạy, không lưu.
Chạy: python3 oidc_gia_lap.py <cổng> <realm>
"""
import base64, json, secrets, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

PORT, REALM = int(sys.argv[1]), sys.argv[2]
ISS = f"http://127.0.0.1:{PORT}/realms/{REALM}"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KID = "gia-lap-1"
LOCK = threading.Lock()
STATE = {"user": {"sub": "11111111-1111-4111-8111-111111111111", "name": "qtnt-mot", "roles": ["platform_admin"]}}
DEVICE, REFRESH = {}, {}


def b64(n):
    return base64.urlsafe_b64encode(n.to_bytes((n.bit_length() + 7) // 8, "big")).rstrip(b"=").decode()


def token(user, acr, client):
    now = int(time.time())
    claims = {"iss": ISS, "sub": user["sub"], "aud": ["account", client], "azp": client, "typ": "Bearer",
              "preferred_username": user["name"], "realm_access": {"roles": user["roles"]},
              "acr": acr, "auth_time": now, "iat": now, "exp": now + 300, "jti": secrets.token_hex(8)}
    return jwt.encode(claims, KEY, algorithm="RS256", headers={"kid": KID})


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        p = f"/realms/{REALM}"
        if self.path == p + "/.well-known/openid-configuration":
            oc = p + "/protocol/openid-connect"
            return self.send(200, {"issuer": ISS, "jwks_uri": ISS + "/protocol/openid-connect/certs",
                                   "token_endpoint": f"http://127.0.0.1:{PORT}{oc}/token",
                                   "device_authorization_endpoint": f"http://127.0.0.1:{PORT}{oc}/auth/device",
                                   "end_session_endpoint": f"http://127.0.0.1:{PORT}{oc}/logout"})
        if self.path == p + "/protocol/openid-connect/certs":
            n = KEY.public_key().public_numbers()
            return self.send(200, {"keys": [{"kty": "RSA", "kid": KID, "use": "sig", "alg": "RS256", "n": b64(n.n), "e": b64(n.e)}]})
        self.send(404, {"error": "not_found"})

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        if self.path == "/_dieu-khien/nguoi-dung":
            with LOCK:
                STATE["user"] = json.loads(body)
            return self.send(200, STATE["user"])
        f = {k: v[0] for k, v in parse_qs(body).items()}
        oc = f"/realms/{REALM}/protocol/openid-connect"
        if self.path == oc + "/auth/device":
            dc = secrets.token_hex(16)
            with LOCK:
                DEVICE[dc] = {"user": dict(STATE["user"]), "acr": "aal2" if f.get("acr_values") == "aal2" else "aal1",
                              "client": f.get("client_id", "")}
            return self.send(200, {"device_code": dc, "user_code": "GIA-LAP", "verification_uri": ISS + "/device",
                                   "verification_uri_complete": ISS + "/device?user_code=GIA-LAP",
                                   "expires_in": 600, "interval": 1})
        if self.path == oc + "/token":
            g = f.get("grant_type")
            with LOCK:
                if g == "urn:ietf:params:oauth:grant-type:device_code":
                    d = DEVICE.pop(f.get("device_code", ""), None)
                elif g == "refresh_token":
                    d = REFRESH.pop(f.get("refresh_token", ""), None)
                    if d:
                        d = dict(d, acr="aal1")  # làm mới không giữ bậc 2, như Keycloak
                else:
                    d = None
                if not d:
                    return self.send(400, {"error": "invalid_grant"})
                rt = secrets.token_hex(16)
                REFRESH[rt] = d
            return self.send(200, {"access_token": token(d["user"], d["acr"], d["client"]), "token_type": "Bearer",
                                   "expires_in": 300, "refresh_token": rt, "refresh_expires_in": 1800})
        if self.path == oc + "/logout":
            return self.send(204, {})
        self.send(404, {"error": "not_found"})


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()

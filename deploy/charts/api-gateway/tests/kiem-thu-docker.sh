#!/usr/bin/env bash
# Kiểm thử tích hợp DV-01-01 với APISIX 3.13 thật trong Docker, không cần cụm: dựng ConfigMap ở chế độ standalone từ
# chart, chạy Keycloak giả lập (discovery + JWKS ES256) và upstream phản hồi lại header với bí danh mạng
# <service>.<namespace>.svc.cluster.local, rồi thử các ca: không token 401, sai chữ ký 401, token RS256 401,
# hết hạn, sai iss, alg none 401, sai cell 403, header X-Tenant-Id giả bị ghi đè bằng claim, tuyến chặn nội bộ 404, JWKS công khai không cần token, cookie định tuyến sửa một byte 403, khác cell 421; mọi lỗi là Problem Details.
# Cần docker, helm, python3 có PyJWT + cryptography + PyYAML.
set -euo pipefail
cd "$(dirname "$0")/.."
APISIX_IMG=${APISIX_IMG:-mirror.gcr.io/apache/apisix:3.13.0-debian}
PY_IMG=${PY_IMG:-mirror.gcr.io/library/python:3.13-alpine}
W=$(mktemp -d); NET=vt-apigw-$$
don() { docker rm -f "$NET-apisix" "$NET-kc" "$NET-echo" "$NET-scoring" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$W"; }
trap don EXIT
helm template g . -f tests/values-docker.yaml > "$W/r.yaml"
python3 -I - "$W" <<'PY'
import json, sys, time, yaml, jwt
from cryptography.hazmat.primitives.asymmetric import ec, rsa
d = sys.argv[1]
for doc in yaml.safe_load_all(open(f"{d}/r.yaml")):
    if doc and doc.get("kind") == "ConfigMap" and doc["metadata"]["name"] in ("apisix", "apisix-vt-plugins"):
        for k, v in doc["data"].items():
            open(f"{d}/{k}", "w").write(v)
iss = "http://keycloak.identity.svc.cluster.local:8080/realms/mission"
k = ec.generate_private_key(ec.SECP256R1()); khac = ec.generate_private_key(ec.SECP256R1())
r = rsa.generate_private_key(public_exponent=65537, key_size=2048)
# Keycloak công bố cả x5c (chứng thư tự ký của khoá realm); lua-resty-openidc cần x5c để dựng khoá EC.
import base64, datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
def x5c(key):
    n = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "mission")])
    t = datetime.datetime.now(datetime.timezone.utc)
    c = (x509.CertificateBuilder().subject_name(n).issuer_name(n).public_key(key.public_key()).serial_number(1)
         .not_valid_before(t - datetime.timedelta(days=1)).not_valid_after(t + datetime.timedelta(days=1))
         .sign(key, hashes.SHA256()))
    return [base64.b64encode(c.public_bytes(serialization.Encoding.DER)).decode()]
jwk = json.loads(jwt.algorithms.ECAlgorithm.to_jwk(k.public_key())); jwk.update(kid="thu", use="sig", alg="ES256", x5c=x5c(k))
jr = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(r.public_key())); jr.update(kid="rsa", use="sig", alg="RS256", x5c=x5c(r))
import os; os.makedirs(f"{d}/kc/realms/mission/protocol/openid-connect", exist_ok=True); os.makedirs(f"{d}/kc/realms/mission/.well-known", exist_ok=True)
open(f"{d}/kc/realms/mission/protocol/openid-connect/certs", "w").write(json.dumps({"keys": [jwk, jr]}))
open(f"{d}/kc/realms/mission/.well-known/openid-configuration", "w").write(json.dumps({"issuer": iss,
  "jwks_uri": iss + "/protocol/openid-connect/certs", "authorization_endpoint": iss + "/protocol/openid-connect/auth",
  "token_endpoint": iss + "/protocol/openid-connect/token", "id_token_signing_alg_values_supported": ["ES256", "RS256"]}))
now = int(time.time())
def tok(key, alg, kid, **kw):
    c = {"iss": iss, "sub": "u-thu", "aud": "apisix-gateway", "azp": "mission-web", "tenant_id": "t-thu", "cell": "CELL-DN1",
         "iat": now, "exp": now + 3600}; c.update(kw)
    return jwt.encode(c, key, algorithm=alg, headers={"kid": kid})
toks = {"dung": tok(k, "ES256", "thu"), "sai_chu_ky": tok(khac, "ES256", "thu"), "rs256": tok(r, "RS256", "rsa"),
        "sai_cell": tok(k, "ES256", "thu", cell="CELL-HCM1"), "het_han": tok(k, "ES256", "thu", exp=now - 600),
        "sai_iss": tok(k, "ES256", "thu", iss="http://khac/realms/mission"),
        "none": jwt.encode({"iss": iss, "sub": "u", "cell": "CELL-DN1", "exp": now + 600}, None, algorithm="none")}
# Cookie định tuyến (DV-01-04): cùng định dạng routecookie của SVC-03.
import hmac as _h, hashlib, os as _os
rk = _os.urandom(32); open(f"{d}/route-key", "w").write(base64.b64encode(rk).decode())
def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def ck(t, c, exp):
    head = "v1." + b64u(json.dumps({"t": t, "c": c, "exp": exp}).encode())
    return head + "." + b64u(_h.new(rk, head.encode(), hashlib.sha256).digest())
good = ck("t-thu", "CELL-DN1", now + 300)
bad = good[:-2] + ("A" if good[-2] != "A" else "B") + good[-1]
toks.update(ck_dung=good, ck_sua=bad, ck_cell_khac=ck("t-thu", "CELL-HCM1", now + 300),
            ck_tenant_khac=ck("t-khac", "CELL-DN1", now + 300), ck_het_han=ck("t-thu", "CELL-DN1", now - 10))
json.dump(toks, open(f"{d}/toks.json", "w"))
PY
cat > "$W/echo.py" <<'PY'
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def _r(self):
        b = json.dumps({"service": os.environ.get("TEN", ""), "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)
    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _r
HTTPServer(("0.0.0.0", 8080), H).serve_forever()
PY
chmod -R a+rX "$W"
docker network create "$NET" >/dev/null
docker run -d --name "$NET-kc" --network "$NET" --network-alias keycloak.identity.svc.cluster.local \
  -v "$W/kc:/srv:ro" "$PY_IMG" python -m http.server 8080 --directory /srv >/dev/null
ALIASES=()
for a in mission-core.core document-service.workers ledger-service.secure esign-service.secure \
         org-service.identity integration-gateway.platform notification-service.platform search-indexer.platform analytics-api.analytics; do
  ALIASES+=(--network-alias "$a.svc.cluster.local")
done
docker run -d --name "$NET-echo" --network "$NET" "${ALIASES[@]}" -e TEN=chung -v "$W/echo.py:/echo.py:ro" "$PY_IMG" python /echo.py >/dev/null
docker run -d --name "$NET-scoring" --network "$NET" --network-alias scoring-service.workers.svc.cluster.local -e TEN=scoring \
  -v "$W/echo.py:/echo.py:ro" "$PY_IMG" python /echo.py >/dev/null
docker run -d --name "$NET-apisix" --network "$NET" -p 127.0.0.1::9080 -e APISIX_OIDC_CLIENT_SECRET=khong-dung \
  -e VT_ROUTE_COOKIE_KEY="$(cat "$W/route-key")" \
  -e APISIX_STAND_ALONE=true \
  -v "$W/config.yaml:/usr/local/apisix/conf/config.yaml:ro" -v "$W/apisix.yaml:/usr/local/apisix/conf/apisix.yaml:ro" \
  -v "$W/vt-claims.lua:/opt/vt/lua/apisix/plugins/vt-claims.lua:ro" "$APISIX_IMG" >/dev/null
PORT=$(docker port "$NET-apisix" 9080/tcp | head -1 | sed 's/.*://')
for i in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
if ! python3 -I "$(pwd)/tests/kiem-thu-docker.py" "http://127.0.0.1:$PORT" "$W/toks.json"; then
  docker logs "$NET-apisix" 2>&1 | grep -E "error|warn" | tail -30; exit 1
fi

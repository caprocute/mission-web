#!/usr/bin/env bash
# Kiểm thử cục bộ bằng Docker, không cần cụm (DV-01-02): dựng APISIX standalone từ chính chart (helm template với
# values-cuc-bo.yaml), Coraza 0.5.0 với OWASP CRS 4, Keycloak giả lập (discovery + JWKS tĩnh), Valkey đơn và upstream
# giả lập; rồi kiểm ba tầng giới hạn, ghi đè theo tenant, cắt tải theo lớp, giới hạn kích thước, Valkey ngừng,
# WAF ở chế độ chặn và phát hiện, ví dụ request của hợp đồng không bị chặn nhầm, cắt tải mức 1 và mức 3.
# Cần docker, helm, python3 có PyJWT + cryptography + PyYAML. Không thay kiểm thử tải k6 (YC-PT-19: chưa đo hiệu năng).
set -euo pipefail
cd "$(dirname "$0")"
CORAZA_VER=0.5.0
PY=docker.io/library/python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
VALKEY=docker.io/valkey/valkey@sha256:081c2f5cb575efc901aa80ff9cdbd1ec6a301682fd35e1ebb4b0990a4a4a8507
APISIX=docker.io/apache/apisix@sha256:c5c7a55ebb5c07abc210dbb963a37f41030e12c91d23bacedbaa168fec633bd7
ENV_KIND=../../../environments/kind/api-gateway/values.yaml
W=$(mktemp -d); NET=vt-apisix-thu-$$; PORT=${PORT:-39080}
don() { docker rm -f "$NET-apisix" "$NET-kc" "$NET-up" "$NET-valkey" >/dev/null 2>&1 || true
        docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$W"; }
[ -n "${KEEP:-}" ] || trap don EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

echo "== Chuẩn bị Coraza, khoá ký và mạng"
if [ -n "${CORAZA_WASM:-}" ]; then cp "$CORAZA_WASM" "$W/coraza-proxy-wasm.wasm"; else
  curl -sSfL -o "$W/c.zip" "https://github.com/corazawaf/coraza-proxy-wasm/releases/download/$CORAZA_VER/coraza-proxy-wasm-$CORAZA_VER.zip"
  (cd "$W" && unzip -q c.zip); fi
mkdir -p "$W/kc/realms/mission/.well-known" "$W/kc/realms/mission/protocol/openid-connect" "$W/plugins" "$W/conf"
python3 - "$W" <<'PY'
import json, sys, jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
d = sys.argv[1]
k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
iss = "http://keycloak:8080/realms/mission"
jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(k.public_key())); jwk.update(kid="thu", use="sig", alg="RS256")
open(f"{d}/kc/realms/mission/protocol/openid-connect/certs", "w").write(json.dumps({"keys": [jwk]}))
open(f"{d}/kc/realms/mission/.well-known/openid-configuration", "w").write(json.dumps({"issuer": iss,
  "jwks_uri": iss + "/protocol/openid-connect/certs", "authorization_endpoint": iss + "/protocol/openid-connect/auth",
  "token_endpoint": iss + "/protocol/openid-connect/token", "id_token_signing_alg_values_supported": ["RS256"]}))
open(f"{d}/key.pem", "wb").write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
  serialization.NoEncryption()))
PY
chmod -R a+rX "$W"
docker network create "$NET" >/dev/null
docker run -d --name "$NET-valkey" --network "$NET" --network-alias valkey "$VALKEY" \
  valkey-server --save "" --appendonly no --protected-mode no >/dev/null
docker run -d --name "$NET-kc" --network "$NET" --network-alias keycloak -v "$W/kc:/srv:ro" "$PY" \
  python -m http.server 8080 --directory /srv >/dev/null
# Upstream giả lập trả lại phương thức, đường dẫn, header và độ dài thân; đường dẫn chứa /slow chờ 1,5 s.
ALIAS=(); for s in mission-core.core esign-service.secure evidence-service.platform analytics-api.analytics ai-gateway.secure keycloak.identity; do
  ALIAS+=(--network-alias "$s.svc.cluster.local"); done
docker run -d --name "$NET-up" --network "$NET" "${ALIAS[@]}" "$PY" python -c '
import json, time, http.server as h
class H(h.BaseHTTPRequestHandler):
    def any(s):
        n = int(s.headers.get("Content-Length") or 0); s.rfile.read(n)
        if "/slow" in s.path: time.sleep(1.5)
        b = json.dumps({"method": s.command, "path": s.path, "len": n,
                        "headers": {k.lower(): v for k, v in s.headers.items()}}).encode()
        s.send_response(200); s.send_header("Content-Type", "application/json")
        s.send_header("Content-Length", str(len(b))); s.end_headers(); s.wfile.write(b)
    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = any
    def log_message(s, *a): pass
h.ThreadingHTTPServer(("", 8080), H).serve_forever()' >/dev/null

# Dựng cấu hình APISIX từ chart; tham số thêm cho helm template (mức suy giảm, chế độ Coraza).
dung() {
  helm template g .. -f values-cuc-bo.yaml -f "$ENV_KIND" "$@" > "$W/r.yaml"
  python3 - "$W" <<'PY'
import sys, yaml
d = sys.argv[1]
for doc in yaml.safe_load_all(open(f"{d}/r.yaml")):
    if doc and doc["kind"] == "ConfigMap" and doc["metadata"]["name"] in ("apisix", "apisix-vt-plugins"):
        for k, v in doc["data"].items():
            open(f"{d}/conf/{k}" if k.endswith(".yaml") else f"{d}/plugins/{k}", "w").write(v)
PY
}
# Chép đè giữ nguyên inode để APISIX standalone tự nạp lại apisix.yaml đã gắn (bind mount từng tệp).
nap_lai() { cat "$W/conf/apisix.yaml" > "$W/live-apisix.yaml"; sleep 3; }

echo "== Khởi động APISIX (mức 0, Coraza chặn)"
dung --set coraza.ruleEngine=On
cp "$W/conf/apisix.yaml" "$W/live-apisix.yaml"; chmod a+r "$W"/conf/* "$W"/plugins/* "$W/live-apisix.yaml"
docker run -d --name "$NET-apisix" --network "$NET" -p "127.0.0.1:$PORT:9080" -e APISIX_OIDC_CLIENT_SECRET=thu \
  -v "$W/conf/config.yaml:/usr/local/apisix/conf/config.yaml:ro" -v "$W/live-apisix.yaml:/usr/local/apisix/conf/apisix.yaml" \
  -v "$W/plugins:/opt/vt/lua/apisix/plugins:ro" -v "$W/coraza-proxy-wasm.wasm:/opt/vt/wasm/coraza-proxy-wasm.wasm:ro" \
  "$APISIX" >/dev/null
for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
curl -s -o /dev/null "http://127.0.0.1:$PORT/" && ok "APISIX khởi động (Coraza, vt-claims, vt-problem nạp được)" \
  || { hong "APISIX không khởi động"; docker logs "$NET-apisix" 2>&1 | tail -30; exit 1; }
URL="http://127.0.0.1:$PORT"
chay() { python3 kiem-thu-cuc-bo.py "$URL" "$W" "$1" > "$W/kq.txt" 2>&1 || true; sed 's/^/  /' "$W/kq.txt"
  dat=$((dat + $(grep -c '^\[ĐẠT\]' "$W/kq.txt" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$W/kq.txt" || true)))
  grep -q '^\[' "$W/kq.txt" || truot=$((truot+1)); }
chay muc0
echo "== Valkey ngừng: giới hạn tầng 1 bỏ qua, request vẫn đi qua (allow_degradation)"
docker stop "$NET-valkey" >/dev/null
chay valkey-ngung
docker start "$NET-valkey" >/dev/null

echo "== Coraza chế độ phát hiện (dev)"
dung; nap_lai; chay phat-hien
echo "== Cắt tải mức 1"
dung --set coraza.ruleEngine=On --set degrade.level=1; nap_lai; chay muc1
echo "== Cắt tải mức 3"
dung --set coraza.ruleEngine=On --set degrade.level=3; nap_lai; chay muc3
if docker logs "$NET-apisix" 2>&1 | grep -E '\[(error|crit|alert|emerg)\]' | grep -v -E 'Coraza|coraza|limit|redis|connection refused|timeout|No bearer token' > "$W/loi.txt"; then
  hong "error.log APISIX có lỗi ngoài dự kiến"; head -10 "$W/loi.txt"; else ok "error.log APISIX không có lỗi ngoài dự kiến"; fi
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

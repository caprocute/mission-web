#!/usr/bin/env bash
# Kiểm thử api-gateway trên cụm kind (INF-08 tiêu chí 3): không JWT bị 401, JWT có claim cell khác bị từ chối,
# vượt 600 request/60 s trả 429 thân RFC 9457 kèm Retry-After, payload SQLi bị Coraza chặn, header SEC-24,
# traceparent. Keycloak giả lập bằng pod phục vụ discovery và JWKS tĩnh; JWT ký RS256 bằng khoá sinh tại chỗ.
# Cần cụm từ deploy/environments/kind/tao-cum.sh, python3 có PyJWT + cryptography. Chạy cell-base trước.
set -euo pipefail
cd "$(dirname "$0")"
KIND_DIR=../../../environments/kind
CORAZA_VER=0.5.0
PY=docker.io/library/python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
VALKEY=docker.io/valkey/valkey@sha256:081c2f5cb575efc901aa80ff9cdbd1ec6a301682fd35e1ebb4b0990a4a4a8507
APISIX=docker.io/apache/apisix@sha256:c5c7a55ebb5c07abc210dbb963a37f41030e12c91d23bacedbaa168fec633bd7
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
PSC='"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}}'
SC='"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}'

echo "== Chuẩn bị ảnh"
"$KIND_DIR/nap-anh.sh" "$PY" "$VALKEY" "$APISIX" >/dev/null
if ! docker image inspect vt-local/coraza-proxy-wasm:$CORAZA_VER >/dev/null 2>&1; then
  curl -sSfL -o "$TMP/c.zip" "https://github.com/corazawaf/coraza-proxy-wasm/releases/download/$CORAZA_VER/coraza-proxy-wasm-$CORAZA_VER.zip"
  (cd "$TMP" && unzip -q c.zip && printf 'FROM docker.io/library/alpine:latest\nCOPY coraza-proxy-wasm.wasm /wasm/\nUSER 10001\n' > Dockerfile \
    && docker build -q -t vt-local/coraza-proxy-wasm:$CORAZA_VER . >/dev/null)
fi
kind load docker-image vt-local/coraza-proxy-wasm:$CORAZA_VER --name vt-cell-kind >/dev/null

echo "== Keycloak giả lập, mission-core giả lập, Valkey"
./gia-lap-keycloak.sh "$TMP"
kubectl -n core apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: { name: mission-core, labels: { app.kubernetes.io/name: mission-core } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: $PY
      imagePullPolicy: IfNotPresent
      # Trả lại header nhận được dưới dạng JSON để kiểm traceparent.
      command: ["python", "-c", "import json,http.server as h\nclass H(h.BaseHTTPRequestHandler):\n def do_GET(s):\n  b=json.dumps({k.lower():v for k,v in s.headers.items()}).encode();s.send_response(200);s.send_header('Content-Type','application/json');s.end_headers();s.wfile.write(b)\n def log_message(s,*a):pass\nh.ThreadingHTTPServer(('',8080),H).serve_forever()"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
---
apiVersion: v1
kind: Service
metadata: { name: mission-core }
spec: { selector: { app.kubernetes.io/name: mission-core }, ports: [{ port: 8080 }] }
YAML
kubectl -n data-kafka apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: { name: valkey, labels: { app.kubernetes.io/name: valkey } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 999, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: $VALKEY
      imagePullPolicy: IfNotPresent
      command: ["valkey-server", "--save", "", "--appendonly", "no", "--protected-mode", "no"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
---
apiVersion: v1
kind: Service
metadata: { name: valkey }
spec: { selector: { app.kubernetes.io/name: valkey }, ports: [{ port: 6379 }] }
YAML
kubectl wait --for=condition=Ready pod/keycloak -n identity pod/mission-core -n core --timeout=90s >/dev/null 2>&1 || true
for p in identity/keycloak core/mission-core data-kafka/valkey; do
  kubectl -n "${p%/*}" wait --for=condition=Ready "pod/${p#*/}" --timeout=90s >/dev/null && ok "pod giả lập $p Ready" || hong "$p chưa Ready"
done

echo "== Cài api-gateway (standalone)"
# Ở cụm thật Secret này do ExternalSecret tạo từ OpenBao; trên kind dùng giá trị thử ngẫu nhiên.
kubectl -n edge create secret generic apisix-oidc --from-literal=client-secret="thu-$RANDOM$RANDOM" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
if helm upgrade --install api-gateway .. -f values-kind.yaml --wait --timeout 4m >/dev/null; then
  # Khởi động lại để APISIX bỏ JWKS đã lưu đệm từ lần chạy trước.
  kubectl -n edge rollout restart deploy/apisix >/dev/null && kubectl -n edge rollout status deploy/apisix --timeout=3m >/dev/null
  ok "3 replica APISIX Ready (Coraza nạp được, vt-claims nạp được)"
else
  hong "APISIX chưa Ready"; kubectl -n edge logs deploy/apisix --tail=30 || true
fi
vung=$(kubectl -n edge get pod -l app.kubernetes.io/name=apisix -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' \
  | xargs -I{} kubectl get node {} -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}{"\n"}' | sort -u | wc -l)
[ "$vung" = 3 ] && ok "APISIX rải trên 3 vùng" || hong "APISIX nằm trên $vung vùng"

echo "== Gọi qua cổng từ pod client trong edge"
kubectl -n edge apply -f - >/dev/null <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: client-thu-egress }
spec:
  podSelector: { matchLabels: { vt.io/vai-tro: client-thu } }
  policyTypes: ["Egress"]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
    - to: [{ podSelector: { matchLabels: { app.kubernetes.io/name: apisix } } }]
      ports: [{ protocol: TCP, port: 9080 }]
YAML
kubectl -n edge delete pod client-thu --ignore-not-found >/dev/null
kubectl -n edge run client-thu --image="$PY" --restart=Never --labels=vt.io/vai-tro=client-thu \
  --overrides='{"spec":{'"$PSC"',"containers":[{"name":"c","image":"'"$PY"'","imagePullPolicy":"IfNotPresent","command":["sleep","3600"],'"$SC"'}]}}' >/dev/null
kubectl -n edge wait --for=condition=Ready pod/client-thu --timeout=60s >/dev/null
kubectl -n edge exec -i client-thu -- python - http://apisix-gateway.edge.svc "$(cat "$TMP/tok")" "$(cat "$TMP/tok_sai")" \
  < kiem-thu-kind.py > "$TMP/kq.txt" 2>&1 || true
sed 's/^/  /' "$TMP/kq.txt"
dat=$((dat + $(grep -c '^\[ĐẠT\]' "$TMP/kq.txt" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$TMP/kq.txt" || true)))
grep -q '^\[' "$TMP/kq.txt" || truot=$((truot+1))

echo "== Dọn"
kubectl -n edge delete pod client-thu --wait=false >/dev/null; kubectl -n edge delete networkpolicy client-thu-egress >/dev/null
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

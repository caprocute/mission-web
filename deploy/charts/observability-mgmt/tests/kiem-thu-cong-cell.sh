#!/usr/bin/env bash
# Kiểm thử điểm vào TLS cho cảnh báo từ cell (INF-15, templates/cong-canh-bao-cell.yaml): render, rồi chạy ghostunnel
# đúng tham số chart sinh ra trước một Alertmanager thật ở localhost. Chứng chỉ khách đúng CA và đúng OU gửi được
# cảnh báo; không chứng chỉ, chứng chỉ CA khác, chứng chỉ sai OU bị từ chối. Cần helm, alertmanager, amtool,
# ghostunnel (v1.9), openssl, curl, python3 với PyYAML. Dùng: tests/kiem-thu-cong-cell.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); PIDS=()
trap 'for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$OUT"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

echo "== Render"
helm template om . -n observability -f tests/values-ci.yaml > "$OUT/om.yaml" 2>/dev/null && ok "render ci" || hong "render ci"
for k in host cidrs choPhep; do
  helm template om . -n observability -f tests/values-ci.yaml --set "alertmanager.congCell.$k=null" >/dev/null 2>"$OUT/e" \
    && hong "render không dừng khi thiếu congCell.$k" || { grep -q "congCell.$k trống" "$OUT/e" && ok "render dừng khi thiếu congCell.$k"; }
done
python3 - "$OUT/om.yaml" "$OUT/args" <<'PY' > "$OUT/kq"
import sys, yaml
docs = {(d["kind"], d["metadata"]["name"]): d for d in yaml.safe_load_all(open(sys.argv[1])) if d}
def ok(c, s): print(("[ĐẠT] " if c else "[HỎNG] ") + s)
dep = docs[("Deployment", "alertmanager-cong-cell")]["spec"]["template"]["spec"]
c = dep["containers"][0]
ok(c["image"].startswith("docker.io/ghostunnel/ghostunnel@sha256:"), "ảnh ghostunnel ghim digest")
ok(c["securityContext"]["readOnlyRootFilesystem"] and dep["securityContext"]["runAsNonRoot"], "chạy không root, hệ tệp chỉ đọc")
np = docs[("NetworkPolicy", "alertmanager-cong-cell")]["spec"]
ok([b["ipBlock"]["cidr"] for b in np["ingress"][0]["from"]] == ["192.0.2.0/24", "198.51.100.0/24"]
   and np["ingress"][0]["ports"] == [{"protocol": "TCP", "port": 9443}], "NetworkPolicy chỉ nhận dải của cell vào cổng 9443")
ok(np["egress"][0]["ports"] == [{"protocol": "TCP", "port": 9093}], "cổng chỉ ra Alertmanager 9093 (và DNS)")
svc = docs[("Service", "alertmanager-cong-cell")]
ok(svc["spec"]["type"] == "LoadBalancer" and svc["spec"]["externalTrafficPolicy"] == "Local"
   and svc["metadata"]["annotations"]["vt.io/ten-dns"] == "alertmanager.quan-tri.invalid", "Service LoadBalancer giữ IP nguồn, ghi tên DNS")
es = docs[("ExternalSecret", "alertmanager-cong-cell-tls")]["spec"]
ok(sorted(x["secretKey"] for x in es["data"]) == ["ca.crt", "tls.crt", "tls.key"], "chứng chỉ máy chủ và CA khách từ OpenBao qua ESO")
open(sys.argv[2], "w").write("\n".join(c["args"]))
PY
sed 's/^/  /' "$OUT/kq"; dat=$((dat + $(grep -c '^\[ĐẠT\]' "$OUT/kq" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$OUT/kq" || true)))

echo "== Chạy thật: ghostunnel trước Alertmanager"
cd "$OUT"
ssl() { openssl "$@" >/dev/null 2>&1; }
ssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 -subj "/CN=ca-cell" -keyout ca.key -out ca.crt
ssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 -subj "/CN=ca-la" -keyout la.key -out la.crt
ky() { # $1 tên, $2 subject, $3 CA, $4 phần mở rộng
  ssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "$2" -keyout "$1.key" -out "$1.csr"
  printf '%s\n' "$4" > "$1.ext"
  ssl x509 -req -in "$1.csr" -CA "$3.crt" -CAkey "$3.key" -CAcreateserial -days 1 -extfile "$1.ext" -out "$1.crt"
}
ky may "/CN=alertmanager.quan-tri.invalid" ca "subjectAltName=DNS:alertmanager.quan-tri.invalid,DNS:localhost,IP:127.0.0.1
extendedKeyUsage=serverAuth"
ky dung "/OU=prometheus-cell/CN=prometheus-g4" ca "extendedKeyUsage=clientAuth"
ky saiou "/OU=khac/CN=prometheus-g4" ca "extendedKeyUsage=clientAuth"
ky caLa "/OU=prometheus-cell/CN=gia-mao" la "extendedKeyUsage=clientAuth"
mkdir -p tls webhooks data
cp may.crt tls/tls.crt; cp may.key tls/tls.key; cp ca.crt tls/ca.crt
for r in goi-truc nhan-tin-sre dba soc quan-tri-khoa chi-huy-su-co chu-service nen-tang chu-san-pham; do echo "http://127.0.0.1:1/$r" > "webhooks/$r"; done
GOC=$(cd - >/dev/null; pwd)
sed "s#/etc/alertmanager/webhooks#$OUT/webhooks#" "$GOC/alertmanager/alertmanager.yaml" > am.yaml
alertmanager --config.file=am.yaml --storage.path=data --web.listen-address=127.0.0.1:19093 --cluster.listen-address= > am.log 2>&1 &
PIDS+=($!)
# Tham số chart sinh, chỉ thay địa chỉ nghe, đích và đường dẫn chứng chỉ cho môi trường kiểm thử.
mapfile -t A < <(sed -e 's#^--listen=.*#--listen=127.0.0.1:19443#' -e 's#^--target=.*#--target=127.0.0.1:19093#' \
  -e 's#/etc/ghostunnel/tls#'"$OUT"'/tls#' -e 's#^--status=.*#--status=http://127.0.0.1:16060#' args)
ghostunnel "${A[@]}" > gt.log 2>&1 &
PIDS+=($!)
for _ in $(seq 40); do curl -sf http://127.0.0.1:19093/-/ready >/dev/null && curl -sf http://127.0.0.1:16060/_status >/dev/null && break; sleep 0.25; done
curl -sf http://127.0.0.1:16060/_status >/dev/null && ok "ghostunnel chạy với tham số của chart, /_status trả 200" || { hong "ghostunnel không lên"; tail -5 gt.log; }
gui() { # $1 tên chứng chỉ khách (rỗng: không gửi chứng chỉ), $2 tên cảnh báo; in mã HTTP hoặc 000 khi bắt tay TLS hỏng
  local c=(); [ -n "$1" ] && c=(--cert "$1.crt" --key "$1.key")
  curl -s -o /dev/null -w '%{http_code}' --cacert ca.crt "${c[@]}" -H 'Content-Type: application/json' \
    -d "[{\"labels\":{\"alertname\":\"$2\",\"severity\":\"critical\",\"doi\":\"sre\",\"cell\":\"g4\"}}]" \
    https://localhost:19443/api/v2/alerts || true
}
co() { curl -s "http://127.0.0.1:19093/api/v2/alerts?filter=alertname%3D%22$1%22" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
m=$(gui dung TuCellDung); [ "$m" = 200 ] && [ "$(co TuCellDung)" = 1 ] && ok "chứng chỉ khách đúng CA, đúng OU: cảnh báo tới Alertmanager ($m)" || hong "chứng chỉ đúng: $m"
m=$(gui "" KhongChungChi); [ "$m" != 200 ] && [ "$(co KhongChungChi)" = 0 ] && ok "không chứng chỉ khách: bị từ chối ($m)" || hong "không chứng chỉ: $m"
m=$(gui caLa CaLa); [ "$m" != 200 ] && [ "$(co CaLa)" = 0 ] && ok "chứng chỉ do CA khác ký: bị từ chối ($m)" || hong "CA khác: $m"
m=$(gui saiou SaiOu); [ "$m" != 200 ] && [ "$(co SaiOu)" = 0 ] && ok "chứng chỉ sai OU: bị từ chối ($m)" || hong "sai OU: $m"
r=$(amtool config routes test --config.file=am.yaml alertname=TuCellDung severity=critical doi=sre cell=g4 | tail -1)
[ "$r" = "goi-truc,nhan-tin-sre" ] && ok "cảnh báo critical từ cell định tuyến tới $r" || hong "định tuyến: $r"

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

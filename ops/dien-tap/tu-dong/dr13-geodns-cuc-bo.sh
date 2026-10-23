#!/usr/bin/env bash
# QA-15 · DR-13: thử chuyển GeoDNS bằng PowerDNS Authoritative thật chạy cục bộ (không cần Docker hay cụm), dùng bản
# ghi LUA ifurlup render từ chart deploy/charts/global-dns với chu kỳ kiểm sức khoẻ mặc định của chart (10 s, ba lần
# thất bại liên tiếp). Hai "VIP" giả trả /healthz/edge trên hai địa chỉ loopback; VIP Hoà Lạc chuyển sang FAIL là sự
# cố. Đo thời gian từ sự cố tới khi PowerDNS trả VIP phía Nam và khi Hoà Lạc khoẻ lại thì trả về; cộng TTL 60 s của
# bản ghi là thời gian lớn nhất người dùng phải chờ (so với RTO 15 phút của DR-13). Tuỳ chọn chạy thêm dns-sync thật
# với SVC-03 giả (cần go): bản ghi điểm vào cell chỉ đổi khi đổi activeDc, giữ nguyên khi SVC-03 không trả lời.
# Phần chuyển primary của pg-registry (CNPG) không mô phỏng ở đây. PowerDNS của Ubuntu 24.04 là 4.8, chart dùng 4.9:
# bản 4.8 bỏ qua tuỳ chọn interval, minimumFailures theo từng bản ghi (có từ 4.9), nên thời gian chuyển đo được là
# cận dưới (một lần thất bại); với 4.9 cần cộng thêm hai chu kỳ kiểm (suy luận theo tài liệu PowerDNS).
# Dùng: dr13-geodns-cuc-bo.sh [--bien-ban <tệp.json>] [--khong-dns-sync]. Cần pdns-server, pdns-backend-sqlite3, dig,
# helm, python3 + PyYAML.
# Mẫu "lệnh && ok || hong" là chủ ý: ok chỉ in và cộng đếm.
# shellcheck disable=SC2015
set -uo pipefail
cd "$(dirname "$0")" || exit 2
GOC=$(cd ../../.. && pwd); CHART=$GOC/deploy/charts/global-dns
BIEN_BAN=""; DNS_SYNC=1
while [ $# -gt 0 ]; do
  case "$1" in
    --bien-ban) BIEN_BAN=$2; shift 2 ;;
    --khong-dns-sync) DNS_SYNC=0; shift ;;
    *) echo "dr13: tham số lạ $1" >&2; exit 2 ;;
  esac
done
for c in pdns_server pdnsutil dig helm python3; do command -v "$c" >/dev/null || { echo "dr13: thiếu $c" >&2; exit 2; }; done
W=$(mktemp -d); IP_PD=127.0.1.2; IP_HL=127.0.1.10; IP_SN=127.0.1.11; IP_REG=127.0.1.20; CONG=5300
PIDS=()
don() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; rm -rf "$W"; }
trap don EXIT
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT + 1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG + 1)); }
hoi()  { dig +short +time=2 +tries=1 -p "$CONG" @"$IP_PD" "$1" A | sort | tr '\n' ' ' | sed 's/ $//'; }
cho()  {   # $1 tên, $2 giá trị mong đợi, $3 giới hạn giây; in số giây đã chờ
  local t0 i; t0=$(date +%s.%N)
  for ((i = 0; i < $3 * 2; i++)); do
    if [ "$(hoi "$1")" = "$2" ]; then awk -v a="$t0" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; return 0; fi
    sleep 0.5
  done
  return 1
}

# VIP giả và SVC-03 giả (cùng hành vi với deploy/charts/global-dns/tests/thu-docker.sh).
cat > "$W/vip.py" <<'PY'
import http.server, os, sys
hong = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        ok = not os.path.exists(hong)
        self.send_response(200 if ok else 503); self.end_headers(); self.wfile.write(b"OK" if ok else b"FAIL")
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer((sys.argv[1], 8080), H).serve_forever()
PY
cat > "$W/svc03.py" <<'PY'
import http.server, os, sys
w = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if os.path.exists(w + "/svc03-hong") or not self.path.startswith("/api/v1/registry/cells"):
            self.send_response(503); self.end_headers(); return
        b = open(w + "/cells.json", "rb").read()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer((sys.argv[1], 8080), H).serve_forever()
PY
echo '{"items":[{"code":"CELL-G4","primaryDc":"HOALAC","standbyDc":"PHIANAM"}],"nextCursor":null}' > "$W/cells.json"
python3 "$W/vip.py" "$IP_HL" "$W/hl-hong" & PIDS+=($!)
python3 "$W/vip.py" "$IP_SN" "$W/sn-hong" & PIDS+=($!)
python3 "$W/svc03.py" "$IP_REG" "$W" & PIDS+=($!)

# PowerDNS với gsqlite3, LUA bật như chart (vt.conf).
python3 -c 'import sqlite3, sys; c = sqlite3.connect(sys.argv[1]); c.executescript(open(sys.argv[2]).read())' \
  "$W/pdns.sqlite3" /usr/share/pdns-backend-sqlite3/schema/schema.sqlite3.sql
KHOA=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
mkdir -p "$W/pdns.d"
cat > "$W/pdns.conf" <<CF
launch=gsqlite3
gsqlite3-database=$W/pdns.sqlite3
local-address=$IP_PD
local-port=$CONG
enable-lua-records=yes
lua-health-checks-interval=__CHU_KY__
api=yes
api-key=$KHOA
webserver=yes
webserver-address=$IP_PD
webserver-port=8081
webserver-allow-from=127.0.0.0/8
include-dir=$W/pdns.d
socket-dir=$W
guardian=no
daemon=no
CF
# Chu kỳ kiểm sức khoẻ toàn cục lấy đúng giá trị của chart (pdns.luaHealthCheckInterval).
CHU_KY=$(helm show values "$CHART" | python3 -c 'import sys, yaml; print(yaml.safe_load(sys.stdin)["pdns"]["luaHealthCheckInterval"])')
sed -i "s/__CHU_KY__/$CHU_KY/" "$W/pdns.conf"
pdnsutil --config-dir="$W" create-zone thu.vn ns1.thu.vn >/dev/null 2>&1
pdns_server --config-dir="$W" > "$W/pdns.log" 2>&1 & PIDS+=($!)
sleep 2

# Bản ghi app từ bản render của chart: chỉ thay URL và VIP, giữ timeout, interval, minimumFailures mặc định.
cat > "$W/v.yaml" <<YML
common:
  - { name: app, ttl: 60, url: "http://app.thu.vn:8080/healthz/edge", vips: [["$IP_HL"], ["$IP_SN"]], stringmatch: OK,
      timeout: 2, interval: 10, minimumFailures: 3 }
YML
helm template t "$CHART" -f "$W/v.yaml" --set dnsSync.image="harbor.vt.local/okr/dns-sync@sha256:$(printf '1%.0s' $(seq 64))" > "$W/r.yaml" 2>"$W/loi" \
  || { hong "render chart global-dns"; cat "$W/loi"; exit 1; }
python3 - "$W/r.yaml" > "$W/app.lua" <<'PY'
import sys, yaml
d = next(d for d in yaml.safe_load_all(open(sys.argv[1])) if d and d["kind"] == "ConfigMap" and d["metadata"]["name"] == "pdns-vung")
print(d["data"]["app.lua"])
PY
pdnsutil --config-dir="$W" replace-rrset thu.vn app LUA 60 "$(cat "$W/app.lua")" >/dev/null 2>"$W/loi" || { hong "nạp bản ghi LUA"; cat "$W/loi"; }
echo "  PowerDNS $(pdns_server --version 2>&1 | grep -o '[0-9]\+\.[0-9]\+\.[0-9]\+' | head -1), bản ghi: $(tr '\n' ' ' < "$W/app.lua" | cut -c1-140)"

T_CHUYEN=""; T_VE=""
if cho app.thu.vn "$IP_HL" 40 >/dev/null; then ok "khi cả hai khoẻ, app trả VIP Hoà Lạc"; else hong "app ban đầu: $(hoi app.thu.vn)"; fi
touch "$W/hl-hong"
if T_CHUYEN=$(cho app.thu.vn "$IP_SN" 120); then
  ok "VIP Hoà Lạc FAIL: app trả VIP phía Nam sau $T_CHUYEN s (lua-health-checks-interval $CHU_KY s)"
else hong "không chuyển: $(hoi app.thu.vn)"; fi
rm -f "$W/hl-hong"
if T_VE=$(cho app.thu.vn "$IP_HL" 120); then ok "Hoà Lạc khoẻ lại: app trở về sau $T_VE s"; else hong "không trở về: $(hoi app.thu.vn)"; fi
TONG=$(awk -v a="${T_CHUYEN:-0}" 'BEGIN { printf "%.1f", a + 60 }')
awk -v t="$TONG" 'BEGIN { exit !(t <= 900) }' && ok "thời gian chờ lớn nhất của người dùng (chuyển + TTL 60 s) $TONG s ≤ RTO 900 s" \
  || hong "vượt RTO: $TONG s"

if [ "$DNS_SYNC" = 1 ] && command -v go >/dev/null; then
  if (cd "$GOC/services-go/dns-sync" && CGO_ENABLED=0 go build -o "$W/dns-sync" ./cmd/dns-sync) 2>"$W/loi"; then
    echo -n "$KHOA" > "$W/khoa"
    echo '{"zone":"thu.vn","ttl":60,"cells":{"CELL-G4":{"vip":{"HOALAC":"203.0.113.24","PHIANAM":"198.51.100.24"}}}}' > "$W/cfg.json"
    dongbo() { CONFIG_FILE="$W/cfg.json" INTERVAL=0 REGISTRY_URL="http://$IP_REG:8080" PDNS_URL="http://$IP_PD:8081" \
      PDNS_API_KEY_FILE="$W/khoa" "$W/dns-sync" >> "$W/ds.log" 2>&1; }
    dongbo && [ "$(hoi cell-g4.edge.thu.vn)" = 203.0.113.24 ] && ok "dns-sync: cell-g4.edge trỏ Hoà Lạc khi cell chưa chuyển (DR-13 không đổi điểm vào cell)" \
      || { hong "dns-sync lần đầu: $(hoi cell-g4.edge.thu.vn)"; tail -3 "$W/ds.log"; }
    touch "$W/svc03-hong"
    dongbo; [ "$(hoi cell-g4.edge.thu.vn)" = 203.0.113.24 ] && ok "SVC-03 không trả lời: dns-sync giữ nguyên bản ghi cell" || hong "bản ghi cell bị đổi khi SVC-03 hỏng"
    rm -f "$W/svc03-hong"
    echo '{"items":[{"code":"CELL-G4","primaryDc":"HOALAC","activeDc":"PHIANAM"}],"nextCursor":null}' > "$W/cells.json"
    dongbo && [ "$(hoi cell-g4.edge.thu.vn)" = 198.51.100.24 ] && ok "đổi activeDc (DR-10 bước 10): cell-g4.edge trỏ phía Nam" \
      || { hong "dns-sync sau khi đổi: $(hoi cell-g4.edge.thu.vn)"; tail -3 "$W/ds.log"; }
  else echo "  [BỎ QUA] dns-sync: không dựng được ($(tail -1 "$W/loi"))"; fi
else echo "  [BỎ QUA] dns-sync"; fi

[ -n "$BIEN_BAN" ] && printf '{"powerdns":"%s","chuyenGiay":%s,"traVeGiay":%s,"choLonNhatGiay":%s,"dat":%s,"hong":%s}\n' \
  "$(pdns_server --version 2>&1 | grep -o '[0-9]\+\.[0-9]\+\.[0-9]\+' | head -1)" "${T_CHUYEN:-null}" "${T_VE:-null}" "$TONG" "$DAT" "$HONG" > "$BIEN_BAN"
echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]

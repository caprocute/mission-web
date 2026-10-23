#!/usr/bin/env bash
# INF-20 · Thử GeoDNS bằng container (không cần cụm): PowerDNS Authoritative 4.9 thật với bản ghi LUA lấy từ bản render
# của chart, hai "VIP" giả trả /healthz/edge, và dns-sync (services-go/dns-sync) ghi bản ghi cell qua API PowerDNS theo
# một SVC-03 giả. Kiểm:
#   1. app trả VIP Hoà Lạc khi khoẻ; tắt VIP Hoà Lạc thì trả VIP phía Nam; bật lại thì về Hoà Lạc (DR-10, DR-13).
#   2. dns-sync ghi cell-g4.edge theo TTDL đang hoạt động; đổi activeDc thì bản ghi đổi; SVC-03 không trả lời thì giữ
#      nguyên bản ghi (không bao giờ tự chuyển vùng cell).
# Cần docker, go, helm, dig. Ảnh qua mirror.gcr.io. Dọn sạch container và mạng khi kết thúc.
set -uo pipefail
cd "$(dirname "$0")/.."
GOC=$(cd ../../.. && pwd)
W=$(mktemp -d); MANG=gdns-thu; PD=gdns-pdns
PDNS_ANH=${PDNS_ANH:-mirror.gcr.io/powerdns/pdns-auth-49@sha256:ae9747404642013be65cfb199c019eab8d775dffb4dd70973ca3718d6fa1c107}
PY_ANH=${PY_ANH:-mirror.gcr.io/library/python:3.13-alpine}
don() { docker rm -f "$PD" gdns-vip-hl gdns-vip-sn gdns-svc03 >/dev/null 2>&1; docker network rm "$MANG" >/dev/null 2>&1; rm -rf "${W:?}"; }
trap don EXIT; don; W=$(mktemp -d)
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
hoi()  { dig +short +time=2 +tries=1 @"$IP_PD" "$1" A | sort | tr '\n' ' ' | sed 's/ $//'; }
cho()  { local i; for i in $(seq 1 "$3"); do [ "$(hoi "$1")" = "$2" ] && return 0; sleep 2; done; return 1; }

docker network create --subnet 172.30.19.0/24 "$MANG" >/dev/null
IP_HL=172.30.19.10; IP_SN=172.30.19.11; IP_REG=172.30.19.20; IP_PD=172.30.19.2

# VIP giả: trả OK cho /healthz/edge, FAIL khi có tệp /tmp/hong (mô phỏng điều kiện của edge-health không đạt).
cat > "$W/vip.py" <<'EOF'
import http.server, os
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        ok = not os.path.exists("/tmp/hong")
        self.send_response(200 if ok else 503); self.end_headers(); self.wfile.write(b"OK" if ok else b"FAIL")
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
EOF
# SVC-03 giả: GET /api/v1/registry/cells đọc /tmp/cells.json; có /tmp/hong thì trả 503.
cat > "$W/svc03.py" <<'EOF'
import http.server, os
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if os.path.exists("/tmp/hong") or not self.path.startswith("/api/v1/registry/cells"):
            self.send_response(503); self.end_headers(); return
        b = open("/tmp/cells.json", "rb").read()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
EOF
echo '{"items":[{"code":"CELL-G4","primaryDc":"HOALAC","standbyDc":"PHIANAM"}],"nextCursor":null}' > "$W/cells.json"
for x in "gdns-vip-hl $IP_HL vip.py" "gdns-vip-sn $IP_SN vip.py" "gdns-svc03 $IP_REG svc03.py"; do
  set -- $x
  docker run -d --name "$1" --network "$MANG" --ip "$2" -v "$W/$3:/app.py:ro" -v "$W:/w:ro" "$PY_ANH" \
    sh -c 'cp /w/cells.json /tmp/ 2>/dev/null; exec python /app.py' >/dev/null || hong "chạy $1"
done

# PowerDNS: cấu hình LUA như chart (vt.conf), CSDL gsqlite3 mặc định của ảnh thay cho gpgsql.
KHOA=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
cat > "$W/lua.conf" <<EOF
enable-lua-records=yes
lua-health-checks-interval=2
api=yes
api-key=$KHOA
webserver=yes
webserver-address=0.0.0.0
webserver-port=8081
webserver-allow-from=0.0.0.0/0
local-address=0.0.0.0
EOF
docker run -d --name "$PD" --network "$MANG" --ip "$IP_PD" -v "$W/lua.conf:/etc/powerdns/pdns.d/lua.conf:ro" "$PDNS_ANH" >/dev/null \
  || { hong "chạy PowerDNS"; exit 1; }
sleep 3
docker exec "$PD" pdnsutil create-zone thu.vn ns1.thu.vn >/dev/null 2>&1
docker restart "$PD" >/dev/null; sleep 3   # vùng mới chỉ thấy sau khi nạp lại bộ đệm vùng

# Bản ghi app lấy từ bản render của chart (helper gdns.ifurlup), chỉ thay URL, VIP và chu kỳ cho nhanh.
cat > "$W/v.yaml" <<EOF
common:
  - { name: app, ttl: 60, url: "http://app.thu.vn:8080/healthz/edge", vips: [["$IP_HL"], ["$IP_SN"]], stringmatch: OK,
      timeout: 2, interval: 2, minimumFailures: 2 }
EOF
helm template t . -f "$W/v.yaml" --set dnsSync.image=harbor.vt.local/okr/dns-sync@sha256:$(printf '1%.0s' $(seq 64)) > "$W/r.yaml" 2>"$W/loi" \
  || { hong "render chart"; cat "$W/loi"; exit 1; }
python3 - "$W/r.yaml" > "$W/app.lua" <<'EOF'
import sys, yaml
d = next(d for d in yaml.safe_load_all(open(sys.argv[1])) if d and d["kind"] == "ConfigMap" and d["metadata"]["name"] == "pdns-vung")
print(d["data"]["app.lua"])
EOF
docker exec "$PD" pdnsutil replace-rrset thu.vn app LUA 60 "$(cat "$W/app.lua")" >/dev/null 2>"$W/loi" || { hong "nạp bản ghi LUA"; cat "$W/loi"; }

cho app.thu.vn "$IP_HL" 15 && ok "app trả VIP Hoà Lạc khi cả hai khoẻ" || hong "app: $(hoi app.thu.vn)"
docker exec gdns-vip-hl touch /tmp/hong
cho app.thu.vn "$IP_SN" 20 && ok "VIP Hoà Lạc trả FAIL thì app chuyển sang VIP phía Nam" || hong "không chuyển: $(hoi app.thu.vn)"
docker exec gdns-vip-hl rm -f /tmp/hong
cho app.thu.vn "$IP_HL" 20 && ok "Hoà Lạc khoẻ lại thì app trở về Hoà Lạc" || hong "không trở về: $(hoi app.thu.vn)"

# dns-sync thật chạy một vòng (INTERVAL=0) với API PowerDNS thật.
( cd "$GOC/services-go/dns-sync" && CGO_ENABLED=0 go build -o "$W/dns-sync" ./cmd/dns-sync ) || hong "build dns-sync"
echo -n "$KHOA" > "$W/khoa"
cat > "$W/cfg.json" <<'EOF'
{"zone":"thu.vn","ttl":60,"cells":{"CELL-G4":{"vip":{"HOALAC":"203.0.113.24","PHIANAM":"198.51.100.24"}}}}
EOF
dongbo() { CONFIG_FILE="$W/cfg.json" INTERVAL=0 REGISTRY_URL="http://$IP_REG:8080" PDNS_URL="http://$IP_PD:8081" \
  PDNS_API_KEY_FILE="$W/khoa" "$W/dns-sync" >> "$W/ds.log" 2>&1; }
dongbo && [ "$(hoi cell-g4.edge.thu.vn)" = 203.0.113.24 ] && ok "dns-sync ghi cell-g4.edge theo TTDL chính (Hoà Lạc)" \
  || { hong "dns-sync lần đầu: $(hoi cell-g4.edge.thu.vn)"; tail -3 "$W/ds.log"; }
docker exec gdns-svc03 sh -c 'echo "{\"items\":[{\"code\":\"CELL-G4\",\"primaryDc\":\"HOALAC\",\"activeDc\":\"PHIANAM\"}],\"nextCursor\":null}" > /tmp/cells.json'
dongbo && [ "$(hoi cell-g4.edge.thu.vn)" = 198.51.100.24 ] && ok "đổi activeDc sang phía Nam thì cell-g4.edge đổi theo" \
  || { hong "dns-sync sau khi đổi: $(hoi cell-g4.edge.thu.vn)"; tail -3 "$W/ds.log"; }
dongbo && grep -q '"unchanged":1' "$W/ds.log" && ok "vòng lặp lại không ghi khi bản ghi đã đúng" || hong "vòng lặp lại vẫn ghi"
docker exec gdns-svc03 touch /tmp/hong
dongbo && hong "SVC-03 hỏng mà dns-sync báo thành công" || true
[ "$(hoi cell-g4.edge.thu.vn)" = 198.51.100.24 ] && ok "SVC-03 không trả lời thì dns-sync giữ nguyên bản ghi cell" || hong "bản ghi cell bị đổi khi SVC-03 hỏng"

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]

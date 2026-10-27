#!/bin/bash
# Chạy phân hệ web Mission trên máy trạm của lập trình viên (macOS/Linux), nối Keycloak trên server dev qua hầm SSH.
# Dùng (tại gốc repo, đã có tệp .env với DEV_SERVER_HOST, DEV_SERVER_SSH_PORT, DEV_SERVER_USER):
#   scripts/chay-may-tram.sh            # dữ liệu theo config.json của repo (gọi API thật ở /api)
#   scripts/chay-may-tram.sh --gia-lap  # bật dữ liệu giả lập (useMockApi) chỉ cho lần chạy này, không sửa repo
# Mở http://localhost:4200 khi thấy dòng "SẴN SÀNG". Ctrl+C để dừng tất cả (hầm SSH và các tiến trình ng serve).
set -euo pipefail
cd "$(dirname "$0")/.."
GOC=$(pwd)
CFG="$GOC/frontend/projects/fe-shell/public/config.json"
GIA_LAP=0; [ "${1:-}" = "--gia-lap" ] && GIA_LAP=1

[ -f .env ] || { echo "Thiếu tệp .env ở gốc repo (xem .env.example)"; exit 1; }
set -a; . ./.env; set +a
: "${DEV_SERVER_HOST:?Thiếu DEV_SERVER_HOST trong .env}" "${DEV_SERVER_USER:?Thiếu DEV_SERVER_USER trong .env}"
command -v node >/dev/null || { echo "Cần Node.js 22 trở lên"; exit 1; }

# 1. Hầm SSH: Keycloak 35080 (bắt buộc), thêm các cổng hạ tầng khác qua TUNNEL_LOCAL_FORWARDS trong .env nếu cần.
FWD=${TUNNEL_LOCAL_FORWARDS:-"35080:127.0.0.1:35080"}
ARGS=(); for f in $FWD; do ARGS+=(-L "$f"); done
echo "Mở hầm SSH tới server dev (có thể hỏi mật khẩu SSH)..."
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -p "${DEV_SERVER_SSH_PORT:-22}" "${ARGS[@]}" \
  "$DEV_SERVER_USER@$DEV_SERVER_HOST" &
PIDS=($!)
don_dep() { echo; echo "Đang dừng..."; kill "${PIDS[@]}" 2>/dev/null || true; [ -f "$GOC/.tmp-config.bak" ] && mv "$GOC/.tmp-config.bak" "$CFG"; }
trap don_dep EXIT INT TERM
for _ in $(seq 60); do curl -s -o /dev/null http://127.0.0.1:35080/realms/mission && break; sleep 2; done
curl -sf -o /dev/null http://127.0.0.1:35080/realms/mission \
  && echo "Keycloak qua hầm: OK" || echo "CẢNH BÁO: chưa thấy Keycloak ở 127.0.0.1:35080 (kiểm tra container mission-dev-keycloak trên server)"

# 2. Cài phụ thuộc lần đầu.
cd frontend
[ -d node_modules ] || npm ci --no-audit --no-fund

# 3. Tuỳ chọn dữ liệu giả lập: sửa tạm config.json, tự khôi phục khi dừng.
if [ $GIA_LAP -eq 1 ]; then
  cp "$CFG" "$GOC/.tmp-config.bak"
  node -e "const f=process.argv[1],c=require(f);c.useMockApi=true;require('fs').writeFileSync(f,JSON.stringify(c,null,2)+'\n')" "$CFG"
  echo "Đã bật dữ liệu giả lập cho lần chạy này"
fi

# 4. Chạy 9 remote và shell (cổng theo tools/remotes/remotes.json, shell 4200).
LOG="$GOC/.chay-may-tram-logs"; mkdir -p "$LOG"
while read -r proj port; do
  npx ng serve "$proj" --port "$port" >"$LOG/$proj.log" 2>&1 &
  PIDS+=($!)
done < <(node -e "for (const r of require('./tools/remotes/remotes.json').remotes) console.log(r.project, r.port)")
npx ng serve fe-shell --port 4200 >"$LOG/fe-shell.log" 2>&1 &
PIDS+=($!)
echo "Đang biên dịch shell và 9 remote (lần đầu vài phút); log ở $LOG/"
for _ in $(seq 300); do curl -sf -o /dev/null http://localhost:4200/ && break; sleep 2; done
echo "SẴN SÀNG: mở http://localhost:4200  (Ctrl+C để dừng)"
wait

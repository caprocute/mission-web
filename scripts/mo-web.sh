#!/usr/bin/env bash
# Mở ứng dụng web Mission trên máy chủ dev bằng một lệnh: dựng đường hầm SSH cho các cổng ứng dụng, tự nối lại khi
# đường hầm rớt, rồi mở trình duyệt tại http://127.0.0.1:35000 (YC-HT-03). Khác scripts/dev-tunnel.sh (mở toàn bộ cổng
# hạ tầng cho người phát triển), kịch bản này chỉ mở cổng mà trình duyệt cần:
#   35000 web và API (APISIX), 35080 đăng nhập (Keycloak), 35900 tải lên, tải về tệp (MinIO), 35088 báo cáo nhúng (Superset).
#
#   ./scripts/mo-web.sh            # mở đường hầm (chạy nền, tự nối lại) và mở trình duyệt
#   ./scripts/mo-web.sh trang-thai # xem các cổng đã thông chưa
#   ./scripts/mo-web.sh dung       # đóng đường hầm
#
# Đọc DEV_SERVER_HOST, DEV_SERVER_SSH_PORT, DEV_SERVER_USER, DEV_SERVER_PASSWORD (hoặc tệp .sshpass) từ .env ở gốc repo;
# không in bí mật. Nhật ký ở /tmp/mission-mo-web.log.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${REPO_ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${REPO_ROOT}/.env"
  set +a
fi
HOST="${DEV_SERVER_HOST:-}"
PORT="${DEV_SERVER_SSH_PORT:-2222}"
USER_="${DEV_SERVER_USER:-provision}"
PORTS="${MO_WEB_PORTS:-35000 35080 35900 35088}"
URL="http://127.0.0.1:35000"
PID_FILE="/tmp/mission-mo-web.pid"
LOG_FILE="/tmp/mission-mo-web.log"

thong() { nc -z -w 2 127.0.0.1 "$1" >/dev/null 2>&1; }

trang_thai() {
  local ok=0
  for p in ${PORTS}; do
    if thong "${p}"; then echo "  [thông] 127.0.0.1:${p}"; else echo "  [chưa]  127.0.0.1:${p}"; ok=1; fi
  done
  return "${ok}"
}

# Vòng giữ đường hầm: ssh chạy tiền cảnh trong tiến trình nền, rớt thì nối lại sau 5 giây. Cổng nào đã có tiến trình
# khác chiếm (ví dụ dev-tunnel.sh) thì bỏ qua để không lỗi ExitOnForwardFailure.
giu_duong_ham() {
  while true; do
    local args=()
    for p in ${PORTS}; do
      thong "${p}" || args+=(-L "127.0.0.1:${p}:127.0.0.1:${p}")
    done
    if [[ ${#args[@]} -gt 0 ]]; then
      local ssh_cmd=(ssh -N -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ServerAliveCountMax=3
        -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -p "${PORT}" "${args[@]}" "${USER_}@${HOST}")
      echo "$(date '+%F %T') nối đường hầm: ${args[*]}"
      if [[ -f "${REPO_ROOT}/.sshpass" ]] && command -v sshpass >/dev/null 2>&1; then
        sshpass -f "${REPO_ROOT}/.sshpass" "${ssh_cmd[@]}" || true
      elif [[ -n "${DEV_SERVER_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
        SSHPASS="${DEV_SERVER_PASSWORD}" sshpass -e "${ssh_cmd[@]}" || true
      else
        "${ssh_cmd[@]}" || true
      fi
      echo "$(date '+%F %T') đường hầm rớt, nối lại sau 5 giây"
    fi
    sleep 5
  done
}

dung() {
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    local pid
    pid="$(cat "${PID_FILE}")"
    pkill -P "${pid}" 2>/dev/null || true
    kill "${pid}" 2>/dev/null || true
    echo "Đã đóng đường hầm."
  else
    echo "Không có đường hầm nào do kịch bản này mở."
  fi
  rm -f "${PID_FILE}"
}

mo() {
  [[ -n "${HOST}" ]] || { echo "Chưa đặt DEV_SERVER_HOST trong .env ở gốc repo." >&2; exit 1; }
  if ! { [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; }; then
    nohup bash "$0" __giu >>"${LOG_FILE}" 2>&1 &
    echo $! >"${PID_FILE}"
  fi
  for _ in $(seq 1 30); do
    trang_thai >/dev/null && break
    sleep 1
  done
  if trang_thai; then
    echo "Đã mở. Truy cập ${URL}"
    if command -v open >/dev/null 2>&1; then open "${URL}"
    elif command -v xdg-open >/dev/null 2>&1; then xdg-open "${URL}" >/dev/null 2>&1 || true
    fi
  else
    echo "Chưa thông đủ cổng sau 30 giây, xem ${LOG_FILE}." >&2
    exit 1
  fi
}

case "${1:-mo}" in
  mo) mo ;;
  trang-thai) trang_thai ;;
  dung) dung ;;
  __giu) giu_duong_ham ;;
  *) echo "Cách dùng: $0 [mo|trang-thai|dung]" >&2; exit 1 ;;
esac

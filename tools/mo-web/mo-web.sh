#!/usr/bin/env bash
# Mở ứng dụng web Mission trên máy chủ dev bằng một lệnh: dựng đường hầm SSH cho các cổng ứng dụng, tự nối lại khi
# rớt, rồi mở trình duyệt tại http://127.0.0.1:35000 (YC-HT-03). Gói tự chứa: chỉ cần thư mục này, ssh và bash
# (macOS bash 3.2 hoặc Linux); cấu hình kết nối đọc từ tệp cau-hinh.env cùng thư mục (chép từ cau-hinh.env.example).
# Cổng mở: 35000 web và API, 35080 đăng nhập, 35900 tải lên/tải về tệp, 35088 báo cáo nhúng.
#
#   ./mo-web.sh             # mở đường hầm (chạy nền, tự nối lại) và mở trình duyệt
#   ./mo-web.sh trang-thai  # xem các cổng đã thông chưa
#   ./mo-web.sh dung        # đóng đường hầm
#
# Biến môi trường đã đặt sẵn (ví dụ scripts/mo-web.sh trong repo nạp từ .env) được ưu tiên hơn cau-hinh.env.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${DIR}/cau-hinh.env" ]]; then
  # Giữ giá trị đã có trong môi trường, chỉ bổ sung biến còn thiếu.
  while IFS='=' read -r k v; do
    case "${k}" in '' | \#*) continue ;; esac
    v="${v%\"}"; v="${v#\"}"
    if [[ -z "${!k:-}" ]]; then export "${k}=${v}"; fi
  done <"${DIR}/cau-hinh.env"
fi
HOST="${DEV_SERVER_HOST:-}"
PORT="${DEV_SERVER_SSH_PORT:-2222}"
USER_="${DEV_SERVER_USER:-provision}"
KEY="${DEV_SERVER_SSH_KEY:-}"
PORTS="${MO_WEB_PORTS:-35000 35080 35900 35088}"
URL="http://127.0.0.1:35000"
TMP="${TMPDIR:-/tmp}"
TMP="${TMP%/}"
PID_FILE="${TMP}/mission-mo-web.pid"
LOG_FILE="${TMP}/mission-mo-web.log"
# Nhật ký riêng của ssh, đồng thời là dấu nhận ra đúng tiến trình ssh của kịch bản này (dev-tunnel.sh mở cùng cổng).
SSH_LOG="${TMP}/mission-mo-web-ssh.log"

thong() {
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 2 127.0.0.1 "$1" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
  fi
}

trang_thai() {
  local ok=0 p
  for p in ${PORTS}; do
    if thong "${p}"; then echo "  [thông] 127.0.0.1:${p}"; else echo "  [chưa]  127.0.0.1:${p}"; ok=1; fi
  done
  return "${ok}"
}

# Vòng giữ đường hầm: ssh chạy tiền cảnh trong tiến trình nền, rớt thì nối lại sau 5 giây. Cổng nào đã có tiến trình
# khác chiếm (ví dụ dev-tunnel.sh) thì bỏ qua để không lỗi ExitOnForwardFailure.
giu_duong_ham() {
  while true; do
    local args=() p
    for p in ${PORTS}; do
      thong "${p}" || args+=(-L "127.0.0.1:${p}:127.0.0.1:${p}")
    done
    if [[ ${#args[@]} -gt 0 ]]; then
      local ssh_cmd=(ssh -N -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ServerAliveCountMax=3
        -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -E "${SSH_LOG}" -p "${PORT}")
      if [[ -n "${KEY}" ]]; then ssh_cmd+=(-i "${KEY}"); fi
      ssh_cmd+=("${args[@]}" "${USER_}@${HOST}")
      echo "$(date '+%F %T') nối đường hầm: ${args[*]}"
      if [[ -n "${DEV_SERVER_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
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
    kill "$(cat "${PID_FILE}")" 2>/dev/null || true
    echo "Đã đóng đường hầm."
  else
    echo "Không có đường hầm nào do kịch bản này mở."
  fi
  # ssh nằm dưới sshpass nên không là con trực tiếp của vòng giữ; nhận theo tệp nhật ký riêng để không chạm
  # đường hầm khác mở cùng cổng.
  pkill -f -- "-E ${SSH_LOG}" 2>/dev/null || true
  rm -f "${PID_FILE}"
}

mo() {
  if [[ -z "${HOST}" ]]; then
    echo "Chưa có thông tin máy chủ. Chép ${DIR}/cau-hinh.env.example thành cau-hinh.env rồi điền DEV_SERVER_HOST." >&2
    exit 1
  fi
  if ! command -v ssh >/dev/null 2>&1; then
    echo "Máy chưa có lệnh ssh (Linux: cài gói openssh-client)." >&2
    exit 1
  fi
  if [[ -n "${DEV_SERVER_PASSWORD:-}" ]] && ! command -v sshpass >/dev/null 2>&1; then
    echo "Có mật khẩu nhưng máy chưa cài sshpass; ssh sẽ không tự đăng nhập được khi chạy nền. Cài sshpass hoặc dùng khoá SSH." >&2
  fi
  if ! { [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; }; then
    nohup bash "${DIR}/mo-web.sh" __giu >>"${LOG_FILE}" 2>&1 </dev/null &
    echo $! >"${PID_FILE}"
  fi
  local i
  for i in $(seq 1 30); do
    if trang_thai >/dev/null; then break; fi
    sleep 1
  done
  if trang_thai; then
    echo "Đã mở. Truy cập ${URL}"
    if command -v open >/dev/null 2>&1; then open "${URL}"
    elif command -v xdg-open >/dev/null 2>&1; then xdg-open "${URL}" >/dev/null 2>&1 || true
    fi
  else
    echo "Chưa thông đủ cổng sau 30 giây, xem ${LOG_FILE} và ${SSH_LOG}." >&2
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

#!/usr/bin/env bash
# Kịch bản quản lý đường hầm SSH (SSH Tunnel) tới máy chủ dev dùng chung (YC-HT-03).
# Khi máy chủ dev hạn chế mở cổng ra ngoài và chỉ mở cổng SSH (mặc định 2222),
# kịch bản này ánh xạ toàn bộ cổng hạ tầng dùng chung (35xxx) về 127.0.0.1
# và hỗ trợ mở ngược (-R) cổng dịch vụ cục bộ lên máy chủ khi cần.
#
# Cách dùng:
#   ./scripts/dev-tunnel.sh start    # Khởi tạo SSH tunnel chạy nền
#   ./scripts/dev-tunnel.sh status   # Kiểm tra trạng thái tunnel và các cổng đã thông
#   ./scripts/dev-tunnel.sh stop     # Dừng SSH tunnel

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"
PID_FILE="/tmp/mission-dev-tunnel.pid"
CONTROL_SOCKET="/tmp/mission-dev-tunnel.sock"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

DEV_SERVER_HOST="${DEV_SERVER_HOST:-}"
DEV_SERVER_SSH_PORT="${DEV_SERVER_SSH_PORT:-2222}"
DEV_SERVER_USER="${DEV_SERVER_USER:-provision}"
TUNNEL_BIND_ADDRESS="${TUNNEL_BIND_ADDRESS:-127.0.0.1}"
TUNNEL_LOCAL_FORWARDS="${TUNNEL_LOCAL_FORWARDS:-35432:127.0.0.1:35432 35379:127.0.0.1:35379 35080:127.0.0.1:35080 35081:127.0.0.1:35081 35082:127.0.0.1:35082 35083:127.0.0.1:35083 35092:127.0.0.1:35092 35094:127.0.0.1:35094 35123:127.0.0.1:35123 35300:127.0.0.1:35300 35900:127.0.0.1:35900 35901:127.0.0.1:35901 35920:127.0.0.1:35920 35181:127.0.0.1:35181 35820:127.0.0.1:35820}"
TUNNEL_REMOTE_FORWARDS="${TUNNEL_REMOTE_FORWARDS:-}"

check_port() {
  local host="$1"
  local port="$2"
  nc -z -w 2 "${host}" "${port}" >/dev/null 2>&1
}

cmd_status() {
  echo "=== Trạng thái SSH Tunnel tới ${DEV_SERVER_USER}@${DEV_SERVER_HOST}:${DEV_SERVER_SSH_PORT} ==="
  if [[ -S "${CONTROL_SOCKET}" ]] && ssh -S "${CONTROL_SOCKET}" -p "${DEV_SERVER_SSH_PORT}" -O check "${DEV_SERVER_USER}@${DEV_SERVER_HOST}" >/dev/null 2>&1; then
    echo "Control socket: ĐANG HOẠT ĐỘNG (${CONTROL_SOCKET})"
  else
    echo "Control socket: chưa kích hoạt qua socket riêng (kiểm tra trực tiếp cổng...)"
  fi

  local all_ok=0
  for spec in ${TUNNEL_LOCAL_FORWARDS}; do
    local lport="${spec%%:*}"
    if check_port "${TUNNEL_BIND_ADDRESS}" "${lport}"; then
      echo "  [OK]   ${TUNNEL_BIND_ADDRESS}:${lport} -> ${spec#*:}"
    else
      echo "  [DOWN] ${TUNNEL_BIND_ADDRESS}:${lport} -> ${spec#*:}"
      all_ok=1
    fi
  done
  return "${all_ok}"
}

cmd_start() {
  if [[ -z "${DEV_SERVER_HOST}" ]]; then
    echo "Lỗi: Chưa cấu hình DEV_SERVER_HOST trong .env" >&2
    exit 1
  fi

  if cmd_status >/dev/null 2>&1; then
    echo "SSH Tunnel đã hoạt động và toàn bộ cổng hạ tầng đều đã thông."
    cmd_status
    return 0
  fi

  local forward_args=()
  for spec in ${TUNNEL_LOCAL_FORWARDS}; do
    local lport="${spec%%:*}"
    if ! check_port "${TUNNEL_BIND_ADDRESS}" "${lport}"; then
      forward_args+=("-L" "${TUNNEL_BIND_ADDRESS}:${spec}")
    fi
  done

  for rspec in ${TUNNEL_REMOTE_FORWARDS}; do
    forward_args+=("-R" "${rspec}")
  done

  if [[ ${#forward_args[@]} -eq 0 ]]; then
    echo "Tất cả cổng trong TUNNEL_LOCAL_FORWARDS đã được mở sẵn."
    cmd_status
    return 0
  fi

  local ssh_base=(
    ssh
    -f -N
    -M -S "${CONTROL_SOCKET}"
    -o StrictHostKeyChecking=no
    -o ServerAliveInterval=30
    -o ServerAliveCountMax=3
    -o ExitOnForwardFailure=yes
    -p "${DEV_SERVER_SSH_PORT}"
    "${forward_args[@]}"
    "${DEV_SERVER_USER}@${DEV_SERVER_HOST}"
  )

  if [[ -f "${REPO_ROOT}/.sshpass" ]] && command -v sshpass >/dev/null 2>&1; then
    sshpass -f "${REPO_ROOT}/.sshpass" "${ssh_base[@]}"
  elif [[ -n "${DEV_SERVER_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
    SSHPASS="${DEV_SERVER_PASSWORD}" sshpass -e "${ssh_base[@]}"
  else
    "${ssh_base[@]}"
  fi

  sleep 1
  cmd_status
}

cmd_stop() {
  if [[ -S "${CONTROL_SOCKET}" ]]; then
    ssh -S "${CONTROL_SOCKET}" -p "${DEV_SERVER_SSH_PORT}" -O exit "${DEV_SERVER_USER}@${DEV_SERVER_HOST}" || true
    rm -f "${CONTROL_SOCKET}" "${PID_FILE}"
    echo "Đã dừng SSH Tunnel."
  else
    echo "Không tìm thấy phiên SSH Tunnel qua ${CONTROL_SOCKET}."
  fi
}

case "${1:-status}" in
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  restart)
    cmd_stop || true
    cmd_start
    ;;
  *)
    echo "Cách dùng: $0 {start|stop|status|restart}" >&2
    exit 1
    ;;
esac

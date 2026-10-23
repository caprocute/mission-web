#!/usr/bin/env bash
# =============================================================================
# Đồng bộ infra/dev của repo lên máy chủ dev dùng chung và áp lại compose
# project `mission-dev` (INF-22). Chạy từ một bản checkout trên máy trạm có SSH
# tới máy chủ (ví dụ phiên trên máy Mac), không chạy trên máy chủ.
#
# Chỉ thao tác container của project `mission-dev`: không dùng --remove-orphans,
# không `docker system prune`, không đụng container khác trên máy chủ.
#
# Thông tin truy cập đọc từ `.env` ở gốc repo (không commit):
#   DEV_SERVER_HOST, DEV_SERVER_SSH_PORT (mặc định 2222), DEV_SERVER_USER,
#   DEV_SERVER_INFRA_DIR  thư mục infra/dev trên máy chủ (bắt buộc, chứa `.env` của máy chủ),
#   DEV_SERVER_PROFILES   các profile áp lại (mặc định "core security events").
# Mật khẩu SSH (nếu không dùng khoá) lấy từ `.sshpass` hoặc DEV_SERVER_PASSWORD như dev-tunnel.sh.
#
# Cách dùng:
#   infra/dev/scripts/sync-server.sh plan    # so khoá .env, xem tệp sẽ chép, kiểm compose config; không đổi gì
#   infra/dev/scripts/sync-server.sh apply   # chép tệp rồi `docker compose up -d` cho các profile đã chọn
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${INFRA_DIR}/../.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"
PROJECT="mission-dev"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

DEV_SERVER_HOST="${DEV_SERVER_HOST:-}"
DEV_SERVER_SSH_PORT="${DEV_SERVER_SSH_PORT:-2222}"
DEV_SERVER_USER="${DEV_SERVER_USER:-provision}"
DEV_SERVER_INFRA_DIR="${DEV_SERVER_INFRA_DIR:-}"
DEV_SERVER_PROFILES="${DEV_SERVER_PROFILES:-core security events}"

# Tệp và thư mục chỉ có trên máy chủ, không bao giờ ghi đè: .env (bí mật), data/, softhsm/tokens/,
# softhsm/tsa/, SHA256SUMS. Bản rsync loại trừ tường minh; bản tar chỉ chép tệp git theo dõi.

die() {
  echo "Lỗi: $*" >&2
  exit 1
}

[[ -n "${DEV_SERVER_HOST}" ]] || die "chưa cấu hình DEV_SERVER_HOST trong .env"
[[ -n "${DEV_SERVER_INFRA_DIR}" ]] || die "chưa cấu hình DEV_SERVER_INFRA_DIR trong .env"
case "${DEV_SERVER_INFRA_DIR}" in
  /*) ;;
  *) die "DEV_SERVER_INFRA_DIR phải là đường dẫn tuyệt đối" ;;
esac

SSH_OPTS=(-p "${DEV_SERVER_SSH_PORT}" -o ServerAliveInterval=30)
TARGET="${DEV_SERVER_USER}@${DEV_SERVER_HOST}"

with_pass() {
  if [[ -f "${REPO_ROOT}/.sshpass" ]] && command -v sshpass >/dev/null 2>&1; then
    sshpass -f "${REPO_ROOT}/.sshpass" "$@"
  elif [[ -n "${DEV_SERVER_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
    SSHPASS="${DEV_SERVER_PASSWORD}" sshpass -e "$@"
  else
    "$@"
  fi
}

remote() {
  with_pass ssh "${SSH_OPTS[@]}" "${TARGET}" "$@"
}

profile_args() {
  local out=""
  for p in ${DEV_SERVER_PROFILES}; do
    out+=" --profile ${p}"
  done
  printf '%s' "${out}"
}

compose_cmd() {
  printf 'cd %q && docker compose -p %q --env-file .env%s' \
    "${DEV_SERVER_INFRA_DIR}" "${PROJECT}" "$(profile_args)"
}

# Khoá có trong .env.example mà .env của máy chủ còn thiếu (chỉ in tên khoá, không in giá trị).
check_env_keys() {
  echo "==> So khoá .env của máy chủ với infra/dev/.env.example"
  local expected remote_keys missing
  expected="$(grep -E '^[A-Z0-9_]+=' "${INFRA_DIR}/.env.example" | cut -d= -f1 | sort -u)"
  remote_keys="$(remote "grep -E '^[A-Z0-9_]+=' $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.env") | cut -d= -f1 | sort -u" || true)"
  [[ -n "${remote_keys}" ]] || die "không đọc được ${DEV_SERVER_INFRA_DIR}/.env trên máy chủ"
  missing="$(comm -23 <(printf '%s\n' "${expected}") <(printf '%s\n' "${remote_keys}"))"
  if [[ -n "${missing}" ]]; then
    echo "  Khoá còn thiếu trên máy chủ (bổ sung giá trị thật trước khi apply; mặc định của compose sẽ được dùng):"
    while IFS= read -r key; do
      printf '    %s\n' "${key}"
    done <<<"${missing}"
  else
    echo "  Đủ khoá."
  fi
}

copy_files() {
  local dry="$1"
  local commit
  commit="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
  if ! git -C "${REPO_ROOT}" diff --quiet -- infra/dev; then
    echo "  Cảnh báo: infra/dev có thay đổi chưa commit; bản chép sẽ khác commit ${commit}."
  fi
  if command -v rsync >/dev/null 2>&1 && remote "command -v rsync" >/dev/null 2>&1; then
    # Luật đầu tiên khớp được áp: giữ .env.example trước khi loại *.env.
    local args=(-rlptzv --include=.env.example --exclude=.env --exclude='*.env')
    for e in data/ softhsm/tokens/ softhsm/tsa/ SHA256SUMS; do
      args+=("--exclude=${e}")
    done
    [[ "${dry}" == "yes" ]] && args+=(--dry-run)
    echo "==> Chép infra/dev (commit ${commit}) bằng rsync$([[ "${dry}" == "yes" ]] && echo ' (thử, không ghi)')"
    with_pass rsync "${args[@]}" -e "ssh ${SSH_OPTS[*]}" "${INFRA_DIR}/" "${TARGET}:${DEV_SERVER_INFRA_DIR}/"
  else
    echo "==> Máy trạm hoặc máy chủ không có rsync: chép bằng tar (tệp đã xoá trong repo không bị xoá trên máy chủ)"
    if [[ "${dry}" == "yes" ]]; then
      (cd "${INFRA_DIR}" && git ls-files)
      return 0
    fi
    # git ls-files in đường dẫn tương đối với thư mục hiện tại; tar của macOS và GNU đều nhận --null -T.
    (cd "${INFRA_DIR}" && git ls-files -z | tar --null -T - -cf -) \
      | remote "mkdir -p $(printf '%q' "${DEV_SERVER_INFRA_DIR}") && tar -C $(printf '%q' "${DEV_SERVER_INFRA_DIR}") -xf -"
  fi
  if [[ "${dry}" != "yes" ]]; then
    remote "printf '%s\n' $(printf '%q' "${commit}") > $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.repo-commit")"
  fi
}

cmd_plan() {
  check_env_keys
  echo "==> Commit đang áp trên máy chủ: $(remote "cat $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.repo-commit") 2>/dev/null" || echo 'chưa ghi')"
  copy_files yes
  echo "==> Kiểm compose config trên máy chủ (bản hiện có, trước khi chép)"
  remote "$(compose_cmd) config -q" && echo "  Hợp lệ."
}

cmd_apply() {
  check_env_keys
  local before
  before="$(remote "docker ps -q | wc -l")"
  copy_files no
  echo "==> Kiểm compose config sau khi chép"
  remote "$(compose_cmd) config -q"
  echo "==> Áp lại project ${PROJECT}, profile: ${DEV_SERVER_PROFILES}"
  remote "$(compose_cmd) up -d --build"
  echo "==> Container của project ${PROJECT}"
  remote "docker ps --filter label=com.docker.compose.project=${PROJECT} --format '{{.Names}}\t{{.Status}}'"
  echo "==> Số container đang chạy trên máy chủ: trước ${before}, sau $(remote "docker ps -q | wc -l")"
  echo "    (chỉ project ${PROJECT} được áp lại; chênh lệch phải đến từ container ${PROJECT}-*)"
}

case "${1:-plan}" in
  plan) cmd_plan ;;
  apply) cmd_apply ;;
  *)
    echo "Cách dùng: $0 {plan|apply}" >&2
    exit 1
    ;;
esac

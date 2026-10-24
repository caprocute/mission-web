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
#   infra/dev/scripts/sync-server.sh plan    # so khoá .env, kiểm chỗ lưu dữ liệu và cổng, xem tệp sẽ chép; không đổi gì
#   infra/dev/scripts/sync-server.sh apply   # chỉ khi kiểm tra đạt: chép tệp rồi `docker compose up -d`
#
# Cần trên máy trạm: bash, ssh, docker compose (chỉ để dựng cấu hình, không cần daemon), python3.
# Kiểm tra trước khi áp (sync_preflight.py) dừng khi một dịch vụ đang chạy sẽ dựng lại trên chỗ lưu
# dữ liệu khác (ví dụ máy chủ đang gắn <thư mục>/data/postgres mà .env chưa đặt POSTGRES_DATA) hoặc
# khi cổng mới đang bị container khác giữ.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${INFRA_DIR}/../.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"
PROJECT="mission-dev"

# Biến người gọi đã đặt (trien-khai-lai.sh truyền DEV_SERVER_INFRA_DIR là thư mục repo mới trên máy chủ) thắng .env
# gốc repo: nếu .env đè lại thư mục cũ, compose chạy ở đó và đường dẫn ../../backend, ../../analytics trỏ ra ngoài repo.
_truyen_vao=()
for _v in DEV_SERVER_INFRA_DIR DEV_SERVER_PROFILES SYNC_SERVER_ENV; do
  [[ -n "${!_v:-}" ]] && _truyen_vao+=("${_v}=${!_v}")
done
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi
for _kv in ${_truyen_vao[@]+"${_truyen_vao[@]}"}; do
  export "${_kv}"
done

DEV_SERVER_HOST="${DEV_SERVER_HOST:-}"
DEV_SERVER_SSH_PORT="${DEV_SERVER_SSH_PORT:-2222}"
DEV_SERVER_USER="${DEV_SERVER_USER:-provision}"
DEV_SERVER_INFRA_DIR="${DEV_SERVER_INFRA_DIR:-}"
DEV_SERVER_PROFILES="${DEV_SERVER_PROFILES:-core security events}"
# .env của máy chủ dùng để so khoá và dựng cấu hình kiểm tra; mặc định <DEV_SERVER_INFRA_DIR>/.env.
# trien-khai-lai.sh đặt biến này về .env của thư mục cũ khi thư mục mới chưa có .env (chuyển bố cục).
SYNC_SERVER_ENV="${SYNC_SERVER_ENV:-${DEV_SERVER_INFRA_DIR}/.env}"
# Dịch vụ cũ trên máy chủ được thay bằng dịch vụ của repo (cũ=mới): máy chủ từng chạy Debezium dưới tên
# dịch vụ `connect` (container mission-dev-connect-1, cổng 35083). apply dừng và xoá container cũ ngay
# trước `up` để dịch vụ mới nhận cổng; sync_preflight.py chặn nếu container cũ còn dữ liệu có tên.
SYNC_RETIRE="${SYNC_RETIRE:-connect=debezium}"
export SYNC_RETIRE
# Thư mục ngoài infra/dev mà compose gắn bằng đường dẫn ../<tên> (giao diện Keycloak); chép sang thư mục
# anh em của DEV_SERVER_INFRA_DIR trên máy chủ.
SIBLING_DIRS=(keycloak-theme)

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
  with_pass ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "${TARGET}" "$@"
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
  remote_keys="$(remote "grep -E '^[A-Z0-9_]+=' $(printf '%q' "${SYNC_SERVER_ENV}") | cut -d= -f1 | sort -u" || true)"
  [[ -n "${remote_keys}" ]] || die "không đọc được ${SYNC_SERVER_ENV} trên máy chủ"
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

# Trạng thái container trên máy chủ cho sync_preflight.py: mounts và cổng, không có biến môi trường.
server_state() {
  with_pass ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "${TARGET}" 'bash -s' <<'REMOTE'
ids="$(docker ps -aq --filter label=com.docker.compose.project=mission-dev)"
if [ -n "${ids}" ]; then
  # shellcheck disable=SC2086
  docker inspect --format 'C|{{json .Name}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Running}}|{{json .Config.Volumes}}|{{json .Mounts}}' ${ids}
fi
docker ps --format 'P|{{.Label "com.docker.compose.project"}}|{{.Names}}|{{.Ports}}'
REMOTE
}

# Dựng cấu hình compose mới trên máy trạm: tệp của repo + khoá không bí mật của .env máy chủ
# (đường dẫn dữ liệu, cổng, host); khoá bí mật lấy giá trị giữ chỗ của .env.example.
preflight() {
  echo "==> Kiểm tra trước khi áp (chỗ lưu dữ liệu, tranh cổng)"
  if ! docker compose version >/dev/null 2>&1; then
    echo "  Máy trạm không có docker compose để dựng cấu hình mới; không kiểm được."
    return 1
  fi
  command -v python3 >/dev/null 2>&1 || { echo "  Máy trạm thiếu python3; không kiểm được."; return 1; }
  local work rc=0
  work="$(mktemp -d)"
  remote "grep -E '^(DEV_HOST|[A-Z0-9_]+_(DATA|PORT|BIND|HOST))=' $(printf '%q' "${SYNC_SERVER_ENV}")" \
    >"${work}/server-public.env" || true
  cut -d= -f1 "${work}/server-public.env" | sort -u >"${work}/keys"
  grep -E '^[A-Z0-9_]+=' "${INFRA_DIR}/.env.example" \
    | awk -F= 'NR == FNR { k[$1] = 1; next } !($1 in k)' "${work}/keys" - >"${work}/merged.env" || true
  cat "${work}/server-public.env" >>"${work}/merged.env"
  # shellcheck disable=SC2046
  if ! (cd "${INFRA_DIR}" && docker compose -p "${PROJECT}" --env-file "${work}/merged.env" \
    $(profile_args) config --format json >"${work}/compose.json"); then
    echo "  Không dựng được cấu hình compose mới."
    rc=1
  elif ! server_state >"${work}/state.txt"; then
    echo "  Không đọc được trạng thái container trên máy chủ."
    rc=1
  else
    python3 "${SCRIPT_DIR}/sync_preflight.py" "${work}/compose.json" "${work}/state.txt" \
      "${INFRA_DIR}" "${DEV_SERVER_INFRA_DIR}" || rc=1
  fi
  rm -rf "${work:?}"
  return "${rc}"
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
    with_pass rsync ${args[@]+"${args[@]}"} -e "ssh ${SSH_OPTS[*]-}" "${INFRA_DIR}/" "${TARGET}:${DEV_SERVER_INFRA_DIR}/"
    local d sib=(-rlptzv)
    [[ "${dry}" == "yes" ]] && sib+=(--dry-run)
    for d in ${SIBLING_DIRS[@]+"${SIBLING_DIRS[@]}"}; do
      echo "==> Chép infra/${d} sang $(dirname "${DEV_SERVER_INFRA_DIR}")/${d}"
      with_pass rsync ${sib[@]+"${sib[@]}"} -e "ssh ${SSH_OPTS[*]-}" \
        "${INFRA_DIR}/../${d}/" "${TARGET}:$(dirname "${DEV_SERVER_INFRA_DIR}")/${d}/"
    done
  else
    echo "==> Máy trạm hoặc máy chủ không có rsync: chép bằng tar (tệp đã xoá trong repo không bị xoá trên máy chủ)"
    local d parent
    parent="$(dirname "${DEV_SERVER_INFRA_DIR}")"
    if [[ "${dry}" == "yes" ]]; then
      (cd "${INFRA_DIR}" && git ls-files)
      for d in ${SIBLING_DIRS[@]+"${SIBLING_DIRS[@]}"}; do
        (cd "${INFRA_DIR}/.." && git ls-files -- "${d}")
      done
      return 0
    fi
    # git ls-files in đường dẫn tương đối với thư mục hiện tại; tar của macOS và GNU đều nhận --null -T.
    (cd "${INFRA_DIR}" && git ls-files -z | tar --null -T - -cf -) \
      | remote "mkdir -p $(printf '%q' "${DEV_SERVER_INFRA_DIR}") && tar -C $(printf '%q' "${DEV_SERVER_INFRA_DIR}") -xf -"
    for d in ${SIBLING_DIRS[@]+"${SIBLING_DIRS[@]}"}; do
      (cd "${INFRA_DIR}/.." && git ls-files -z -- "${d}" | tar --null -T - -cf -) \
        | remote "tar -C $(printf '%q' "${parent}") -xf -"
    done
  fi
  if [[ "${dry}" != "yes" ]]; then
    remote "printf '%s\n' $(printf '%q' "${commit}") > $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.repo-commit")"
  fi
}

# Dừng và xoá container của dịch vụ cũ được thay (SYNC_RETIRE), chỉ trong project mission-dev, lọc theo
# nhãn dịch vụ của compose; preflight đã chặn trường hợp container cũ còn dữ liệu có tên.
retire_services() {
  local item old
  for item in ${SYNC_RETIRE}; do
    old="${item%%=*}"
    echo "==> Dừng và xoá container của dịch vụ cũ '${old}' (thay bằng '${item#*=}')"
    remote "ids=\$(docker ps -aq --filter label=com.docker.compose.project=${PROJECT} --filter label=com.docker.compose.service=$(printf '%q' "${old}")); if [ -n \"\$ids\" ]; then docker rm -f \$ids; else echo '  Không còn container nào.'; fi"
  done
}

cmd_plan() {
  check_env_keys
  preflight || echo "  (plan) Lần áp sẽ bị chặn cho tới khi hết mục [DỪNG] ở trên."
  echo "==> Commit đang áp trên máy chủ: $(remote "cat $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.repo-commit") 2>/dev/null" || echo 'chưa ghi')"
  copy_files yes
  echo "==> Kiểm compose config trên máy chủ (bản hiện có, trước khi chép)"
  if remote "test -f $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.env")"; then
    remote "$(compose_cmd) config -q" && echo "  Hợp lệ."
  else
    echo "  Chưa có ${DEV_SERVER_INFRA_DIR}/.env (lần chuyển bố cục đầu tiên); bỏ qua."
  fi
}

cmd_apply() {
  check_env_keys
  preflight || die "kiểm tra trước khi áp không đạt; không chép, không đổi container nào"
  local before
  before="$(remote "docker ps -q | wc -l")"
  copy_files no
  echo "==> Kiểm compose config sau khi chép"
  remote "$(compose_cmd) config -q"
  echo "==> Kiểm gói chính sách OPA mà compose sẽ gắn"
  remote "cd $(printf '%q' "${DEV_SERVER_INFRA_DIR}") && python3 scripts/kiem-goi-opa.py .env"
  retire_services
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

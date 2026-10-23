#!/usr/bin/env bash
# =============================================================================
# Triển khai lại máy chủ dev dùng chung bằng một lệnh (INF-22). Chạy từ bản checkout trên máy trạm có SSH tới
# máy chủ (phiên trên máy Mac), không chạy trên máy chủ. Gom các bước đã có thành một quy trình:
#
#   (1) cập nhật `main` trên máy trạm (git pull --ff-only), kiểm `docker compose config` với từng profile;
#   (2) chép mã nguồn của commit hiện tại (git archive, chỉ tệp git theo dõi) vào DEV_SERVER_REPO_DIR trên máy chủ,
#       vì profile `backend` dựng ảnh từ ../../backend, ../../frontend… nên máy chủ phải có bố cục repo;
#   (3) sync-server.sh apply với thư mục hạ tầng <DEV_SERVER_REPO_DIR>/infra/dev: kiểm chỗ lưu dữ liệu và cổng,
#       dựng lại hạ tầng (OPA, kafka-init, giao diện Keycloak, minio…) theo các profile, xoá dịch vụ cũ `connect`;
#       Trước đó gen-env.sh --bo-sung nối vào .env máy chủ các khoá còn thiếu (bí mật sinh ngẫu nhiên, POSTGRES_*
#       lấy từ PG_SUPERUSER*, OPA timeout 2s cũ nâng lên 5s), không đè giá trị có sẵn, không in giá trị;
#   (4) create-topics.sh, them-client-provisioner.sh (bất biến, chạy lại được);
#   (5) dựng jar trên máy trạm (mặc định khi có JDK 21 và Node) rồi chép jar lên, hoặc để máy chủ tự dựng;
#   (6) deploy-backend.sh trên máy chủ cho các service chọn (mặc định APISIX, mission-core, web): dựng ảnh,
#       migration, bật, chờ healthcheck, nạp kỳ hiện hành;
#   (7) seed-org.sh, smoke-backend.sh, health.sh.
#
# Lần đầu, máy chủ chuyển từ thư mục hạ tầng riêng (DEV_SERVER_INFRA_DIR, ví dụ /home/provision/mission-infra) sang
# bố cục repo: .env được sao (cp, không đọc nội dung) sang thư mục mới; dữ liệu vẫn ở thư mục cũ qua các biến *_DATA
# (phải đặt đường dẫn tuyệt đối trước, xem infra/dev/README.md mục 2.1); thư mục cũ giữ nguyên để quay lui.
#
# Đọc từ `.env` ở gốc repo (không commit): DEV_SERVER_HOST, DEV_SERVER_SSH_PORT, DEV_SERVER_USER,
# DEV_SERVER_INFRA_DIR (thư mục hạ tầng cũ, nguồn .env), DEV_SERVER_REPO_DIR (mặc định /home/<user>/mission-web),
# DEV_SERVER_PROFILES (mặc định "core security events search"), TRIEN_KHAI_DICH_VU (mặc định "apisix mission-core
# web"), JAR_BUILD (may-tram | may-chu), WEB_BUILD (may-tram | may-chu, mặc định may-tram khi máy trạm có Node), MIN_FREE_GB (mặc định 12, dung lượng trống tối thiểu cho cache dựng ảnh).
#
# Cách dùng:
#   infra/dev/scripts/trien-khai-lai.sh plan                 # chỉ kiểm, không đổi gì trên máy chủ
#   infra/dev/scripts/trien-khai-lai.sh apply                # triển khai các service mặc định
#   infra/dev/scripts/trien-khai-lai.sh apply mission-core   # chỉ vài service của deploy-backend.sh
# Chỉ thao tác project compose `mission-dev`; không in giá trị bí mật.
# =============================================================================
set -euo pipefail

# Script dùng mapfile, mảng kết hợp (bash >= 4). macOS mặc định chỉ có bash 3.2: chạy lại bằng bash Homebrew nếu có.
if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    [[ -x "${b}" ]] && exec "${b}" "$0" "$@"
  done
  echo "Lỗi: cần bash >= 4 (đang là ${BASH_VERSION}); trên macOS cài bằng: brew install bash" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${INFRA_DIR}/../.." && pwd)"
PROJECT="mission-dev"

if [[ -f "${REPO_ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${REPO_ROOT}/.env"
  set +a
fi

DEV_SERVER_HOST="${DEV_SERVER_HOST:-}"
DEV_SERVER_SSH_PORT="${DEV_SERVER_SSH_PORT:-2222}"
DEV_SERVER_USER="${DEV_SERVER_USER:-provision}"
DEV_SERVER_INFRA_DIR="${DEV_SERVER_INFRA_DIR:-}"
DEV_SERVER_REPO_DIR="${DEV_SERVER_REPO_DIR:-/home/${DEV_SERVER_USER}/mission-web}"
DEV_SERVER_PROFILES="${DEV_SERVER_PROFILES:-core security events search}"
TRIEN_KHAI_DICH_VU="${TRIEN_KHAI_DICH_VU:-apisix mission-core web}"
MIN_FREE_GB="${MIN_FREE_GB:-12}"
NEW_INFRA_DIR="${DEV_SERVER_REPO_DIR}/infra/dev"
# Mọi profile của compose. Profile khác `core` phụ thuộc dịch vụ của core (ví dụ debezium cần postgres), nên
# kiểm từng profile kèm core, rồi cả nhóm.
ALL_PROFILES=(core security events search obs analytics backend all)

die() {
  echo "Lỗi: $*" >&2
  exit 1
}

[[ -n "${DEV_SERVER_HOST}" ]] || die "chưa cấu hình DEV_SERVER_HOST trong .env"
case "${DEV_SERVER_REPO_DIR}" in
  /*) ;;
  *) die "DEV_SERVER_REPO_DIR phải là đường dẫn tuyệt đối" ;;
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

# Chạy một script của infra/dev/scripts trên máy chủ, trong bản repo mới.
remote_script() {
  local env_prefix="$1"
  shift
  remote "cd $(printf '%q' "${NEW_INFRA_DIR}") && ${env_prefix} ./scripts/$*"
}

read -r -a SERVICES <<<"${TRIEN_KHAI_DICH_VU}"
if [[ $# -gt 1 ]]; then
  SERVICES=("${@:2}")
fi

# .env nguồn cho lần kiểm khi thư mục mới chưa có .env (lần chuyển bố cục đầu tiên).
server_env_source() {
  if remote "test -f $(printf '%q' "${NEW_INFRA_DIR}/.env")"; then
    echo "${NEW_INFRA_DIR}/.env"
  elif [[ -n "${DEV_SERVER_INFRA_DIR}" ]] && remote "test -f $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.env")"; then
    echo "${DEV_SERVER_INFRA_DIR}/.env"
  else
    die "không thấy .env của máy chủ ở ${NEW_INFRA_DIR} hay ${DEV_SERVER_INFRA_DIR:-<DEV_SERVER_INFRA_DIR chưa đặt>}"
  fi
}

sync_server() {
  local env_src
  env_src="$(server_env_source)"
  DEV_SERVER_INFRA_DIR="${NEW_INFRA_DIR}" DEV_SERVER_PROFILES="${DEV_SERVER_PROFILES}" \
    SYNC_SERVER_ENV="${env_src}" "${SCRIPT_DIR}/sync-server.sh" "$@"
}

# --- kiểm trên máy trạm ---------------------------------------------------------
check_git() {
  echo "==> Bản checkout trên máy trạm"
  local branch
  branch="$(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD)"
  [[ "${branch}" == "main" ]] || echo "  Cảnh báo: đang ở nhánh ${branch}, không phải main."
  if ! git -C "${REPO_ROOT}" diff --quiet || ! git -C "${REPO_ROOT}" diff --cached --quiet; then
    die "có thay đổi chưa commit; git archive chỉ chép commit HEAD"
  fi
  git -C "${REPO_ROOT}" fetch -q origin "${branch}" || echo "  Không fetch được origin/${branch}."
  echo "  HEAD $(git -C "${REPO_ROOT}" rev-parse --short HEAD), sau origin/${branch} $(git -C "${REPO_ROOT}" rev-list --count "HEAD..origin/${branch}" 2>/dev/null || echo '?') commit"
}

check_compose_profiles() {
  echo "==> docker compose config với từng profile kèm core (giá trị giữ chỗ của .env.example)"
  if ! docker compose version >/dev/null 2>&1; then
    echo "  Máy trạm không có docker compose; bỏ qua."
    return 0
  fi
  local p args=() bad=0
  for p in ${ALL_PROFILES[@]+"${ALL_PROFILES[@]}"}; do
    if (cd "${INFRA_DIR}" && docker compose -p "${PROJECT}" --env-file .env.example --profile core --profile "${p}" config -q 2>/dev/null); then
      printf '  %-10s hợp lệ\n' "${p}"
    else
      printf '  %-10s LỖI\n' "${p}"
      bad=1
    fi
    args+=(--profile "${p}")
  done
  if (cd "${INFRA_DIR}" && docker compose -p "${PROJECT}" --env-file .env.example ${args[@]+"${args[@]}"} config -q 2>/dev/null); then
    echo "  mọi profile cùng lúc: hợp lệ"
  else
    echo "  mọi profile cùng lúc: LỖI"
    bad=1
  fi
  [[ ${bad} -eq 0 ]] || die "compose config lỗi; chạy lại lệnh trên không có 2>/dev/null để xem chi tiết"
  local s known
  known="$(cd "${INFRA_DIR}" && docker compose -p "${PROJECT}" --env-file .env.example ${args[@]+"${args[@]}"} config --services 2>/dev/null)"
  local keep=()
  for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
    if grep -qx "${s}" <<<"${known}"; then
      keep+=("${s}")
    else
      echo "  Cảnh báo: compose chưa có dịch vụ '${s}' ở commit này; bỏ khỏi lần triển khai."
    fi
  done
  SERVICES=(${keep[@]+"${keep[@]}"})
}

# Dựng jar trên máy trạm khi có JDK 21 và Node (gộp hợp đồng OpenAPI); jar độc lập kiến trúc nên ảnh vẫn dựng
# trên máy chủ (amd64) từ jar này.
jar_mode() {
  if [[ -n "${JAR_BUILD:-}" ]]; then
    echo "${JAR_BUILD}"
  elif command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21' && command -v node >/dev/null 2>&1; then
    echo "may-tram"
  else
    echo "may-chu"
  fi
}

java_modules() {
  IN_MO_DUN=1 "${SCRIPT_DIR}/deploy-backend.sh" ${SERVICES[@]+"${SERVICES[@]}"} 2>/dev/null || true
}

# --- kiểm trên máy chủ ----------------------------------------------------------
check_server() {
  echo "==> Máy chủ"
  # shellcheck disable=SC2016
  remote 'bash -s' "${MIN_FREE_GB}" "$(jar_mode)" <<'REMOTE'
min="$1"; mode="$2"
docker compose version >/dev/null 2>&1 && echo "  docker compose: có" || echo "  docker compose: THIẾU"
if [ "${mode}" = "may-chu" ]; then
  command -v java >/dev/null 2>&1 && echo "  java: $(java -version 2>&1 | head -1)" || echo "  java: THIẾU (cần JDK 21 vì JAR_BUILD=may-chu)"
  command -v node >/dev/null 2>&1 && echo "  node: $(node --version)" || echo "  node: THIẾU (gộp hợp đồng OpenAPI khi dựng jar)"
fi
root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null | tail -1)"
[ -d "${root}" ] || root=/
free_gb="$(df -Pk "${root}" 2>/dev/null | awk 'NR == 2 { printf "%d", $4 / 1048576 }')"
echo "  Trống ở ${root}: ${free_gb:-?} GB (cần >= ${min} GB cho cache dựng ảnh)"
[ -n "${free_gb}" ] && [ "${free_gb}" -lt "${min}" ] && echo "  CẢNH BÁO: thiếu dung lượng; dọn ảnh và cache dựng cũ của project trước (README mục 2.1)."
exit 0
REMOTE
  local env_src
  env_src="$(server_env_source)"
  echo "  Thư mục repo trên máy chủ: ${DEV_SERVER_REPO_DIR}; .env dùng để kiểm: ${env_src}"
  echo "==> Khoá .env máy chủ sẽ được gen-env.sh --bo-sung thêm khi apply (chỉ tên, không giá trị)"
  # Chạy bản gen-env.sh và .env.example của máy trạm trong thư mục tạm trên máy chủ, chỉ đọc .env.
  tar -C "${INFRA_DIR}" -cf - .env.example scripts/gen-env.sh |
    remote "t=\$(mktemp -d) && tar -C \"\$t\" -xf - && GEN_ENV_FILE=$(printf '%q' "${env_src}") GEN_ENV_EXAMPLE=\"\$t/.env.example\" bash \"\$t/scripts/gen-env.sh\" --kiem; rc=\$?; rm -rf \"\$t\"; exit \$rc"
}

# Ảnh kéo từ registry của các profile sẽ áp: có sẵn trên máy chủ, hoặc registry còn phục vụ. Ảnh dựng từ repo
# (tiền tố mission) bỏ qua. Một ảnh không kéo được làm hỏng cả đợt `docker compose up` (ví dụ MinIO đã gỡ ảnh
# khỏi Docker Hub), nên chặn từ bước kiểm.
check_images() {
  echo "==> Ảnh của profile ${DEV_SERVER_PROFILES} trên máy chủ"
  if ! docker compose version >/dev/null 2>&1; then
    echo "  Máy trạm không có docker compose; bỏ qua."
    return 0
  fi
  local p args=() images
  for p in ${DEV_SERVER_PROFILES}; do args+=(--profile "${p}"); done
  images="$(cd "${INFRA_DIR}" && docker compose -p "${PROJECT}" --env-file .env.example ${args[@]+"${args[@]}"} config --images 2>/dev/null |
    grep -v '^mission' | sort -u)"
  [[ -n "${images}" ]] || return 0
  # shellcheck disable=SC2016
  remote "bash -s -- $(tr '\n' ' ' <<<"${images}")" <<'REMOTE' || die "có ảnh không kéo được; sửa tag trong docker-compose.yml trước khi apply"
bad=0
docker manifest inspect --help >/dev/null 2>&1 || { echo "  docker trên máy chủ không có 'manifest inspect'; chỉ kiểm ảnh có sẵn."; nm=1; }
for img in "$@"; do
  if docker image inspect "${img}" >/dev/null 2>&1; then
    echo "  có sẵn        ${img}"
  elif [ -n "${nm:-}" ]; then
    echo "  chưa có, không kiểm được ${img}"
  elif timeout 60 docker manifest inspect "${img}" >/dev/null 2>&1; then
    echo "  kéo được      ${img}"
  else
    echo "  [DỪNG] không kéo được ${img}"
    bad=1
  fi
done
exit "${bad}"
REMOTE
}

# --- các bước áp ------------------------------------------------------------------
copy_source() {
  local commit
  commit="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
  echo "==> Chép mã nguồn commit ${commit} vào ${DEV_SERVER_REPO_DIR} (git archive; .env, data/ trên máy chủ không bị đụng)"
  git -C "${REPO_ROOT}" archive --format=tar HEAD |
    remote "mkdir -p $(printf '%q' "${DEV_SERVER_REPO_DIR}") && tar -C $(printf '%q' "${DEV_SERVER_REPO_DIR}") -xf - && chmod +x $(printf '%q' "${NEW_INFRA_DIR}")/scripts/*.sh"
}

ensure_env() {
  if remote "test -f $(printf '%q' "${NEW_INFRA_DIR}/.env")"; then
    return 0
  fi
  [[ -n "${DEV_SERVER_INFRA_DIR}" ]] || die "thư mục mới chưa có .env và DEV_SERVER_INFRA_DIR chưa đặt"
  echo "==> Sao .env từ ${DEV_SERVER_INFRA_DIR} sang ${NEW_INFRA_DIR} (quyền 600, không đọc nội dung)"
  remote "install -m 600 $(printf '%q' "${DEV_SERVER_INFRA_DIR}/.env") $(printf '%q' "${NEW_INFRA_DIR}/.env")"
}

build_and_copy_jars() {
  local modules m
  mapfile -t modules < <(java_modules)
  [[ ${#modules[@]} -gt 0 ]] || return 0
  echo "==> Dựng jar trên máy trạm: ${modules[*]-}"
  (cd "${REPO_ROOT}/backend" && ./mvnw -B -q -pl "$(IFS=,; echo "${modules[*]-}")" -am package -DskipTests)
  for m in ${modules[@]+"${modules[@]}"}; do
    echo "  chép backend/${m}/target/*.jar"
    (cd "${REPO_ROOT}/backend/${m}" && tar -cf - target/*.jar) |
      remote "mkdir -p $(printf '%q' "${DEV_SERVER_REPO_DIR}/backend/${m}") && rm -f $(printf '%q' "${DEV_SERVER_REPO_DIR}/backend/${m}")/target/*.jar && tar -C $(printf '%q' "${DEV_SERVER_REPO_DIR}/backend/${m}") -xf -"
  done
}

# Bản web dựng trên máy trạm khi có Node: esbuild của `ng build` treo trên máy chủ CentOS 7 tải cao. Ảnh web khi
# đó dùng deploy/web/Dockerfile.co-san, chỉ đóng gói nginx từ frontend/dist-web/.
web_mode() {
  if [[ -n "${WEB_BUILD:-}" ]]; then
    echo "${WEB_BUILD}"
  elif command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    echo "may-tram"
  else
    echo "may-chu"
  fi
}

build_and_copy_web() {
  local out="${REPO_ROOT}/frontend/dist-web"
  echo "==> Dựng web trên máy trạm (npm ci, build:all, assemble)"
  rm -rf "${out}"
  (cd "${REPO_ROOT}/frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build:all &&
    node tools/remotes/workspace.mjs assemble "${out}/html" "${out}/remotes.tsv" &&
    cp -r dist/fe-embed/browser "${out}/html/embed")
  echo "  chép frontend/dist-web lên máy chủ"
  (cd "${REPO_ROOT}/frontend" && tar -cf - dist-web) |
    remote "rm -rf $(printf '%q' "${DEV_SERVER_REPO_DIR}/frontend/dist-web") && tar -C $(printf '%q' "${DEV_SERVER_REPO_DIR}/frontend") -xf -"
}

cmd_plan() {
  check_git
  check_compose_profiles
  echo "  Service sẽ triển khai: ${SERVICES[*]:-không có}; dựng jar ở: $(jar_mode); dựng web ở: $(web_mode)"
  check_server
  check_images
  sync_server plan
}

cmd_apply() {
  check_git
  echo "==> Cập nhật nhánh (git pull --ff-only)"
  git -C "${REPO_ROOT}" pull -q --ff-only || die "không fast-forward được; xử lý nhánh trên máy trạm trước"
  check_compose_profiles
  check_server
  check_images
  copy_source
  ensure_env
  echo "==> Bổ sung khoá còn thiếu vào .env máy chủ (bí mật sinh ngẫu nhiên, không đè giá trị có sẵn, không in)"
  remote_script "" gen-env.sh --bo-sung
  sync_server apply
  echo "==> Topic Kafka và client provisioner của Keycloak"
  remote_script "" create-topics.sh
  remote_script "" them-client-provisioner.sh
  [[ ${#SERVICES[@]} -gt 0 ]] || { echo "Không có service backend nào để triển khai."; return 0; }
  local prefix=""
  if [[ "$(jar_mode)" == "may-tram" ]]; then
    build_and_copy_jars
    prefix="SKIP_JARS=1"
  fi
  if [[ " ${SERVICES[*]-} " == *" web "* && "$(web_mode)" == "may-tram" ]]; then
    build_and_copy_web
    prefix+=" WEB_DOCKERFILE=deploy/web/Dockerfile.co-san"
  fi
  echo "==> deploy-backend.sh ${SERVICES[*]-} trên máy chủ"
  remote_script "${prefix}" deploy-backend.sh ${SERVICES[@]+"${SERVICES[@]}"}
  echo "==> Dữ liệu thử và kiểm thử khói"
  remote_script "" seed-org.sh || echo "  Cảnh báo: seed-org.sh lỗi (cần schema org của org-service); chạy lại sau khi org-service đã migrate." >&2
  remote_script "" smoke-backend.sh || echo "  Cảnh báo: kiểm thử khói có mục hỏng, xem chi tiết ở trên." >&2
  remote_script "" health.sh
}

case "${1:-plan}" in
  plan) cmd_plan ;;
  apply) cmd_apply ;;
  *)
    echo "Cách dùng: $0 {plan|apply} [service...]" >&2
    exit 1
    ;;
esac

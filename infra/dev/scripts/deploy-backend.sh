#!/usr/bin/env bash
# =============================================================================
# Dựng và bật profile `backend` của hạ tầng dev (INF-22): APISIX dev cổng 35000 cùng mission-core và các
# service đã có khung, cổng 351xx (bảng ở infra/dev/README.md), và web (SVC-17) phục vụ sau APISIX.
#
# Chạy trên máy có Docker và bản checkout đầy đủ của repo (cần backend/, services-go/, contracts/), ví dụ máy
# chủ dev có bản clone repo, hoặc máy trạm khi thử cục bộ. Dựng jar Java cần JDK 21 và Node (gộp hợp đồng
# OpenAPI); service Go dựng hoàn toàn trong Docker.
#
# Các bước: (1) `mvnw package` các mô-đun Java; (2) `docker compose build`; (3) kiểm hạ tầng core, security,
# events đang chạy (không bật hay dựng lại); (4) migration bằng tài khoản mig_<schema>; (5) `docker compose up -d` các service
# backend; (6) chờ healthcheck; (7) seed khi có mission-core (lược đồ Avro, tổ chức và org-republish, kỳ hiện
# hành, phân vùng theo kỳ, nhiệm vụ mẫu; bỏ bằng SKIP_SEED=1) rồi gọi health.sh. Chỉ thao tác project compose `mission-dev`: không dùng
# --remove-orphans, không prune, không đụng container khác trên máy.
#
# Cách dùng:
#   infra/dev/scripts/deploy-backend.sh                       # toàn bộ
#   infra/dev/scripts/deploy-backend.sh mission-core apisix   # chỉ vài service
#   SKIP_JARS=1 SKIP_MIGRATE=1 SKIP_SEED=1 infra/dev/scripts/deploy-backend.sh
#   HA_TANG_KHONG_BAT_BUOC=apicurio infra/dev/scripts/deploy-backend.sh mission-core   # apicurio lỗi chỉ cảnh báo
#   SKIP_JARS=1 SKIP_BUILD=1 infra/dev/scripts/deploy-backend.sh   # dùng lại ảnh đã dựng, chỉ migration và bật service
# Mật khẩu đọc từ infra/dev/.env (không commit); script không in giá trị bí mật.
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
ENV_FILE="${INFRA_DIR}/.env"
PROJECT="mission-dev"

die() {
  echo "Lỗi: $*" >&2
  exit 1
}

if [[ "${IN_MO_DUN:-0}" != "1" ]]; then
  [[ -f "${ENV_FILE}" ]] || die "thiếu ${ENV_FILE} (sao chép từ .env.example và điền mật khẩu)"
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  [[ -n "${DB_ROLE_PASSWORD_DEFAULT:-}" ]] || die "chưa đặt DB_ROLE_PASSWORD_DEFAULT trong .env"
fi
[[ -d "${REPO_ROOT}/backend" && -d "${REPO_ROOT}/services-go" ]] || die "cần bản checkout đầy đủ của repo"

# Service Java: tên dịch vụ compose → mô-đun Maven.
declare -A JAVA_MODULES=(
  [mission-core]=mission-core
  [org-service]=services/org-service
  [workflow-service]=services/workflow-service
  [scoring-service]=services/scoring-service
  [document-service]=services/document-service
  [evidence-service]=services/evidence-service
  [esign-service]=services/esign-service
  [integration-gateway]=services/integration-gateway
  [analytics-api]=services/analytics-api
  [search]=services/search
)
GO_SERVICES=(tenant-registry ledger-service notification-service)
# web: ảnh SVC-17 (deploy/web/Dockerfile, npm build bên trong ảnh), APISIX phục vụ qua tuyến `web`.
# cel-js-runner: bộ chạy cel-js để mission-core đối chiếu hai engine khi chạy ca vàng Policy Pack (npm build trong ảnh).
ALL_SERVICES=(apisix "${!JAVA_MODULES[@]}" ${GO_SERVICES[@]+"${GO_SERVICES[@]}"} cel-js-runner web)

if [[ $# -gt 0 ]]; then
  SERVICES=("$@")
  for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
    [[ " ${ALL_SERVICES[*]-} " == *" ${s} "* ]] || die "service lạ: ${s}"
  done
else
  SERVICES=(${ALL_SERVICES[@]+"${ALL_SERVICES[@]}"})
fi
selected() { [[ " ${SERVICES[*]-} " == *" $1 "* ]]; }

# IN_MO_DUN=1: chỉ in mô-đun Maven của các service đã chọn (trien-khai-lai.sh dựng jar trên máy trạm) rồi thoát.
if [[ "${IN_MO_DUN:-0}" == "1" ]]; then
  for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
    [[ -n "${JAVA_MODULES[$s]:-}" ]] && echo "${JAVA_MODULES[$s]}"
  done
  exit 0
fi

compose() {
  docker compose -p "${PROJECT}" --project-directory "${INFRA_DIR}" -f "${INFRA_DIR}/docker-compose.yml" \
    --env-file "${ENV_FILE}" --profile core --profile security --profile events --profile search \
    --profile backend "$@"
}

echo "==> Container trước khi áp: $(docker ps -q | wc -l) đang chạy, $(docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" | wc -l) thuộc ${PROJECT}"

# --- (1) jar Java ------------------------------------------------------------
modules=()
for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
  [[ -n "${JAVA_MODULES[$s]:-}" ]] && modules+=("${JAVA_MODULES[$s]}")
done
if [[ ${#modules[@]} -gt 0 && "${SKIP_JARS:-0}" != "1" ]]; then
  echo "==> Dựng jar: ${modules[*]-}"
  (cd "${REPO_ROOT}/backend" && ./mvnw -B -q -pl "$(IFS=,; echo "${modules[*]-}")" -am package -DskipTests)
fi

# --- (2) ảnh -----------------------------------------------------------------
build=()
for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
  [[ "${s}" != "apisix" ]] && build+=("${s}")
done
if [[ ${#build[@]} -gt 0 && "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> Dựng ảnh: ${build[*]-}"
  # Nhãn commit trên ảnh để kiem-tuyen.py và docker inspect biết container chạy mã nào
  revision="$(git -C "${SCRIPT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  compose build --build-arg "GIT_REVISION=${revision}" ${build[@]+"${build[@]}"}
fi
selected apisix && compose pull apisix

# --- (3) hạ tầng phụ thuộc ------------------------------------------------------
# Chỉ kiểm, không bật hay dựng lại: hạ tầng do `docker compose up` theo profile (README mục 3) hoặc
# sync-server.sh quản lý, vì dựng lại Postgres, Kafka khi .env thiếu *_DATA sẽ gắn sang volume trống.
echo "==> Kiểm hạ tầng core, security, events"
missing=0
for c in postgres pgbouncer valkey keycloak opa kafka apicurio; do
  state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "mission-dev-${c}" 2>/dev/null || true)"
  printf "  %-10s %s\n" "${c}" "${state:-không chạy}"
  [[ "${state}" == "healthy" || "${state}" == "running" ]] && continue
  # Apicurio chỉ là registry lược đồ sự kiện: các service hiện chỉ đọc địa chỉ, không cần nó để chạy HTTP.
  if [[ " ${HA_TANG_KHONG_BAT_BUOC:-} " == *" ${c} "* ]]; then
    echo "    Cảnh báo: ${c} chưa sẵn sàng, bỏ qua theo HA_TANG_KHONG_BAT_BUOC."
  else
    missing=1
  fi
done
[[ ${missing} -eq 0 ]] || die "hạ tầng chưa sẵn sàng; bật profile core, security, events trước (README mục 3)"

# --- (4) migration ---------------------------------------------------------------
# Schema của mission-core (keyring trước, theo backend/libs/vt-db-migration/README.md) và các service có
# vt-db-migration: MigrationRunner trong chính ảnh service, tài khoản mig_<schema>, nối thẳng PostgreSQL.
declare -A RUNNER_SCHEMAS=(
  [mission-core]="keyring,modulith,config,policy,okr,task,product,appraisal,dossier,oversight,org_replica"
  [scoring-service]=scoring
  [evidence-service]=evidence
  [integration-gateway]=integ
)
run_migration_runner() {
  local svc="$1" schemas="$2" s
  local env_args=()
  IFS=, read -r -a list <<<"${schemas}"
  for s in ${list[@]+"${list[@]}"}; do
    # Biến MIG_<SCHEMA>_PASSWORD truyền theo tên (compose run -e TÊN), giá trị không lên dòng lệnh.
    export "MIG_${s^^}_PASSWORD=${DB_ROLE_PASSWORD_DEFAULT}"
    env_args+=(-e "MIG_${s^^}_PASSWORD")
  done
  echo "  ${svc}: ${schemas}"
  compose run --rm --no-deps -T ${env_args[@]+"${env_args[@]}"} --entrypoint java "${svc}" \
    -cp '/app/BOOT-INF/classes:/app/BOOT-INF/lib/*' vn.viettel.okrkpi.platform.db.migration.MigrationRunner \
    "--db=jdbc:postgresql://postgres:5432/${POSTGRES_DB:-mission}" "--schemas=${schemas}"
}

# Service Go: migration SQL thuần của service (services-go/<svc>/db/migration/<schema>) có tạo vai trò và đặt
# tham số vai trò, nên chạy bằng tài khoản quản trị PostgreSQL như chế độ AUTO_MIGRATE dev của chính service:
# chỉ áp khi bảng mốc chưa có (lần đầu), lần sau báo bỏ qua.
run_go_migration() {
  local db="$1" dir="$2" sentinel="$3" exists f
  exists="$(docker exec -e PGPASSWORD="${POSTGRES_PASSWORD:-}" mission-dev-postgres psql -h 127.0.0.1 -qtA \
    -U "${POSTGRES_USER:-mission_admin}" -d "${db}" -c "select to_regclass('${sentinel}') is not null")"
  if [[ "${exists}" == "t" ]]; then
    echo "  ${dir##*/}: đã có ${sentinel}, bỏ qua"
    return 0
  fi
  echo "  ${dir##*/}: áp $(find "${dir}" -maxdepth 1 -name 'V*.sql' | wc -l) tệp vào CSDL ${db}"
  while IFS= read -r f; do
    docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD:-}" mission-dev-postgres psql -h 127.0.0.1 -q \
      -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-mission_admin}" -d "${db}" -1 <"${f}" >/dev/null
  done < <(find "${dir}" -maxdepth 1 -name 'V*.sql' | sort -V)
}

if [[ "${SKIP_MIGRATE:-0}" != "1" ]]; then
  echo "==> Migration"
  for s in "${!RUNNER_SCHEMAS[@]}"; do
    selected "${s}" && run_migration_runner "${s}" "${RUNNER_SCHEMAS[$s]}"
  done
  if selected org-service; then
    # Hồ sơ Spring `migrate` của org-service: Flyway schema org bằng mig_org, không mở cổng web, xong thì thoát.
    echo "  org-service: org (hồ sơ migrate)"
    compose run --rm --no-deps -T -e SPRING_PROFILES_ACTIVE=migrate \
      -e ORG_DB_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB:-mission}?currentSchema=org" org-service
  fi
  # workflow-service (flowable), document-service (docgen) và esign-service (esign) tự chạy Flyway lúc khởi động
  # bằng mig_<schema> (docker-compose.yml): migration của hai schema sau tự tạo outbox, inbox, idempotency nên
  # không chạy chung với phần V0_x của MigrationRunner (trùng bảng outbox_event).
  selected tenant-registry && run_go_migration "${POSTGRES_DB:-mission}" \
    "${REPO_ROOT}/services-go/tenant-registry/db/migration/registry" registry.tenant
  selected ledger-service && run_go_migration ledger \
    "${REPO_ROOT}/services-go/ledger/db/migration/ledger" ledger.ledger_entry
  selected notification-service && run_go_migration "${POSTGRES_DB:-mission}" \
    "${REPO_ROOT}/services-go/notification/db/migration/notify" notify.notification
fi

# --- (5) bật service -------------------------------------------------------------
echo "==> Bật service: ${SERVICES[*]-}"
compose up -d --no-deps --no-build ${SERVICES[@]+"${SERVICES[@]}"}

# Client mission-web của Keycloak phải nhận gốc web (WEB_ORIGIN) làm redirect URI; realm đã nhập từ trước thì
# không tự cập nhật, nên thêm bằng kcadm (chạy lại không trùng).
selected web && "${SCRIPT_DIR}/cap-nhat-goc-web.sh"

# --- (6) chờ healthcheck ----------------------------------------------------------
echo "==> Chờ healthcheck (tối đa 5 phút)"
deadline=$((SECONDS + 300))
for s in ${SERVICES[@]+"${SERVICES[@]}"}; do
  [[ "${s}" == "apisix" ]] && continue
  name="$(compose ps -q "${s}")"
  state=""
  while [[ ${SECONDS} -lt ${deadline} ]]; do
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${name}" 2>/dev/null || true)"
    [[ "${state}" == "healthy" ]] && break
    sleep 5
  done
  printf "  %-22s %s\n" "${s}" "${state:-không chạy}"
done

# --- (7) dữ liệu nền qua API ---------------------------------------------------------
# Tenant thử cần một kỳ đang mở bao trùm hôm nay, nếu không cây mục tiêu trả 404 (CORE-01-04). Đi qua APISIX
# bằng tài khoản quản trị đơn vị thử; lỗi ở bước này chỉ cảnh báo, không làm hỏng lần triển khai.
# Thứ tự: lược đồ Avro vào Apicurio trước mọi thao tác ghi có sự kiện; tổ chức sang org_replica trước khi giao
# việc; phân vùng theo kỳ trước khi ghi dữ liệu của kỳ (README mục 3.2). Mỗi bước chạy lại an toàn.
canh_bao() { echo "  Cảnh báo: $1 chưa xong; chạy lại $2 khi APISIX, Keycloak và Apicurio sẵn sàng" >&2; }
if selected mission-core && [[ "${SKIP_SEED:-0}" != "1" ]]; then
  python3 -I "${SCRIPT_DIR}/dang-ky-luoc-do.py" >/dev/null || canh_bao "đăng ký lược đồ sự kiện" "dang-ky-luoc-do.py"
  "${SCRIPT_DIR}/seed-org.sh" || canh_bao "seed tổ chức" "seed-org.sh"
  if selected org-service || docker ps -q --filter name=mission-dev-org-service | grep -q .; then
    "${SCRIPT_DIR}/dong-bo-to-chuc.sh" || canh_bao "đồng bộ tổ chức sang mission-core" "dong-bo-to-chuc.sh"
  fi
  "${SCRIPT_DIR}/seed-ky-hien-hanh.sh" || canh_bao "nạp kỳ hiện hành" "seed-ky-hien-hanh.sh"
  "${SCRIPT_DIR}/tao-phan-vung-ky.sh" || canh_bao "tạo phân vùng theo kỳ" "tao-phan-vung-ky.sh"
  # Keycloak từ chối mã OTP đã dùng trong 30 giây của cùng tài khoản; bước sau đăng nhập lại quantri.
  sleep 31
  python3 -I "${SCRIPT_DIR}/seed-ky-nhiem-vu.py" || canh_bao "nhiệm vụ mẫu" "seed-ky-nhiem-vu.py"
fi

echo "==> Container sau khi áp: $(docker ps -q | wc -l) đang chạy, $(docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" | wc -l) thuộc ${PROJECT}"
"${SCRIPT_DIR}/health.sh"

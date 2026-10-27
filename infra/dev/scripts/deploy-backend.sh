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
# backend; (6) chờ healthcheck, đăng ký lược đồ Avro (lỗi thì dừng); (7) seed khi có mission-core (tổ chức và org-republish, kỳ hiện
# hành, phân vùng theo kỳ, nhiệm vụ mẫu, OKR mẫu đã duyệt; bỏ bằng SKIP_SEED=1) rồi gọi health.sh. Chỉ thao tác project compose `mission-dev`: không dùng
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

# Gói chính sách OPA của chính bản checkout này (docker-compose.yml, OPA_POLICY_DIR): đường tuyệt đối để không phụ
# thuộc thư mục chạy compose.
export OPA_POLICY_DIR="${OPA_POLICY_DIR:-${REPO_ROOT}/backend/opa/okrkpi}"

compose() {
  docker compose -p "${PROJECT}" --project-directory "${INFRA_DIR}" -f "${INFRA_DIR}/docker-compose.yml" \
    --env-file "${ENV_FILE}" --profile core --profile security --profile events --profile search \
    --profile backend "$@"
}

# Secret openbao_token của compose là tệp (docker-compose.yml): ghi từ OPENBAO_DEV_ROOT_TOKEN, thư mục 700 để chỉ chủ
# máy đọc được; tệp 644 để người dùng 10001 trong container đọc qua điểm gắn. Không in giá trị.
if [[ -z "${OPENBAO_TOKEN_FILE:-}" ]]; then
  if [[ -n "${OPENBAO_DEV_ROOT_TOKEN:-}" ]]; then
    install -d -m 700 "${INFRA_DIR}/secrets"
    (umask 022 && printf '%s' "${OPENBAO_DEV_ROOT_TOKEN}" >"${INFRA_DIR}/secrets/openbao_token.tmp")
    chmod 644 "${INFRA_DIR}/secrets/openbao_token.tmp"
    mv "${INFRA_DIR}/secrets/openbao_token.tmp" "${INFRA_DIR}/secrets/openbao_token"
  elif [[ ! -f "${INFRA_DIR}/secrets/openbao_token" ]]; then
    echo "  Cảnh báo: chưa đặt OPENBAO_DEV_ROOT_TOKEN; org-service, integration-gateway không mã hoá được trường C2." >&2
    install -d -m 700 "${INFRA_DIR}/secrets"
    : >"${INFRA_DIR}/secrets/openbao_token"
  fi
fi

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
# OPA chạy với thư mục chính sách rỗng vẫn "running" và mọi API trả 403 (thư mục hạ tầng chép ngoài repo làm
# ../../backend/opa/okrkpi trỏ vào chỗ không có, Docker tự tạo thư mục rỗng): kiểm nguồn gắn và quyết định thật.
if [[ " ${HA_TANG_KHONG_BAT_BUOC:-} " != *" opa "* ]]; then
  opa_src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/policies/okrkpi"}}{{.Source}}{{end}}{{end}}' mission-dev-opa)"
  if [[ -z "${opa_src}" ]] || ! compgen -G "${opa_src}/*.rego" >/dev/null; then
    die "OPA gắn '${opa_src:-?}' vào /policies/okrkpi nhưng thư mục không có tệp .rego; đặt OPA_POLICY_DIR=${REPO_ROOT}/backend/opa/okrkpi trong .env rồi dựng lại OPA (compose up -d --force-recreate opa)"
  fi
  curl -fsS -X POST -H 'Content-Type: application/json' -d '{"input":{}}' \
    "http://127.0.0.1:${OPA_PORT:-35181}/v1/data/okrkpi/authz/decision" 2>/dev/null | grep -q '"result"' ||
    die "OPA chưa nạp gói okrkpi từ ${opa_src} (không có data.okrkpi.authz.decision); xem docker logs mission-dev-opa"
  echo "  opa        gói okrkpi từ ${opa_src}"
fi

# --- (4) migration ---------------------------------------------------------------
# Schema của mission-core (keyring trước, theo backend/libs/vt-db-migration/README.md) và các service có
# vt-db-migration: MigrationRunner trong chính ảnh service, tài khoản mig_<schema>, nối thẳng PostgreSQL.
declare -A RUNNER_SCHEMAS=(
  [mission-core]="keyring,modulith,config,policy,okr,task,product,appraisal,dossier,oversight,org_replica"
  [scoring-service]=scoring
  [document-service]=docgen
  [evidence-service]=evidence
  [esign-service]=esign
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
# tham số vai trò, nên chạy bằng tài khoản quản trị PostgreSQL như chế độ AUTO_MIGRATE dev của chính service.
# Các service Go (tenant-registry, ledger, notification) chưa có công cụ migration riêng: áp từng tệp V*.sql theo
# thứ tự và ghi tệp đã áp vào public.dev_go_migration của CSDL đó, để tệp mới thêm sau (ví dụ registry V4 active_dc)
# vẫn được áp ở lần triển khai sau. Trước đây chỉ kiểm bảng mốc rồi bỏ qua cả thư mục, nên tệp mới không bao giờ
# được áp (/config/tenants trả 500 vì thiếu cột). Lần đầu có bảng ghi mà CSDL đã có bảng mốc: tệp nào áp lỗi thì
# coi là đã áp từ trước (mỗi tệp chạy trong một giao dịch nên lỗi không để lại gì) và ghi nhận kèm cảnh báo.
go_psql() {
  local db="$1"
  shift
  docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD:-}" mission-dev-postgres psql -h 127.0.0.1 -qtA \
    -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-mission_admin}" -d "${db}" "$@"
}
run_go_migration() {
  local db="$1" dir="$2" sentinel="$3" key="${2##*/}" bootstrap=0 da_ap f ten n=0
  go_psql "${db}" -c "create table if not exists public.dev_go_migration (thu_muc text not null, tep text not null,
    applied_at timestamptz not null default now(), primary key (thu_muc, tep))" >/dev/null
  da_ap="$(go_psql "${db}" -c "select tep from public.dev_go_migration where thu_muc = '${key}'")"
  if [[ -z "${da_ap}" && "$(go_psql "${db}" -c "select to_regclass('${sentinel}') is not null")" == "t" ]]; then
    bootstrap=1
  fi
  while IFS= read -r f; do
    ten="${f##*/}"
    grep -qx "${ten}" <<<"${da_ap}" && continue
    if go_psql "${db}" -1 <"${f}" >/dev/null 2>&1; then
      n=$((n + 1))
    elif [[ "${bootstrap}" == "1" ]]; then
      echo "  ${key}: ${ten} lỗi khi áp lại, coi là đã áp từ trước (CSDL có ${sentinel} mà chưa có bảng ghi)" >&2
    else
      echo "  ${key}: áp ${ten} lỗi" >&2
      go_psql "${db}" -1 <"${f}" >/dev/null
    fi
    go_psql "${db}" -c "insert into public.dev_go_migration (thu_muc, tep) values ('${key}', '${ten}')" >/dev/null
  done < <(find "${dir}" -maxdepth 1 -name 'V*.sql' | sort -V)
  echo "  ${key}: áp ${n} tệp mới vào CSDL ${db}"
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
  # docgen và esign là schema tự chứa (migration riêng tự tạo outbox, inbox, idempotency): MigrationRunner chỉ áp
  # thư mục riêng của service và gỡ phần V0_x đã lỡ áp ở lần triển khai cũ (DatabaseLayout.SELF_CONTAINED_SCHEMAS),
  # nên Flyway của service lúc khởi động (docker-compose.yml) không còn gì để áp. workflow-service (flowable) tự
  # chạy Flyway lúc khởi động bằng mig_flowable.
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
# clamd (profile backend) là phụ thuộc của evidence-service nhưng không có ảnh dựng từ repo nên không nằm trong
# danh sách service; `up --no-deps` ở trên không bật nó, minh chứng sẽ kết luận LOI "Không gọi được clamd" (HT-20).
# Bật kèm khi triển khai evidence-service; lần đầu clamd tải chữ ký vài phút (healthcheck start_period 300s).
if selected evidence-service; then
  echo "==> Bật clamd cho evidence-service (quét minh chứng)"
  compose up -d --no-deps clamav
fi

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
# Service có schema tự chứa (esign, evidence, scoring) tạo <schema>.outbox_event khi khởi động lần đầu, mà publication
# pub_outbox chỉ gồm các bảng có lúc đăng ký: phải đăng ký lại sau khi service healthy, nếu không sự kiện của chúng
# không lên Kafka (EVT-18 ký xong không về mission-core, kế hoạch đứng ở CHO_DUYET dù yêu cầu ký đã DA_KY).
if [[ "${SKIP_OUTBOX:-0}" != "1" ]]; then
  echo "==> Đăng ký lại Debezium Outbox (mọi bảng outbox_event hiện có)"
  "${SCRIPT_DIR}/dang-ky-outbox.sh" >/dev/null || canh_bao "đăng ký Debezium Outbox" "dang-ky-outbox.sh"
fi
# Lược đồ Avro phải có trong Apicurio trước mọi lần phát sự kiện (serde không tự đăng ký): thiếu là org-republish
# hỏng và seed nhận 403 về sau, nên chờ Apicurio rồi đăng ký, lỗi thì dừng triển khai thay vì chỉ cảnh báo.
# Bỏ bằng SKIP_SCHEMA=1 (ví dụ khi HA_TANG_KHONG_BAT_BUOC=apicurio).
if [[ "${SKIP_SCHEMA:-0}" != "1" ]] && { selected mission-core || selected org-service; }; then
  echo "==> Đăng ký lược đồ sự kiện vào Apicurio"
  export REGISTRY_URL="${REGISTRY_URL:-http://127.0.0.1:${APICURIO_PORT:-35081}/apis/registry/v3}"
  deadline=$((SECONDS + 180))
  until curl -fsS "${REGISTRY_URL}/system/info" >/dev/null 2>&1; do
    [[ ${SECONDS} -lt ${deadline} ]] || die "Apicurio không trả lời ở ${REGISTRY_URL} sau 3 phút"
    sleep 5
  done
  python3 -I "${SCRIPT_DIR}/dang-ky-luoc-do.py" | tail -n 1 ||
    die "đăng ký lược đồ sự kiện lỗi (chi tiết ở trên); sửa rồi chạy lại dang-ky-luoc-do.py"
fi
if selected mission-core && [[ "${SKIP_SEED:-0}" != "1" ]]; then
  # Realm đã nhập từ trước không tự có tài khoản thử, client dịch vụ mới của realm-mission.json (HT-07, HT-08).
  "${SCRIPT_DIR}/them-tai-khoan-thu.sh" >/dev/null || canh_bao "tài khoản thử mới" "them-tai-khoan-thu.sh"
  "${SCRIPT_DIR}/them-client-integration-gateway.sh" >/dev/null ||
    canh_bao "client integration-gateway" "them-client-integration-gateway.sh"
  "${SCRIPT_DIR}/them-client-mission-core.sh" >/dev/null || canh_bao "client mission-core" "them-client-mission-core.sh"
  "${SCRIPT_DIR}/them-client-analytics-api.sh" >/dev/null || canh_bao "client analytics-api" "them-client-analytics-api.sh"
  "${SCRIPT_DIR}/seed-org.sh" || canh_bao "seed tổ chức" "seed-org.sh"
  if selected org-service || docker ps -q --filter name=mission-dev-org-service | grep -q .; then
    "${SCRIPT_DIR}/dong-bo-to-chuc.sh" || canh_bao "đồng bộ tổ chức sang mission-core" "dong-bo-to-chuc.sh"
  fi
  "${SCRIPT_DIR}/seed-ky-hien-hanh.sh" || canh_bao "nạp kỳ hiện hành" "seed-ky-hien-hanh.sh"
  "${SCRIPT_DIR}/tao-phan-vung-ky.sh" || canh_bao "tạo phân vùng theo kỳ" "tao-phan-vung-ky.sh"
  # Keycloak từ chối mã OTP đã dùng trong 30 giây của cùng tài khoản; bước sau đăng nhập lại quantri.
  sleep 31
  python3 -I "${SCRIPT_DIR}/seed-ky-nhiem-vu.py" || canh_bao "nhiệm vụ mẫu" "seed-ky-nhiem-vu.py"
  sleep 31
  python3 -I "${SCRIPT_DIR}/seed-okr-mau.py" || canh_bao "OKR mẫu đã duyệt" "seed-okr-mau.py"
  # Phiếu tự đánh giá dựng từ Mẫu 1B hiện hành của tenant; chưa phát hành thì /ui/forms/mau-1b/current trả 404.
  sleep 31
  python3 -I "${SCRIPT_DIR}/phat-hanh-mau-1b.py" --tat-ca || canh_bao "phát hành Mẫu 1B" "phat-hanh-mau-1b.py --tat-ca"
fi

echo "==> Container sau khi áp: $(docker ps -q | wc -l) đang chạy, $(docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" | wc -l) thuộc ${PROJECT}"
"${SCRIPT_DIR}/health.sh"

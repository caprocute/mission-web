#!/usr/bin/env bash
# =============================================================================
# Kiểm thử khói profile `backend` qua APISIX dev (INF-22). Ba nhóm kiểm:
#   1. Sức khoẻ: APISIX trả lời; container backend của project mission-dev healthy (khi có docker CLI).
#   2. Xác thực: gọi /api/v1/* không token bị 401.
#   3. Lời gọi có JWT thử: đăng nhập tài khoản thử (mặc định canbo) qua client mission-web bằng PKCE và OTP
#      (lay-token-thu.py), gọi GET /api/v1/tasks/mine và GET /api/v1/periods, đòi 200 (danh sách không mang ETag theo hợp đồng).
#   4. (Tuỳ chọn) Sự kiện sau thao tác ghi: khi đặt SMOKE_WRITE_PATH và SMOKE_EVENT_TOPIC, gửi lệnh ghi
#      (SMOKE_WRITE_METHOD, mặc định POST, thân SMOKE_WRITE_BODY, kèm Idempotency-Key) rồi đòi số bản ghi của
#      topic tăng (đọc offset trong container mission-dev-kafka). Không đặt thì báo "bỏ qua".
#
# Đọc infra/dev/.env (không commit): DEV_HOST, APISIX_PORT, KEYCLOAK_PUBLIC_URL (mặc định
# http://DEV_HOST:KEYCLOAK_PORT, phải trùng issuer mà service cấu hình), KC_TEST_USER_PASSWORD,
# KC_TEST_OTP_SECRET. SMOKE_TOKEN (nếu đặt) dùng thay cho bước đăng nhập. Không in token hay mật khẩu.
# Mã thoát 0 khi mọi kiểm tra bắt buộc đạt, 1 khi có kiểm tra hỏng.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

HOST="${DEV_HOST:-127.0.0.1}"
GATEWAY="http://${HOST}:${APISIX_PORT:-35000}"
export KC_URL="${KEYCLOAK_PUBLIC_URL:-http://${HOST}:${KEYCLOAK_PORT:-35080}}"
export SMOKE_USER="${SMOKE_USER:-canbo}"
failed=0

pass() { printf "  %-52s ĐẠT\n" "$1"; }
fail() {
  printf "  %-52s HỎNG (%s)\n" "$1" "$2"
  failed=1
}

echo "==> 1. Sức khoẻ"
code="$(curl -s -o /dev/null -w '%{http_code}' "${GATEWAY}/api/v1/periods")"
if [[ "${code}" != "000" ]]; then pass "APISIX ${GATEWAY}"; else fail "APISIX ${GATEWAY}" "không kết nối được"; fi
if command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1; then
  while IFS=$'\t' read -r name status; do
    [[ -z "${name}" ]] && continue
    if [[ "${status}" == *"(healthy)"* || "${name}" == "mission-dev-apisix" ]]; then
      pass "${name}"
    else
      fail "${name}" "${status}"
    fi
  done < <(docker ps --filter "label=com.docker.compose.project=mission-dev" --format '{{.Names}}\t{{.Status}}' |
    grep -E 'mission-dev-(apisix|mission-core|org-service|workflow-service|scoring-service|document-service|evidence-service|esign-service|integration-gateway|analytics-api|search|tenant-registry|ledger-service|notification-service)\b')
else
  echo "  (không có docker CLI cục bộ: bỏ qua trạng thái container)"
fi

echo "==> 2. Không token bị từ chối"
code="$(curl -s -o /dev/null -w '%{http_code}' "${GATEWAY}/api/v1/tasks/mine")"
if [[ "${code}" == "401" ]]; then pass "GET /api/v1/tasks/mine không token → 401"; else fail "GET /api/v1/tasks/mine không token" "mã ${code}"; fi

echo "==> 3. Lời gọi có JWT thử (${SMOKE_USER})"
token="${SMOKE_TOKEN:-}"
if [[ -z "${token}" ]]; then
  token="$(python3 "${SCRIPT_DIR}/lay-token-thu.py")" || token=""
fi
if [[ -z "${token}" ]]; then
  fail "lấy token qua client mission-web" "xem lỗi ở trên"
else
  pass "lấy token qua client mission-web"
  for path in /api/v1/tasks/mine /api/v1/periods; do
    headers="$(curl -s -o /dev/null -D - -H "Authorization: Bearer ${token}" "${GATEWAY}${path}")"
    code="$(printf '%s' "${headers}" | awk 'NR==1 {print $2}')"
    if [[ "${code}" != "200" ]]; then
      fail "GET ${path}" "mã ${code:-không có}"
    else
      # Hợp đồng của listMyTasks, listPeriods không khai ETag (ETag chỉ có ở tài nguyên đơn lẻ để gửi If-Match).
      pass "GET ${path} → 200"
    fi
  done
fi

echo "==> 4. Sự kiện sau thao tác ghi"
topic_end() {
  docker exec mission-dev-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic "$1" 2>/dev/null | awk -F: '{s += $3} END {print s + 0}'
}
if [[ -z "${SMOKE_WRITE_PATH:-}" || -z "${SMOKE_EVENT_TOPIC:-}" ]]; then
  echo "  bỏ qua (đặt SMOKE_WRITE_PATH, SMOKE_EVENT_TOPIC, tuỳ chọn SMOKE_WRITE_METHOD, SMOKE_WRITE_BODY)"
elif [[ -z "${token}" ]] || ! command -v docker >/dev/null 2>&1; then
  fail "sự kiện trên ${SMOKE_EVENT_TOPIC}" "thiếu token hoặc docker CLI"
else
  before="$(topic_end "${SMOKE_EVENT_TOPIC}")"
  code="$(curl -s -o /dev/null -w '%{http_code}' -X "${SMOKE_WRITE_METHOD:-POST}" \
    -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
    -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
    --data "${SMOKE_WRITE_BODY:-{\}}" "${GATEWAY}${SMOKE_WRITE_PATH}")"
  if [[ "${code}" != 2* ]]; then
    fail "${SMOKE_WRITE_METHOD:-POST} ${SMOKE_WRITE_PATH}" "mã ${code}"
  else
    after="${before}"
    for _ in $(seq 1 15); do
      after="$(topic_end "${SMOKE_EVENT_TOPIC}")"
      [[ "${after}" -gt "${before}" ]] && break
      sleep 2
    done
    if [[ "${after}" -gt "${before}" ]]; then
      pass "sự kiện mới trên ${SMOKE_EVENT_TOPIC}"
    else
      fail "sự kiện trên ${SMOKE_EVENT_TOPIC}" "không có bản ghi mới sau 30 giây"
    fi
  fi
fi

echo "==> 5. Luồng duyệt (mission-core → workflow-service) và bảng điều hành (CDC → ClickHouse → analytics-api)"
# Mỗi phép thử dùng một tài khoản khác nhau: Keycloak không nhận lại mã OTP của cùng tài khoản trong 30 giây.
running() { docker ps -q --filter "name=^mission-dev-$1\$" 2>/dev/null | grep -q .; }
goi_voi() {
  local user="$1" path="$2" tok
  tok="$(SMOKE_USER="${user}" python3 "${SCRIPT_DIR}/lay-token-thu.py" 2>/dev/null)" || tok=""
  [[ -n "${tok}" ]] || { echo "token"; return; }
  curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${tok}" "${GATEWAY}${path}"
}
if running workflow-service; then
  code="$(goi_voi "${SMOKE_POLICY_USER:-chinhsach}" /api/v1/policies/workflow-templates)"
  if [[ "${code}" == "200" ]]; then
    pass "GET /api/v1/policies/workflow-templates → 200"
  else
    # 503: mission-core chưa có địa chỉ workflow-service; 403 kèm opa_unavailable trong nhật ký: OPA chưa nạp gói.
    fail "GET /api/v1/policies/workflow-templates" "mã ${code} (503: thiếu MISSION_CONFIG_WORKFLOW_BASEURL hoặc token dịch vụ, chạy them-client-mission-core.sh)"
  fi
else
  echo "  bỏ qua luồng duyệt (workflow-service chưa chạy)"
fi
if running analytics-api && running clickhouse; then
  code="$(goi_voi "${SMOKE_ANALYTICS_USER:-quanly}" /api/v1/analytics/semantic-model)"
  if [[ "${code}" == "200" ]]; then
    pass "GET /api/v1/analytics/semantic-model → 200"
  else
    fail "GET /api/v1/analytics/semantic-model" "mã ${code} (403: kho chưa có cây tổ chức, chạy noi-cdc-phan-tich.py)"
  fi
  if python3 -I "${SCRIPT_DIR}/noi-cdc-phan-tich.py" --kiem >/dev/null 2>&1; then
    pass "connector CDC phân tích và bảng phạm vi cây tổ chức"
  else
    fail "CDC phân tích" "xem python3 -I scripts/noi-cdc-phan-tich.py --kiem"
  fi
else
  echo "  bỏ qua bảng điều hành (analytics-api hoặc clickhouse chưa chạy, profile analytics)"
fi

echo
if [[ ${failed} -eq 0 ]]; then echo "Kết quả: ĐẠT"; else echo "Kết quả: CÓ KIỂM TRA HỎNG"; fi
exit "${failed}"

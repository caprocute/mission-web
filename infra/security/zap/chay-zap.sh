#!/usr/bin/env bash
# QA-13 · Chạy kế hoạch ZAP bằng ảnh ghcr.io/zaproxy/zaproxy rồi đối chiếu ngoại lệ.
#
#   infra/security/zap/chay-zap.sh api [module]   quét chủ động API theo hợp đồng contracts/openapi/modules/<module>.yaml
#                                                 (mặc định org), cần ZAP_API_URL và cách lấy token (dưới đây)
#   infra/security/zap/chay-zap.sh web            quét thụ động trang web, cần ZAP_WEB_URL
#
# Token cho quét API, theo thứ tự ưu tiên:
#   1. ZAP_CLIENT_SECRET (+ ZAP_CLIENT_ID): client_credentials của client riêng cho ZAP (staging, TL-04);
#   2. ZAP_REFRESH_TOKEN đặt sẵn;
#   3. tự đăng nhập người dùng thử bằng kiem-thu-phu-dinh/lay-token.mjs (KT_OIDC_ISSUER, KT_MAT_KHAU hoặc
#      KC_TEST_USER_PASSWORD, KT_OTP_SECRET hoặc KC_TEST_OTP_SECRET, KT_ZAP_NGUOI_DUNG mặc định quantri).
# Biến chỉ truyền vào container theo tên (-e TÊN), giá trị không xuất hiện trên dòng lệnh hay nhật ký.
# Kết quả: $ZAP_BAO_CAO (mặc định infra/security/zap/ket-qua, đã bỏ qua trong git).
# Mã thoát: 0 đạt; 1 còn High hoặc Medium chưa giải trình; khác: lỗi chạy ZAP.
set -euo pipefail

LOAI="${1:?Cách dùng: chay-zap.sh api [module] | web}"
MODULE="${2:-org}"
ZAP_DIR="$(cd "$(dirname "$0")" && pwd)"
GOC="$(cd "${ZAP_DIR}/../../.." && pwd)"
ANH="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
export ZAP_BAO_CAO_HOST="${ZAP_BAO_CAO:-${ZAP_DIR}/ket-qua}"
mkdir -p "${ZAP_BAO_CAO_HOST}"
chmod 777 "${ZAP_BAO_CAO_HOST}"
export ZAP_BAO_CAO=/zap/wrk/ket-qua

MANG=()
[ -n "${ZAP_DOCKER_NETWORK:-}" ] && MANG=(--network "${ZAP_DOCKER_NETWORK}")
GAN=(-v "${ZAP_DIR}:/zap/wrk/zap:ro" -v "${ZAP_BAO_CAO_HOST}:/zap/wrk/ket-qua")
BIEN=(-e ZAP_BAO_CAO)

case "${LOAI}" in
  api)
    : "${ZAP_API_URL:?Thiếu ZAP_API_URL (gốc API kết thúc bằng /api/)}"
    export ZAP_TARGET_URL="${ZAP_TARGET_URL:-${ZAP_API_URL%/}/v1}"
    BAN_GOP="${GOC}/contracts/dist/${MODULE}.yaml"
    if [ ! -f "${BAN_GOP}" ] || [ "${GOC}/contracts/openapi/modules/${MODULE}.yaml" -nt "${BAN_GOP}" ]; then
      (cd "${GOC}/contracts" && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run --silent bundle)
    fi
    GAN+=(-v "${GOC}/contracts/dist:/zap/wrk/hop-dong:ro")
    export ZAP_OPENAPI_FILE="/zap/wrk/hop-dong/${MODULE}.yaml"
    export ZAP_TOKEN_URL="${ZAP_TOKEN_URL:-${KT_OIDC_ISSUER%/}/protocol/openid-connect/token}"
    export ZAP_CLIENT_ID="${ZAP_CLIENT_ID:-mission-web}"
    if [ -z "${ZAP_CLIENT_SECRET:-}" ] && [ -z "${ZAP_REFRESH_TOKEN:-}" ]; then
      ZAP_REFRESH_TOKEN="$(node "${ZAP_DIR}/../kiem-thu-phu-dinh/lay-token.mjs" refresh)"
      export ZAP_REFRESH_TOKEN
    fi
    BIEN+=(-e ZAP_API_URL -e ZAP_TARGET_URL -e ZAP_OPENAPI_FILE -e ZAP_TOKEN_URL -e ZAP_CLIENT_ID
           -e ZAP_CLIENT_SECRET -e ZAP_REFRESH_TOKEN)
    KE_HOACH=okr-api.yaml; SARIF=zap.sarif.json ;;
  web)
    : "${ZAP_WEB_URL:?Thiếu ZAP_WEB_URL (gốc trang web, kết thúc bằng /)}"
    BIEN+=(-e ZAP_WEB_URL)
    KE_HOACH=web-baseline.yaml; SARIF=zap-web.sarif.json ;;
  *) echo "Loại quét không hợp lệ: ${LOAI}" >&2; exit 2 ;;
esac

rm -f "${ZAP_BAO_CAO_HOST}/${SARIF}"
set +e
docker run --rm "${MANG[@]}" "${GAN[@]}" "${BIEN[@]}" --add-host host.docker.internal:host-gateway \
  "${ANH}" zap.sh -cmd -silent -autorun "/zap/wrk/zap/${KE_HOACH}"
MA_ZAP=$?
set -e
# Mẫu sarif-json của ZAP thêm đuôi .json vào reportFile. exitStatus của kế hoạch trả 1 khi có High, 2 khi có
# Medium; kết luận cuối do bước đối chiếu ngoại lệ quyết định.
if [ ! -s "${ZAP_BAO_CAO_HOST}/${SARIF}" ]; then
  echo "ZAP không sinh ${SARIF} (mã ${MA_ZAP}): kế hoạch lỗi hoặc không tới được đích" >&2
  exit 3
fi
node "${ZAP_DIR}/kiem-ngoai-le.mjs" "${ZAP_BAO_CAO_HOST}/${SARIF}" "${ZAP_DIR}/ngoai-le.yaml" "${LOAI}"

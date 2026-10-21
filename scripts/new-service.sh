#!/usr/bin/env bash
# NEN-019 · TL-04 WI-20, WI-23 · Baseline mục 5 "Danh mục dịch vụ (SVC)".
# Tạo microservice mới từ mẫu backend/services/service-template/.
#
# Cách dùng:
#   ./scripts/new-service.sh <ten-dich-vu> [--java|--go]
#
# Ví dụ:
#   ./scripts/new-service.sh scoring-service
#   ./scripts/new-service.sh org-service --java

set -euo pipefail

usage() {
  cat <<'EOF'
Cách dùng: ./scripts/new-service.sh <ten-dich-vu> [--java|--go]

Các dịch vụ Java hợp lệ theo baseline mục 5 (00-co-so-thiet-ke-chung.md):
  org-service           (SVC-04 · schema org)
  workflow-service      (SVC-06 · schema flowable)
  scoring-service       (SVC-07 · schema scoring)
  evidence-service      (SVC-08 · schema evidence)
  esign-service         (SVC-10 · schema esign)
  document-service      (SVC-11 · schema docgen)
  integration-gateway   (SVC-13 · schema integ; bí danh: integration-service)
EOF
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

SERVICE_NAME=""
LANG_MODE="java"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java)
      LANG_MODE="java"
      shift
      ;;
    --go)
      LANG_MODE="go"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Lỗi: Tuỳ chọn không hợp lệ: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -z "${SERVICE_NAME}" ]]; then
        SERVICE_NAME="$1"
      else
        echo "Lỗi: Thừa tham số vị trí: $1" >&2
        usage >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [[ -z "${SERVICE_NAME}" ]]; then
  echo "Lỗi: Thiếu <ten-dich-vu>." >&2
  usage >&2
  exit 1
fi

if [[ "${LANG_MODE}" == "go" ]]; then
  echo "Lỗi: Khung dịch vụ Go thuộc phạm vi NEN-017. NEN-019 chỉ tạo dịch vụ --java." >&2
  exit 1
fi

SVC_CODE=""
PKG_SEGMENT=""
DB_SCHEMA=""
APP_CLASS=""

case "${SERVICE_NAME}" in
  org-service)
    SVC_CODE="SVC-04"
    PKG_SEGMENT="org"
    DB_SCHEMA="org"
    APP_CLASS="OrgServiceApplication"
    ;;
  workflow-service)
    SVC_CODE="SVC-06"
    PKG_SEGMENT="workflow"
    DB_SCHEMA="flowable"
    APP_CLASS="WorkflowServiceApplication"
    ;;
  scoring-service)
    SVC_CODE="SVC-07"
    PKG_SEGMENT="scoring"
    DB_SCHEMA="scoring"
    APP_CLASS="ScoringServiceApplication"
    ;;
  evidence-service)
    SVC_CODE="SVC-08"
    PKG_SEGMENT="evidence"
    DB_SCHEMA="evidence"
    APP_CLASS="EvidenceServiceApplication"
    ;;
  esign-service)
    SVC_CODE="SVC-10"
    PKG_SEGMENT="esign"
    DB_SCHEMA="esign"
    APP_CLASS="EsignServiceApplication"
    ;;
  document-service)
    SVC_CODE="SVC-11"
    PKG_SEGMENT="docgen"
    DB_SCHEMA="docgen"
    APP_CLASS="DocumentServiceApplication"
    ;;
  integration-gateway|integration-service)
    SVC_CODE="SVC-13"
    PKG_SEGMENT="integ"
    DB_SCHEMA="integ"
    APP_CLASS="IntegrationServiceApplication"
    ;;
  *)
    echo "Lỗi: Dịch vụ '${SERVICE_NAME}' không nằm trong danh mục SVC Java của baseline mục 5." >&2
    usage >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICES_DIR="${REPO_ROOT}/backend/services"
TEMPLATE_DIR="${SERVICES_DIR}/service-template"
TARGET_DIR="${SERVICES_DIR}/${SERVICE_NAME}"
CHARTS_DIR="${REPO_ROOT}/deploy/charts/${SERVICE_NAME}"
SERVICES_POM="${SERVICES_DIR}/pom.xml"

if [[ ! -d "${TEMPLATE_DIR}" ]]; then
  echo "Lỗi: Không tìm thấy mẫu tại ${TEMPLATE_DIR}" >&2
  exit 1
fi

if [[ -e "${TARGET_DIR}" ]]; then
  echo "Lỗi: Thư mục đích đã tồn tại: ${TARGET_DIR}" >&2
  exit 1
fi

mkdir -p "${TARGET_DIR}" "${CHARTS_DIR}"

# 1. Sao chép cấu trúc mẫu (bỏ qua target/ nếu có).
cp "${TEMPLATE_DIR}/pom.xml" "${TARGET_DIR}/pom.xml"
cp "${TEMPLATE_DIR}/Dockerfile" "${TARGET_DIR}/Dockerfile"
mkdir -p "${TARGET_DIR}/helm"
cp "${TEMPLATE_DIR}/helm/values.yaml" "${TARGET_DIR}/helm/values.yaml"

mkdir -p "${TARGET_DIR}/src/main/resources" "${TARGET_DIR}/src/test/resources"
cp "${TEMPLATE_DIR}/src/main/resources/application.yaml" "${TARGET_DIR}/src/main/resources/application.yaml"
cp "${TEMPLATE_DIR}/src/main/resources/application-dev.yaml" "${TARGET_DIR}/src/main/resources/application-dev.yaml"
cp "${TEMPLATE_DIR}/src/test/resources/application-test.yaml" "${TARGET_DIR}/src/test/resources/application-test.yaml"

MAIN_PKG_DIR="${TARGET_DIR}/src/main/java/vn/viettel/okrkpi/${PKG_SEGMENT}"
TEST_PKG_DIR="${TARGET_DIR}/src/test/java/vn/viettel/okrkpi/${PKG_SEGMENT}"
mkdir -p "${MAIN_PKG_DIR}" "${TEST_PKG_DIR}"

cp -R "${TEMPLATE_DIR}/src/main/java/vn/viettel/okrkpi/template/." "${MAIN_PKG_DIR}/"
cp -R "${TEMPLATE_DIR}/src/test/java/vn/viettel/okrkpi/template/." "${TEST_PKG_DIR}/"

mv "${MAIN_PKG_DIR}/ServiceTemplateApplication.java" "${MAIN_PKG_DIR}/${APP_CLASS}.java"

# 2. Thay thế tên gói, tên lớp khởi chạy, mã dịch vụ, schema và artifactId.
python3 - "${TARGET_DIR}" "${CHARTS_DIR}" "${SERVICES_POM}" \
  "${SERVICE_NAME}" "${SVC_CODE}" "${PKG_SEGMENT}" "${DB_SCHEMA}" "${APP_CLASS}" <<'PY'
import pathlib
import sys

target_dir = pathlib.Path(sys.argv[1])
charts_dir = pathlib.Path(sys.argv[2])
services_pom = pathlib.Path(sys.argv[3])
service_name = sys.argv[4]
svc_code = sys.argv[5]
pkg_segment = sys.argv[6]
db_schema = sys.argv[7]
app_class = sys.argv[8]

def replace_in_file(path: pathlib.Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")

def sort_imports(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out = []
    block = []
    for line in lines:
        if line.startswith("import ") and not line.startswith("import static "):
            block.append(line)
        else:
            if block:
                out.extend(sorted(block))
                block = []
            out.append(line)
    if block:
        out.extend(sorted(block))
    return "".join(out)

java_replacements = [
    ("vn.viettel.okrkpi.template", f"vn.viettel.okrkpi.{pkg_segment}"),
    ("ServiceTemplateApplication", app_class),
    ("service-template", service_name),
]

for java_file in list(target_dir.rglob("*.java")):
    replace_in_file(java_file, java_replacements)
    java_file.write_text(sort_imports(java_file.read_text(encoding="utf-8")), encoding="utf-8")

common_replacements = [
    ("<artifactId>service-template</artifactId>", f"<artifactId>{service_name}</artifactId>"),
    ("<name>Mission · service-template</name>", f"<name>Mission · {service_name}</name>"),
    ("vn.viettel.okrkpi.template", f"vn.viettel.okrkpi.{pkg_segment}"),
    ("ServiceTemplateApplication", app_class),
    ("service-template", service_name),
    ("SVC-TEMPLATE", svc_code),
    ("svc_template", f"svc_{db_schema}"),
    ("schema: template", f"schema: {db_schema}"),
    ("schema: \"template\"", f"schema: \"{db_schema}\""),
    ("DB_SCHEMA:template", f"DB_SCHEMA:{db_schema}"),
]

for cfg_rel in [
    "pom.xml",
    "helm/values.yaml",
    "src/main/resources/application.yaml",
    "src/main/resources/application-dev.yaml",
    "src/test/resources/application-test.yaml",
]:
    replace_in_file(target_dir / cfg_rel, common_replacements)

# Chép values.yaml đã cập nhật sang deploy/charts/<ten-dich-vu>/values.yaml
(charts_dir / "values.yaml").write_text(
    (target_dir / "helm/values.yaml").read_text(encoding="utf-8"),
    encoding="utf-8",
)

# Thêm <module> vào backend/services/pom.xml nếu chưa có
pom_text = services_pom.read_text(encoding="utf-8")
module_tag = f"    <module>{service_name}</module>"
if module_tag not in pom_text:
    pom_text = pom_text.replace("  </modules>", f"{module_tag}\n  </modules>", 1)
    services_pom.write_text(pom_text, encoding="utf-8")
PY

echo "Đã tạo dịch vụ '${SERVICE_NAME}' (${SVC_CODE}, gói vn.viettel.okrkpi.${PKG_SEGMENT}, schema ${DB_SCHEMA}):"
echo "  - Mã nguồn: ${TARGET_DIR}"
echo "  - Helm values: ${CHARTS_DIR}/values.yaml"

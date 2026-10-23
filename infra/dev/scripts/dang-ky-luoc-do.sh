#!/usr/bin/env bash
# =============================================================================
# Đăng ký lược đồ Avro của danh mục sự kiện vào Apicurio dev (profile events), cùng cách Job apicurio-dang-ky
# của chart kafka làm trên cụm (INF-10): đọc danh sách `avro` trong deploy/charts/vt-platform/ha-tang/kafka/
# files/danh-muc.yaml, chép tệp .avsc theo artifactId rồi chạy files/dang_ky.py. Producer của dev (org-republish,
# outbox) đặt AUTO_REGISTER_ARTIFACT=false nên cần bước này sau khi bật Apicurio mới; chạy lại không sinh bản trùng.
#
#   infra/dev/scripts/dang-ky-luoc-do.sh            # Apicurio ở http://127.0.0.1:${APICURIO_PORT:-35081}
#   REGISTRY_URL=http://<máy>:<cổng> infra/dev/scripts/dang-ky-luoc-do.sh
# Cần python3 có PyYAML.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART="${REPO_ROOT}/deploy/charts/vt-platform/ha-tang/kafka"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  APICURIO_PORT="$(sed -n 's/^APICURIO_PORT=//p' "${ENV_FILE}" | tail -1)"
fi
export REGISTRY_URL="${REGISTRY_URL:-http://127.0.0.1:${APICURIO_PORT:-35081}}"

cfg="$(mktemp -d)"
trap 'rm -rf "${cfg}"' EXIT
python3 -I - "${CHART}/files" "${cfg}" <<'PY'
import json, shutil, sys, yaml
src, dst = sys.argv[1], sys.argv[2]
with open(f"{src}/danh-muc.yaml", encoding="utf-8") as f:
    avro = yaml.safe_load(f)["avro"]
for a in avro:
    shutil.copyfile(f"{src}/{a['tep']}", f"{dst}/{a['artifactId']}.avsc")
with open(f"{dst}/thu-tu.json", "w", encoding="utf-8") as f:
    json.dump(avro, f)
print(f"{len(avro)} lược đồ")
PY
THU_MUC="${cfg}" python3 -I "${CHART}/files/dang_ky.py"

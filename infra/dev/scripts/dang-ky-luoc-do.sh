#!/usr/bin/env bash
# =============================================================================
# Đăng ký lược đồ Avro của danh mục sự kiện vào Apicurio Registry dev (INF-10, TL-03 "Đăng ký lược đồ").
#
# Ở cụm thật, Job `apicurio-dang-ky-*` của chart vt-platform/ha-tang/kafka làm việc này sau mỗi lần cài hoặc nâng
# cấp. Compose dev không có Job đó, nên bộ ghi sự kiện (org-republish, outbox của service) báo "Could not resolve
# artifact reference by content". Script dùng lại đúng danh mục `files/danh-muc.yaml` (mục `avro`) và
# `files/dang_ky.py` của chart, chạy trên máy có Python 3 kèm PyYAML; chạy lại an toàn (FIND_OR_CREATE_VERSION).
#
#   ./infra/dev/scripts/dang-ky-luoc-do.sh                       # Apicurio ở http://127.0.0.1:${APICURIO_PORT:-35081}
#   REGISTRY_URL=http://apicurio:8080 ./infra/dev/scripts/dang-ky-luoc-do.sh
#   DANG_KY_DANG_LOP=0 ./infra/dev/scripts/dang-ky-luoc-do.sh       # bỏ lượt hai (dạng lớp sinh mã)
#
# Lượt hai (mặc định bật, cần JDK 21 và jar backend/libs/vt-events đã dựng): bộ ghi dùng AUTO_REGISTER=false và
# tra lược đồ theo nội dung của lớp Avro sinh mã (SCHEMA$ có "avro.java.string" và kiểu lồng viết liền), khác
# dạng tệp .avsc có tham chiếu mà lượt một đăng ký, nên Apicurio không khớp. Lượt hai đăng ký thêm dạng lớp làm
# phiên bản kế tiếp của cùng artifact. Đây là cách vá ở dev; lệch giữa .avsc và lớp sinh mã cần sửa ở phía
# máy chủ (ghi trong data/seed/e2e/README.md).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART="${REPO_ROOT}/deploy/charts/vt-platform/ha-tang/kafka/files"
ENV_FILE="${SCRIPT_DIR}/../.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi
export REGISTRY_URL="${REGISTRY_URL:-http://127.0.0.1:${APICURIO_PORT:-35081}}"

THU_MUC="$(mktemp -d)"
trap 'rm -rf "${THU_MUC}"' EXIT
python3 - "${CHART}" "${THU_MUC}" <<'PY'
import json, shutil, sys
import yaml
chart, out = sys.argv[1], sys.argv[2]
ds = yaml.safe_load(open(f"{chart}/danh-muc.yaml", encoding="utf-8"))["avro"]
for a in ds:
    shutil.copyfile(f"{chart}/{a['tep']}", f"{out}/{a['artifactId']}.avsc")
json.dump(ds, open(f"{out}/thu-tu.json", "w", encoding="utf-8"), ensure_ascii=False)
print(f"{len(ds)} lược đồ cần đăng ký")
PY
THU_MUC="${THU_MUC}" python3 -I "${CHART}/dang_ky.py"

[[ "${DANG_KY_DANG_LOP:-1}" == "1" ]] || exit 0
JAR="$(ls "${REPO_ROOT}"/backend/libs/vt-events/target/vt-events-*.jar 2>/dev/null | grep -v sources | head -1 || true)"
if [[ -z "${JAR}" ]] || ! command -v java >/dev/null; then
  echo "Bỏ lượt hai: cần JDK và jar vt-events (./mvnw -pl libs/vt-events -am install -DskipTests)" >&2
  exit 1
fi
LOP="$(mktemp -d)"
trap 'rm -rf "${THU_MUC}" "${LOP}"' EXIT
(cd "${REPO_ROOT}/backend" && ${MVN:-./mvnw} -B -q -o -pl libs/vt-events dependency:build-classpath \
  -Dmdep.outputFile="${LOP}/cp.txt" >/dev/null)
cat >"${LOP}/InLop.java" <<'JAVA'
import java.nio.file.Files;
import java.nio.file.Path;

/** Ghi SCHEMA$ của lớp Avro sinh mã: tham số là tệp (artifactId TAB tên lớp) và thư mục ra. */
public class InLop {
  public static void main(String[] a) throws Exception {
    for (String dong : Files.readAllLines(Path.of(a[0]))) {
      String[] p = dong.split("\t");
      Object s = Class.forName(p[1]).getMethod("getClassSchema").invoke(null);
      Files.writeString(Path.of(a[1], p[0] + ".avsc"), s.toString());
    }
  }
}
JAVA
mkdir -p "${LOP}/ra"
python3 - "${THU_MUC}" "${LOP}" <<'PY'
import json, sys
vao, lop = sys.argv[1], sys.argv[2]
ds = json.load(open(f"{vao}/thu-tu.json", encoding="utf-8"))
with open(f"{lop}/lop.tsv", "w", encoding="utf-8") as f:
    for a in ds:
        sc = json.load(open(f"{vao}/{a['artifactId']}.avsc", encoding="utf-8"))
        f.write(f"{a['artifactId']}\t{sc['namespace']}.{sc['name']}\n")
json.dump([{"artifactId": a["artifactId"]} for a in ds], open(f"{lop}/ra/thu-tu.json", "w", encoding="utf-8"))
PY
java -cp "${JAR}:$(cat "${LOP}/cp.txt")" "${LOP}/InLop.java" "${LOP}/lop.tsv" "${LOP}/ra"
echo "Lượt hai: dạng lớp sinh mã"
THU_MUC="${LOP}/ra" python3 -I "${CHART}/dang_ky.py"

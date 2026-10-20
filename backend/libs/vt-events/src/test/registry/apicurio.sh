#!/bin/bash
# NEN-007 · Bật/tắt Apicurio Registry 3.x thử trong Docker cho profile -Pschema-compat.
#   apicurio.sh start <ảnh> <cổng> <tên-container> <quy-tắc-tương-thích>
#   apicurio.sh stop  <tên-container>
# Registry chạy bộ nhớ trong (không lưu bền), chỉ dùng cho kiểm tương thích lược đồ; CI có thể
# trỏ thẳng tới registry dùng chung bằng -Dschema.compat.registryUrl=... -Dschema.compat.docker.skip=true.
set -euo pipefail

cmd="${1:?start|stop}"
case "$cmd" in
  start)
    image="${2:?ảnh}"; port="${3:?cổng}"; name="${4:?tên container}"; rule="${5:?quy tắc}"
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --rm --name "$name" -p "127.0.0.1:${port}:8080" "$image" >/dev/null
    base="http://127.0.0.1:${port}/apis/registry/v3"
    for _ in $(seq 1 120); do
      if curl -fsS "${base}/system/info" >/dev/null 2>&1; then break; fi
      sleep 1
    done
    curl -fsS "${base}/system/info" >/dev/null
    # Quy tắc toàn cục: tương thích BACKWARD_TRANSITIVE (TL-03 "Phong bì sự kiện") và hợp lệ cú pháp.
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "{\"ruleType\":\"COMPATIBILITY\",\"config\":\"${rule}\"}" "${base}/admin/rules" >/dev/null
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d '{"ruleType":"VALIDITY","config":"FULL"}' "${base}/admin/rules" >/dev/null
    echo "Apicurio Registry sẵn sàng tại ${base} (COMPATIBILITY=${rule}, VALIDITY=FULL)"
    ;;
  stop)
    name="${2:?tên container}"
    docker rm -f "$name" >/dev/null 2>&1 || true
    ;;
  *)
    echo "Lệnh không hợp lệ: $cmd" >&2
    exit 2
    ;;
esac

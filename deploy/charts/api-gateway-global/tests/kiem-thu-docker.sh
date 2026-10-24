#!/usr/bin/env bash
# Kiểm thử tích hợp DV-01-04 với APISIX 3.13 thật trong Docker, không cần cụm: dựng ConfigMap của chart
# api-gateway-global, chạy SVC-03 giả lập (token client credentials, registry/resolve có công tắc ngừng và chuyển cell)
# và hai cell giả lập, rồi thử: trúng đệm không gọi registry, hết hạn gọi lại, 421 làm sạch đệm, registry ngừng vẫn
# phục vụ tenant đã đệm, p95 khi trúng đệm ≤ 50 ms, cookie định tuyến, khám phá 307 trên tên miền chung, tenant
# TAM_NGUNG đọc được nhưng lệnh ghi bị chặn 403 (TL-04 mục 11.4).
# Cần docker, helm, python3 có PyYAML.
set -euo pipefail
cd "$(dirname "$0")/.."
APISIX_IMG=${APISIX_IMG:-mirror.gcr.io/apache/apisix:3.13.0-debian}
PY_IMG=${PY_IMG:-mirror.gcr.io/library/python:3.13-alpine}
W=$(mktemp -d); NET=vt-apigw-global-$$
don() { docker rm -f "$NET-apisix" "$NET-reg" "$NET-a" "$NET-b" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$W"; }
trap don EXIT
helm template g . -f tests/values-docker.yaml > "$W/r.yaml"
python3 -I - "$W" <<'PY'
import base64, os, sys, yaml
d = sys.argv[1]
for doc in yaml.safe_load_all(open(f"{d}/r.yaml")):
    if doc and doc.get("kind") == "ConfigMap":
        for k, v in doc["data"].items():
            open(f"{d}/{k}", "w").write(v)
open(f"{d}/route-key", "w").write(base64.b64encode(os.urandom(32)).decode())
PY
cp tests/gia-lap.py "$W/"; chmod -R a+rX "$W"
docker network create "$NET" >/dev/null
docker run -d --name "$NET-reg" --network "$NET" --network-alias registry.test --network-alias registry.test.svc.cluster.local \
  -p 127.0.0.1::8080 -e VAI=registry -v "$W/gia-lap.py:/g.py:ro" "$PY_IMG" python /g.py >/dev/null
docker run -d --name "$NET-a" --network "$NET" --network-alias cell-a.test -e TEN=cell-a -v "$W/gia-lap.py:/g.py:ro" "$PY_IMG" python /g.py >/dev/null
docker run -d --name "$NET-b" --network "$NET" --network-alias cell-b.test -e TEN=cell-b -v "$W/gia-lap.py:/g.py:ro" "$PY_IMG" python /g.py >/dev/null
docker run -d --name "$NET-apisix" --network "$NET" -p 127.0.0.1::9080 -e APISIX_STAND_ALONE=true \
  -e VT_REGISTRY_CLIENT_SECRET=khong-dung -e VT_ROUTE_COOKIE_KEY="$(cat "$W/route-key")" \
  -v "$W/config.yaml:/usr/local/apisix/conf/config.yaml:ro" -v "$W/apisix.yaml:/usr/local/apisix/conf/apisix.yaml:ro" \
  -v "$W/vt-cell-router.lua:/opt/vt/lua/apisix/plugins/vt-cell-router.lua:ro" "$APISIX_IMG" >/dev/null
GW=$(docker port "$NET-apisix" 9080/tcp | head -1 | sed 's/.*://'); REG=$(docker port "$NET-reg" 8080/tcp | head -1 | sed 's/.*://')
for i in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$GW/" && break; sleep 1; done
if ! python3 -I "$(pwd)/tests/kiem-thu-docker.py" "http://127.0.0.1:$GW" "http://127.0.0.1:$REG" "$W/route-key"; then
  docker logs "$NET-apisix" 2>&1 | grep -E "error|warn" | tail -30; exit 1
fi

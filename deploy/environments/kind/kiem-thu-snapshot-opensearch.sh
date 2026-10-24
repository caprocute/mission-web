#!/usr/bin/env bash
# Kiểm thử snapshot OpenSearch sang kho sao lưu (INF-18, TL-04 mục 8.8 và bảng lịch sao lưu) trên kho Ceph thật của
# kind (container ceph-bk dựng ở bước INF-17, bucket bk-cell-<cell>-os có Object Lock): một nút OpenSearch 2.19.3 (đúng
# digest của chart) chạy bằng Docker trên mạng kind, cấu hình lấy từ chart opensearch đã render:
#   - plugin repository-s3 cài từ tệp zip đã kiểm SHA-512 (cách cài khi không ra Internet), khoá S3 của bucket nằm
#     trong keystore, opensearch.yml theo additionalConfig, repo bk-sn theo snapshotRepositories của chart;
#   - chính sách Snapshot Management daily-to-sn đúng lịch, điều kiện xoá, chỉ mục của chart được OpenSearch chấp nhận;
#     một bản sao của nó chạy mỗi phút tạo được snapshot SUCCESS lên bucket Object Lock;
#   - vai trò sao-luu theo chart không ghi được chỉ mục thật, không tạo được snapshot;
#   - khoi-phuc-thu-opensearch.sh của backup-cell (người dùng sao-luu) khôi phục vào khoi-phuc-thu-*, so số tài liệu,
#     xoá chỉ mục tạm, chỉ số success 1; xoá bớt tài liệu gốc sau snapshot thì vẫn đạt (snapshot giữ bản cũ).
# Cần docker, helm, jq, curl, python3; KHO_BAO trỏ tới kho OpenBao giả chứa khoá Ceph (kv/ceph/cell-<cell>/<uid>/).
# Dùng: KHO_BAO=/duong/dan/kho deploy/environments/kind/kiem-thu-snapshot-opensearch.sh   (GIU_LAI=1 giữ container)
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/vt-platform/ha-tang/opensearch
CFG_BK=$GOC/deploy/charts/backup-cell/files
CELL=${KIND_CELL:-kind}   # không dùng biến CELL của môi trường (có thể là cell thật)
KHO_BAO=${KHO_BAO:?cần KHO_BAO (kho OpenBao giả chứa khoá Ceph)}
S3=${S3_ENDPOINT:-172.18.0.51:8080}
OS_ANH=opensearchproject/opensearch:2.19.3@sha256:e96cc6ae1500a073d973c0906f30f7cf4d9c461f32f855f9242a2da933660cdd
ZIP_URL=https://artifacts.opensearch.org/releases/plugins/repository-s3/2.19.3/repository-s3-2.19.3.zip
ZIP_SHA512=098a94a0a959caa70ed286d232f8d4d9a064aa70f85c6a1198f1fd030196721c232b9084efcad9aa27188ad10827b7f6a8f89f54573ad12098dc7053e965990d
TEN=os-thu-snapshot
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
don() {
  [ "${GIU_LAI:-0}" = 1 ] || docker rm -f $TEN >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap don EXIT
cho() { # <số lần> <lệnh...>: thử lại mỗi 5 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1
}
mk() { openssl rand -hex 12; }

echo "== Chuẩn bị: cấu hình từ chart, plugin repository-s3, nút OpenSearch trên mạng kind"
helm template os "$CHART" --set global.cell="$CELL" --set snapshot.endpoint="$S3" --set snapshot.protocol=http > "$W/os.yaml"
python3 - "$W" <<'PY'
import json, sys, yaml
w = sys.argv[1]
docs = [d for d in yaml.safe_load_all(open(f"{w}/os.yaml")) if d]
c = next(d for d in docs if d["kind"] == "OpenSearchCluster")["spec"]["general"]
p = next(d for d in docs if d["kind"] == "OpenSearchSnapshotPolicy")["spec"]
json.dump({"cfg": c["additionalConfig"], "repo": c["snapshotRepositories"][0], "plugins": c["pluginsList"],
           "keystore": c["keystore"][0]["keyMappings"], "policy": p}, open(f"{w}/chart.json", "w"))
PY
jq -r '.cfg | to_entries[] | "\(.key): \(.value)"' "$W/chart.json" > "$W/them.yml"
ZIP=${PLUGIN_ZIP:-$W/repository-s3.zip}
[ -f "$ZIP" ] || curl -sSfL -o "$ZIP" "$ZIP_URL"
echo "$ZIP_SHA512  $ZIP" | sha512sum -c --quiet || { hong "SHA-512 của plugin không khớp"; exit 1; }
KHOA=$KHO_BAO/kv/ceph/cell-$CELL/bk-cell-$CELL-os
MK_ADMIN="Vt-$(mk)-A1"; MK_SL="Sl-$(mk)-b2"
echo "$MK_SL" > "$W/mk-sao-luu"
docker rm -f $TEN >/dev/null 2>&1 || true
# Plugin và keystore cài trước khi nút khởi động, như operator (pluginsList, keystore từ Secret ESO).
# Cổng 9200 chỉ mở trên 127.0.0.1 của máy chạy kiểm thử: chứng thư demo của OpenSearch chỉ có localhost, 127.0.0.1.
# Ngưỡng đĩa tắt vì đĩa máy agent thường đầy hơn 95% (chỉ mục sẽ bị khoá chỉ đọc).
docker run -d --name $TEN --network kind -p 127.0.0.1:"${OS_CONG:-19200}":9200 -e cluster.routing.allocation.disk.threshold_enabled=false -v "$ZIP:/plugin.zip:ro" -v "$W/them.yml:/them.yml:ro" \
  -e discovery.type=single-node -e OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m" -e OPENSEARCH_INITIAL_ADMIN_PASSWORD="$MK_ADMIN" \
  -e S3_AK="$(cat "$KHOA/access-key")" -e S3_SK="$(cat "$KHOA/secret-key")" \
  --entrypoint bash $OS_ANH -c '
set -e
bin/opensearch-plugin install --batch file:///plugin.zip >/dev/null
[ -f config/opensearch.keystore ] || bin/opensearch-keystore create
printf %s "$S3_AK" | bin/opensearch-keystore add --stdin s3.client.default.access_key
printf %s "$S3_SK" | bin/opensearch-keystore add --stdin s3.client.default.secret_key
unset S3_AK S3_SK
cat /them.yml >> config/opensearch.yml
exec ./opensearch-docker-entrypoint.sh opensearch' >/dev/null
URL=https://localhost:${OS_CONG:-19200}
cho 60 docker cp $TEN:/usr/share/opensearch/config/root-ca.pem "$W/ca.pem" || { hong "không lấy được CA demo"; exit 1; }
adm() { curl -sS --fail-with-body --cacert "$W/ca.pem" -u "admin:$MK_ADMIN" -X "$1" -H 'Content-Type: application/json' ${3:+--data-binary "$3"} "$URL$2"; }
if cho 60 adm GET /_cluster/health; then ok "OpenSearch 2.19.3 chạy, plugin $(jq -r '.plugins[0]' "$W/chart.json") cài từ zip đã kiểm SHA-512"
else hong "OpenSearch không lên"; docker logs --tail 30 $TEN; exit 1; fi

echo "== Repo bk-sn và chính sách daily-to-sn theo chart"
REPO=$(jq -r '.repo.name' "$W/chart.json")
than=$(jq -c '{type: .repo.type, settings: .repo.settings}' "$W/chart.json")
if kq=$(adm PUT "/_snapshot/$REPO" "$than" 2>&1) && adm POST "/_snapshot/$REPO/_verify" >/dev/null 2>&1; then
  ok "repo $REPO trên bucket $(jq -r '.repo.settings.bucket' "$W/chart.json") đăng ký và kiểm ghi đọc được (Object Lock)"
else hong "repo $REPO lỗi: $kq"; exit 1; fi
# Chính sách SM từ CR của chart (operator đổi trường camelCase sang API _plugins/_sm).
sm() { # <tên> <cron tạo>
  jq -c --arg ten "$1" --arg tao "$2" '.policy | {description, enabled,
    creation: {schedule: {cron: {expression: $tao, timezone: .creation.schedule.cron.timezone}}},
    deletion: {schedule: {cron: .deletion.schedule.cron}, condition: {max_count: .deletion.deleteCondition.maxCount,
      min_count: .deletion.deleteCondition.minCount}},
    snapshot_config: {repository: .snapshotConfig.repository, indices: .snapshotConfig.indices,
      include_global_state: .snapshotConfig.includeGlobalState, partial: .snapshotConfig.partial}}' "$W/chart.json"
}
P=$(jq -r '.policy.policyName' "$W/chart.json")
if adm POST "/_plugins/_sm/policies/$P" "$(sm "$P" "$(jq -r '.policy.creation.schedule.cron.expression' "$W/chart.json")")" >/dev/null; then
  g=$(adm GET "/_plugins/_sm/policies/$P" | jq -c '.sm_policy | [.creation.schedule.cron.expression, .creation.schedule.cron.timezone, .deletion.schedule.cron.expression, .deletion.condition.max_count, .deletion.condition.min_count, .snapshot_config.indices]')
  ok "chính sách $P được chấp nhận: $g"
else hong "chính sách $P bị từ chối"; fi

echo "== Dữ liệu và snapshot"
nap() { # <chỉ mục> <số tài liệu>
  seq 1 "$2" | awk -v i="$1" '{printf "{\"index\":{\"_index\":\"%s\",\"_id\":\"%d\"}}\n{\"tieu_de\":\"tai lieu %d\",\"so\":%d}\n", i, $1, $1, $1}' > "$W/bulk"
  curl -sS --fail --cacert "$W/ca.pem" -u "admin:$MK_ADMIN" -H 'Content-Type: application/x-ndjson' --data-binary @"$W/bulk" "$URL/_bulk?refresh=true" | jq -e '.errors == false' >/dev/null
}
for x in okr-thu:1500 org-thu:1200 mission-thu:1100 evidence-thu:1000 khac-thu:50; do nap "${x%%:*}" "${x#*:}"; done
# Bản sao của chính sách chạy mỗi phút để có snapshot thật do SM tạo (lịch của chart là 02:00).
adm POST "/_plugins/_sm/policies/$P-moi-phut" "$(sm "$P-moi-phut" "* * * * *")" >/dev/null
co_snap() { adm GET "/_snapshot/$REPO/_all" | jq -e '[.snapshots[] | select(.state == "SUCCESS")] | length > 0'; }
if cho 48 co_snap; then
  s=$(adm GET "/_snapshot/$REPO/_all" | jq -c '[.snapshots[] | select(.state == "SUCCESS")][0] | {snapshot, indices: (.indices | sort)}')
  ok "Snapshot Management tạo snapshot SUCCESS trên bucket Object Lock: $s"
  grep -q khac-thu <<<"$s" && hong "snapshot chứa chỉ mục ngoài danh sách của chính sách" || ok "snapshot chỉ gồm chỉ mục okr-*, mission-*, evidence-*, org-*"
else hong "chưa có snapshot SUCCESS"; adm GET "/_plugins/_sm/policies/$P-moi-phut/_explain" | head -c 1500; echo; fi
adm DELETE "/_plugins/_sm/policies/$P-moi-phut" >/dev/null || true
# Chờ snapshot đang chạy (nếu SM vừa tạo thêm) xong để khôi phục không vướng.
cho 24 bash -c "[ \"\$(curl -sS --cacert '$W/ca.pem' -u 'admin:$MK_ADMIN' '$URL/_snapshot/_status' | jq '.snapshots | length')\" = 0 ]" || true

echo "== Vai trò sao-luu theo chart"
helm show values "$CHART" | python3 -c '
import json, sys, yaml
r = yaml.safe_load(sys.stdin)["roles"]["sao-luu"]
ip = [{"index_patterns": r["indexPatterns"], "allowed_actions": r["allowedActions"]}]
ip += [{"index_patterns": t["indexPatterns"], "allowed_actions": t["allowedActions"]} for t in r.get("them", [])]
print(json.dumps({"cluster_permissions": r["clusterPermissions"], "index_permissions": ip}))' > "$W/vai-tro.json"
adm PUT /_plugins/_security/api/roles/sao-luu "$(cat "$W/vai-tro.json")" >/dev/null
adm PUT /_plugins/_security/api/internalusers/sao-luu "$(jq -nc --arg p "$MK_SL" '{password: $p}')" >/dev/null
adm PUT /_plugins/_security/api/rolesmapping/sao-luu '{"users":["sao-luu"]}' >/dev/null
sl() { curl -sS -o /dev/null -w '%{http_code}' --cacert "$W/ca.pem" -u "sao-luu:$MK_SL" -X "$1" -H 'Content-Type: application/json' ${3:+--data-binary "$3"} "$URL$2"; }
[ "$(sl POST /okr-thu/_doc '{"x":1}')" = 403 ] && ok "sao-luu không ghi được chỉ mục thật okr-thu (403)" || hong "sao-luu ghi được okr-thu"
[ "$(sl PUT "/_snapshot/$REPO/tu-tao?wait_for_completion=true" '{}')" = 403 ] && ok "sao-luu không tạo được snapshot (403)" || hong "sao-luu tạo được snapshot"
[ "$(sl DELETE "/_snapshot/$REPO/_all")" = 403 ] && ok "sao-luu không xoá được snapshot (403)" || hong "sao-luu xoá được snapshot"

echo "== khoi-phuc-thu-opensearch.sh của backup-cell"
# Xoá bớt tài liệu gốc sau snapshot: snapshot vẫn giữ đủ, khôi phục thử so với gốc hiện tại vẫn đạt.
adm POST "/okr-thu/_delete_by_query?refresh=true" '{"query":{"range":{"so":{"gt":1400}}}}' >/dev/null
chay_kp() { # <nhóm> <tệp ra>: một nhóm mỗi lần (script chọn nhóm theo tuần trong MAU)
  env CFG="$CFG_BK" CELL="$CELL" OS_URL="$URL" OS_USER=sao-luu OS_PASSWORD_FILE="$W/mk-sao-luu" OS_CA="$W/ca.pem" \
    OS_REPO="$REPO" MAU="$1" NGUONG_TAI_LIEU=1000 TI_LE=0.5 PUSHGATEWAY= \
    bash "$CFG_BK/khoi-phuc-thu-opensearch.sh" > "$2" 2>&1
}
for nhom in org okr mission evidence; do
  if chay_kp "$nhom-*" "$W/kp-$nhom.out" && grep -q '^backup_opensearch_restore_check_success 1$' "$W/kp-$nhom.out"; then
    ok "khôi phục thử nhóm $nhom-* bằng người dùng sao-luu: đạt"
  else hong "khôi phục thử nhóm $nhom-*"; tail -5 "$W/kp-$nhom.out"; fi
done
if adm GET "/_cat/indices/khoi-phuc-thu-*?format=json" | jq -e 'length == 0' >/dev/null; then ok "chỉ mục tạm khoi-phuc-thu-* đã xoá"
else hong "còn chỉ mục tạm"; fi

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

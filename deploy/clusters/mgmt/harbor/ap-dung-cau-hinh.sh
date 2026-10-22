#!/usr/bin/env bash
# Áp cau-hinh.yaml vào Harbor qua API v2.0 (INF-07). Chạy lại được nhiều lần: thứ gì đã có thì cập nhật metadata,
# robot đã có thì giữ nguyên (thêm --xoay để sinh lại mật khẩu). Mật khẩu robot ghi thẳng vào OpenBao, không in ra.
# Cần: curl, jq, python3 + PyYAML, bao (OpenBao CLI, đã đăng nhập). Biến: HARBOR_URL, HARBOR_USER, HARBOR_PASS_FILE
# (tệp chứa mật khẩu admin, lấy từ OpenBao kv/mgmt/harbor/admin), CAU_HINH (mặc định cau-hinh.yaml cạnh script).
# Dùng: HARBOR_URL=https://harbor.vt.local ap-dung-cau-hinh.sh [--xoay] [--thu]   (--thu: chỉ in việc sẽ làm)
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
CAU_HINH=${CAU_HINH:-$D/cau-hinh.yaml}
XOAY=0; THU=0
for a in "$@"; do case $a in --xoay) XOAY=1;; --thu) THU=1;; *) echo "tham số lạ: $a" >&2; exit 2;; esac; done
J=$(python3 -c 'import json,sys,yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))' "$CAU_HINH")
api() {  # api <method> <đường dẫn> [thân JSON]
  local m=$1 p=$2 b=${3:-}
  if [ "$THU" = 1 ] && [ "$m" != GET ]; then echo "[thử] $m $p ${b:0:160}" >&2; echo '{}'; return; fi
  if [ "$THU" = 1 ]; then echo '[]'; return; fi
  curl -fsS -X "$m" -u "${HARBOR_USER:-admin}:$(cat "$HARBOR_PASS_FILE")" -H 'Content-Type: application/json' \
    ${b:+--data "$b"} "$HARBOR_URL/api/v2.0$p"
}
co_project() { api GET "/projects?name=$1&page_size=100" | jq -e --arg n "$1" 'map(select(.name==$n))|length>0' >/dev/null; }

echo "== Endpoint proxy-cache và project"
while read -r pc; do
  ten=$(jq -r .ten <<<"$pc")
  rid=$(api GET "/registries?q=name%3D$ten" | jq -r '.[0].id // empty')
  if [ -z "$rid" ]; then
    api POST /registries "$(jq -c '{name:.ten,type:.loai,url:.url,insecure:false}' <<<"$pc")" >/dev/null
    rid=$(api GET "/registries?q=name%3D$ten" | jq -r '.[0].id // 0')
  fi
  md=$(jq -c .macDinhProxyCache <<<"$J")
  co_project "$ten" || api POST /projects "$(jq -nc --arg n "$ten" --argjson r "${rid:-0}" --argjson m "$md" \
    '{project_name:$n,registry_id:$r,metadata:$m,storage_limit:-1}')" >/dev/null
  echo "  proxy-cache $ten"
done < <(jq -c '.proxyCache[]' <<<"$J")
while read -r p; do
  ten=$(jq -r .ten <<<"$p"); md=$(jq -c .metadata <<<"$p")
  if co_project "$ten"; then api PUT "/projects/$ten" "$(jq -nc --argjson m "$md" '{metadata:$m}')" >/dev/null
  else api POST /projects "$(jq -nc --arg n "$ten" --argjson m "$md" '{project_name:$n,metadata:$m,storage_limit:-1}')" >/dev/null; fi
  echo "  project $ten"
done < <(jq -c '.projects[]' <<<"$J")

echo "== Robot account"
while read -r r; do
  ten=$(jq -r .ten <<<"$r")
  rid=$(api GET "/robots?q=name%3D~$ten" | jq -r --arg n "$ten" 'map(select(.name|endswith("$"+$n)))|.[0].id // empty')
  quyen=$(jq -c '[.quyen[] | {kind:"project",namespace:.project,access:[.hanhDong[]|{resource:"repository",action:.}]}]' <<<"$r")
  if [ -n "$rid" ] && [ "$XOAY" = 0 ]; then echo "  giữ $ten"; continue; fi
  if [ -n "$rid" ]; then
    mk=$(api PATCH "/robots/$rid" '{}' | jq -r '.secret // empty')
  else
    mk=$(api POST /robots "$(jq -nc --arg n "$ten" --arg d "$(jq -r .moTa <<<"$r")" --argjson h "$(jq .hanNgay <<<"$r")" \
      --argjson q "$quyen" '{name:$n,description:$d,duration:$h,level:"system",disable:false,permissions:$q}')" | jq -r '.secret // empty')
  fi
  # Mật khẩu chỉ đi qua stdin của bao, không nằm trong tham số dòng lệnh hay log.
  [ "$THU" = 1 ] || printf '%s' "$mk" | bao kv put "kv/mgmt/harbor/robots/$ten" secret=- >/dev/null
  echo "  đã tạo/xoay $ten (mật khẩu ở OpenBao kv/mgmt/harbor/robots/$ten)"
done < <(jq -c '.robots[]' <<<"$J")

echo "== Nhân bản sang harbor-sn"
dich=$(jq -c .nhanBan.dich <<<"$J"); tdich=$(jq -r .ten <<<"$dich")
did=$(api GET "/registries?q=name%3D$tdich" | jq -r '.[0].id // empty')
if [ -z "$did" ] && [ "$THU" = 0 ]; then
  mkSn=$(bao kv get -field=secret "$(jq -r .secretOpenBao <<<"$dich")" 2>/dev/null || true)
  if [ -n "$mkSn" ]; then
    api POST /registries "$(jq -nc --argjson d "$dich" --arg s "$mkSn" \
      '{name:$d.ten,type:$d.loai,url:$d.url,insecure:false,credential:{type:"basic",access_key:"robot$replicate-sn",access_secret:$s}}')" >/dev/null
    did=$(api GET "/registries?q=name%3D$tdich" | jq -r '.[0].id // empty')
  else
    echo "  bỏ qua: chưa có robot replicate-sn của harbor-sn trong OpenBao (INF-18 tạo)"; exit 0
  fi
fi
while read -r q; do
  ten=$(jq -r .ten <<<"$q")
  co=$(api GET "/replication/policies?name=$ten" | jq -r --arg n "$ten" 'map(select(.name==$n))|.[0].id // empty')
  body=$(jq -nc --argjson q "$q" --argjson d "${did:-0}" '{name:$q.ten,enabled:$q.bat,dest_registry:{id:$d},
    trigger:{type:$q.kichHoat},deletion:$q.xoaTheo,override:true,filters:$q.boLoc,replicate_deletion:$q.xoaTheo}')
  if [ -n "$co" ]; then api PUT "/replication/policies/$co" "$body" >/dev/null; else api POST /replication/policies "$body" >/dev/null; fi
  echo "  quy tắc $ten (bật: $(jq -r .bat <<<"$q"))"
done < <(jq -c '.nhanBan.quyTac[]' <<<"$J")

#!/usr/bin/env bash
# Kiểm tĩnh chart api-gateway-global (DV-01-04): helm lint, render mặc định và values production của vùng toàn cục,
# kubeconform -strict kèm schema CRD, cú pháp Lua của vt-cell-router (luajit trong ảnh APISIX nếu có docker), ca phải
# thất bại. Kiểm thử tích hợp với APISIX thật: tests/kiem-thu-docker.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
ENV=../../environments/global/api-gateway-global/values.yaml
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm lint . -f "$ENV" >/dev/null && ok "helm lint" || hong "helm lint"
for ten in mac-dinh production; do
  args=(); [ "$ten" = production ] && args=(-f "$ENV")
  helm template g . "${args[@]}" > "$OUT/$ten.yaml" && ok "render $ten" || hong "render $ten"
  kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/$ten.yaml" > "$OUT/kc" 2>&1 \
    && ok "kubeconform -strict $ten ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $ten"; cat "$OUT/kc"; }
done
grep -q 'vt-cell-cache: 64m' "$OUT/production.yaml" && ok "lua_shared_dict đệm phân giải" || hong "shared dict"
grep -q 'cache_ttl: 300' "$OUT/production.yaml" && ok "đệm phân giải 300 giây" || hong "cache_ttl"
grep -q 'enable_admin: false' "$OUT/production.yaml" && ok "không mở Admin API" || hong "admin"
grep -q 'kind: ExternalSecret' "$OUT/production.yaml" && ! grep -qi 'ROUTING_COOKIE_KEY: ' "$OUT/production.yaml" \
  && ok "khoá HMAC qua ExternalSecret, không có giá trị trong manifest" || hong "khoá HMAC"
if command -v docker >/dev/null && docker image inspect mirror.gcr.io/apache/apisix:3.13.0-debian >/dev/null 2>&1; then
  docker run --rm -v "$PWD/files:/f:ro" --entrypoint /usr/local/openresty/luajit/bin/luajit \
    mirror.gcr.io/apache/apisix:3.13.0-debian -bl /f/vt-cell-router.lua >/dev/null && ok "cú pháp Lua vt-cell-router" || hong "cú pháp Lua"
fi
phai_hong() { local mota=$1 mau=$2; shift 2
  if helm template g . "$@" >/dev/null 2>"$OUT/e"; then hong "$mota: lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/e"; then ok "$mota bị từ chối"; else hong "$mota: thông báo không rõ"; cat "$OUT/e"; fi; }
phai_hong "ảnh không theo digest" "SEC-55" --set apisix.image.digest=latest
phai_hong "thời gian đệm 0" "cache.ttlSeconds" --set cache.ttlSeconds=0
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

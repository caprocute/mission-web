#!/usr/bin/env bash
# Kiểm tĩnh chart api-gateway (INF-08, DV-01-01): lint, render hai chế độ, kubeconform -strict kèm schema CRD APISIX,
# Kyverno CLI với chính sách nền của cell, luacheck cú pháp plugin vt-claims, đối chiếu
# bảng tuyến với hợp đồng OpenAPI, các ca phải thất bại. Chạy APISIX thật: tests/kiem-thu-docker.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm lint . -f tests/values-ci.yaml >/dev/null && ok "helm lint" || hong "helm lint"
for ten in ci kind; do
  helm template g . -f "tests/values-$ten.yaml" > "$OUT/$ten.yaml" && ok "render values-$ten" || hong "render values-$ten"
  kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/$ten.yaml" > "$OUT/kc" 2>&1 \
    && ok "kubeconform -strict values-$ten ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $ten"; cat "$OUT/kc"; }
done
kyverno apply ../cell-base/tests/kyverno-co-so.yaml --resource "$OUT/ci.yaml" > "$OUT/ky" 2>&1 \
  && ok "Kyverno (cụm thật): $(grep -o 'pass: [0-9]*, fail: [0-9]*' "$OUT/ky")" || { hong Kyverno; tail -20 "$OUT/ky"; }
for k in ApisixRoute ApisixPluginConfig ApisixGlobalRule ApisixTls StatefulSet; do
  grep -q "^kind: $k" "$OUT/ci.yaml" && ok "chế độ controller có $k" || hong "thiếu $k"
done
grep -q '/kpi-legacy' "$OUT/ci.yaml" && hong "còn tuyến Strangler /kpi-legacy" || ok "không có tuyến Strangler /kpi-legacy"
grep -q 'ssl_protocols: "TLSv1.3"' "$OUT/ci.yaml" && ok "TLS 1.3" || hong "TLS"
grep -qF 'regex_uri: ["^/api/v1/iam/(.*)$", "/realms/mission/mission-iam/$1"]' "$OUT/ci.yaml" && ok "tuyến /api/v1/iam/* đổi sang SPI mission-iam của Keycloak (DV-02-08)" || hong "tuyến mission-iam"
grep -q 'read: 10s' "$OUT/ci.yaml" && ok "tuyến mission-core timeout đọc 10 s" || hong "timeout"
grep -A1 'algorithms:' "$OUT/ci.yaml" | grep -q -- '- ES256' && grep -q 'verify: true' "$OUT/ci.yaml" && ok "protected-api kiểm JWT ES256 qua JWKS" || hong "kiểm JWT ES256"
grep -q 'route_cookie:' "$OUT/ci.yaml" && grep -q 'name: VT_ROUTE_COOKIE_KEY' "$OUT/ci.yaml" && ok "kiểm cookie định tuyến HMAC, khoá từ Secret (DV-01-04)" || hong "cookie định tuyến"
grep -q 'tenant_claim: tenant_id' "$OUT/ci.yaml" && ok "claim tenant cấu hình từ values (tenant_id)" || hong "claim tenant"
python3 tests/doi-chieu-hop-dong.py --rendered "$OUT/ci.yaml" > "$OUT/dc" 2>&1 && ok "$(tail -1 "$OUT/dc")" || { hong "đối chiếu hợp đồng"; cat "$OUT/dc"; }
# DV-01-03: tuyến không xác thực chỉ từ public-routes.yaml, tệp này khớp hợp đồng; fixture âm tính phải bị bắt.
python3 ../../../scripts/kiem-tuyen-cong-khai.py > "$OUT/ck" 2>&1 && ok "$(tail -1 "$OUT/ck")" || { hong "tuyến công khai"; cat "$OUT/ck"; }
if python3 ../../../scripts/kiem-tuyen-cong-khai.py --rendered tests/fixtures/tuyen-khong-xac-thuc.yaml > "$OUT/ck" 2>&1; then
  hong "fixture tuyến không xác thực: lẽ ra phải hỏng"
elif [ "$(grep -c 'lo-khong-xac-thuc\|cong-khai-tien-to\|protected-rong' "$OUT/ck")" -eq 3 ]; then
  ok "fixture âm tính: kiểm tra hỏng và nêu đủ ba tuyến sai"
else hong "fixture âm tính: không nêu tên tuyến"; cat "$OUT/ck"; fi
if command -v luac >/dev/null || command -v luac5.1 >/dev/null; then
  $(command -v luac || command -v luac5.1) -p files/vt-claims.lua && ok "cú pháp Lua vt-claims" || hong "cú pháp Lua"
fi
phai_hong() { local mota=$1 mau=$2; shift 2
  if helm template g . "$@" >/dev/null 2>"$OUT/e"; then hong "$mota: lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/e"; then ok "$mota bị từ chối"; else hong "$mota: thông báo không rõ"; cat "$OUT/e"; fi; }
phai_hong "ảnh Coraza thiếu digest" "SEC-55"
phai_hong "mode lạ" "mode chỉ nhận" -f tests/values-ci.yaml --set mode=abc
phai_hong "verifier lạ" "oidc.verifier chỉ nhận" -f tests/values-ci.yaml --set oidc.verifier=jwt-auth
phai_hong "thuật toán ký lạ" "signingAlg" -f tests/values-ci.yaml --set oidc.signingAlg=HS256
# Giới hạn tần suất, lớp ưu tiên, cắt tải, WAF (DV-01-02).
tests/kiem-thu-gioi-han.sh > "$OUT/gh" 2>&1 && ok "$(tail -1 "$OUT/gh")" || { hong "kiem-thu-gioi-han.sh"; grep HỎNG "$OUT/gh"; }
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

#!/usr/bin/env bash
# Kiểm tĩnh chart observability-mgmt (INF-14): lint, render bản cụm thật và kind, kubeconform -strict, lưu giữ
# Tempo 14 ngày, Loki 90 ngày, Thanos raw 30 ngày / 5 phút 180 ngày / 1 giờ 2 năm, cờ thay AGPL.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm dependency build . >/dev/null
helm lint . -f tests/values-ci.yaml >/dev/null && ok "helm lint" || hong "helm lint"
for ten in ci kind; do
  helm template om . -n observability -f "tests/values-$ten.yaml" > "$OUT/$ten.yaml" && ok "render $ten" || hong "render $ten"
  kubeconform -strict -summary -ignore-missing-schemas "$OUT/$ten.yaml" > "$OUT/kc" 2>&1 \
    && ok "kubeconform -strict $ten ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $ten"; tail -5 "$OUT/kc"; }
done
f="$OUT/ci.yaml"
grep -q 'block_retention: 336h' "$f" && ok "Tempo giữ 14 ngày" || hong "lưu giữ Tempo"
grep -q 'retention_period: 2160h' "$f" && ok "Loki giữ nóng 90 ngày" || hong "lưu giữ Loki"
grep -q 'min-age' "$f" && grep -q 'loki-chunks-cold' "$f" && ok "CronJob chép khối Loki sang bucket lạnh" || hong "chép lạnh"
grep -q 'retention.resolution-raw=30d' "$f" && grep -q 'retention.resolution-5m=180d' "$f" && grep -q 'retention.resolution-1h=2y' "$f" \
  && ok "Thanos Compactor raw 30 ngày, 5 phút 180 ngày, 1 giờ 2 năm" || hong "lưu giữ Thanos"
if helm template om . -f tests/values-ci.yaml --set agplThayThe=true >/dev/null 2>"$OUT/e"; then hong "agplThayThe không chặn AGPL"
else grep -q AGPL "$OUT/e" && ok "agplThayThe=true từ chối khi còn bật Grafana, Loki, Tempo" || hong "thông báo agplThayThe"; fi
helm template om . -f tests/values-ci.yaml --set agplThayThe=true --set tempo-distributed.enabled=false --set loki.enabled=false \
  --set grafana.enabled=false >/dev/null && ok "agplThayThe=true render được khi tắt thành phần AGPL" || hong "agplThayThe tắt AGPL"
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

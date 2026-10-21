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
echo "== Luật dài hạn, Alertmanager, dashboard (INF-15)"
promtool check rules rules/*.yaml > "$OUT/pt" 2>&1 && ok "promtool check rules dài hạn và NG" || { hong "promtool check rules"; cat "$OUT/pt"; }
(cd rules/tests && promtool test rules dai-han-nang-luc.test.yaml) > "$OUT/pt" 2>&1 && ok "promtool test rules dai-han-nang-luc.test.yaml" || { hong "promtool test"; tail -20 "$OUT/pt"; }
alertmanager/tests/chay-kiem-thu.sh > "$OUT/am" 2>&1 && ok "Alertmanager: $(tail -1 "$OUT/am")" || { hong "Alertmanager"; grep HỎNG "$OUT/am"; }
dashboards/dung.sh --kiem-tra && ok "dashboards/json khớp mã Grafonnet" || hong "dashboards/json lệch"
for d in dashboards/json/*.json; do python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["uid"] and d["panels"]' "$d" || hong "JSON $d"; done
grep -q 'name: thanos-rule$' "$f" && grep -q 'name: alertmanager$' "$f" && ok "render có Thanos Ruler và Alertmanager" || hong "Thanos Ruler, Alertmanager"
[ "$(grep -c '^  [a-z-]*\.json: |' "$OUT/kind.yaml")" = 4 ] && ok "ConfigMap vt-grafana-dashboards có 4 dashboard" || hong "ConfigMap dashboard"
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

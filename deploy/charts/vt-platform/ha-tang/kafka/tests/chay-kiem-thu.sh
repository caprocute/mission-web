#!/usr/bin/env bash
# Kiểm thử tĩnh chart kafka (INF-10): danh mục khớp hợp đồng sự kiện, pytest bộ sinh và bản render, helm lint,
# kubeconform -strict với lược đồ CRD Strimzi 1.2.0 cục bộ ở bốn hồ sơ định cỡ. Cần helm, kubeconform, python3 + PyYAML, pytest.
set -euo pipefail
CHART=$(cd "$(dirname "$0")/.." && pwd)
VTP=$(cd "$CHART/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "${OUT:?}"' EXIT
dat=0; hong=0
ok() { echo "  [ĐẠT] $*"; dat=$((dat + 1)); }
loi() { echo "  [HỎNG] $*"; hong=$((hong + 1)); }
if python3 "$CHART/sinh_danh_muc.py" --kiem; then ok "danh mục khớp contracts/events"; else loi "danh mục lệch, chạy lại sinh_danh_muc.py"; fi
if python3 -m pytest -q -p no:cacheprovider "$CHART/tests" > "$OUT/py.log" 2>&1; then ok "pytest ($(tail -1 "$OUT/py.log"))"; else loi "pytest"; tail -30 "$OUT/py.log"; fi
if helm lint "$CHART" > "$OUT/lint.log" 2>&1; then ok "helm lint"; else loi "helm lint"; cat "$OUT/lint.log"; fi
for hs in compact S M L; do
  f="$OUT/$hs.yaml"
  # Tệp hồ sơ là values của vt-platform; lấy nhánh kafka và global cho chart con.
  python3 - "$VTP/sizing/values-sizing-$(echo "$hs" | tr 'SML' 'sml').yaml" "$OUT/v-$hs.yaml" <<'PY'
import sys, yaml
v = yaml.safe_load(open(sys.argv[1]))
yaml.safe_dump({**(v.get("kafka") or {}), "global": v.get("global", {})}, open(sys.argv[2], "w"))
PY
  if helm template t "$CHART" -f "$OUT/v-$hs.yaml" > "$f" 2> "$OUT/tpl.log" \
     && kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
          -schema-location "$VTP/ci/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
          -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
          "$f" > "$OUT/kc.log" 2>&1; then
    ok "hồ sơ $hs: $(grep -c '^kind:' "$f") đối tượng, kubeconform -strict ($(tail -1 "$OUT/kc.log"))"
  else
    loi "hồ sơ $hs"; cat "$OUT/tpl.log"; grep -v '^Summary' "$OUT/kc.log" 2>/dev/null | head -20 || true
  fi
done
echo; echo "Tổng: $dat đạt, $hong hỏng"
[ "$hong" -eq 0 ]

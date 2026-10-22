#!/usr/bin/env bash
# Bước CI định cỡ của kho GitOps (INF-03): render vt-platform với bốn hồ sơ TL-05 và values của từng cell có tệp
# values.yaml, kiểm hồ sơ compact sinh đúng hai cụm CNPG, chạy pytest, kiểm ngân sách kết nối mọi cell (bảng Markdown
# vào báo cáo CI, trả mã khác 0 khi vi phạm) và in bảng tính lại định cỡ. Cần helm, python3 + PyYAML, pytest.
# Dùng: deploy/capacity/chay-kiem-thu.sh [thư mục báo cáo]
set -uo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../.. && pwd); PF="$GOC/deploy/charts/vt-platform"
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
BC=${1:-$W}; mkdir -p "$BC"
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
cnpg() { grep -c '^kind: Cluster$' "$1"; }

[ -d "$PF/charts" ] || (cd "$PF" && helm dependency build . >/dev/null)
for h in compact s m l; do
  helm template t "$PF" -f "$PF/values-saas.yaml" -f "$PF/sizing/values-sizing-$h.yaml" -f "$PF/ci/values-ci.yaml" \
    > "$W/$h.yaml" 2> "$W/loi" && ok "render hồ sơ $h ($(cnpg "$W/$h.yaml") cụm CNPG)" || { hong "render hồ sơ $h"; tail -3 "$W/loi"; }
done
[ "$(cnpg "$W/compact.yaml")" = 2 ] && ok "compact gộp thành pg-core và pg-ledger" || hong "compact không ra hai cụm CNPG"
for d in ../cells/*/; do
  c=$(basename "$d"); [ -f "$d/values.yaml" ] || continue
  h=$(python3 -c "import yaml,sys; print(yaml.safe_load(open(sys.argv[1]))['cell']['sizing'].lower())" "$d/cell.yaml")
  helm template t "$PF" -f "$PF/values-saas.yaml" -f "$PF/sizing/values-sizing-$h.yaml" -f "$d/values.yaml" \
    -f "$PF/ci/values-ci.yaml" > "$W/cell-$c.yaml" 2> "$W/loi" && ok "render cell $c (hồ sơ $h)" || { hong "render cell $c"; tail -3 "$W/loi"; }
done
python3 -m pytest -q tests > "$W/pt" 2>&1 && ok "pytest ($(tail -1 "$W/pt"))" || { hong "pytest"; tail -30 "$W/pt"; }
python3 kiem_ngan_sach_ket_noi.py --tat-ca --bao-cao "$BC/ngan-sach-ket-noi.md" > "$W/ns" 2>&1 \
  && ok "ngân sách kết nối mọi cell ($(tail -2 "$W/ns" | head -1))" || { hong "ngân sách kết nối"; grep -B2 'VI PHẠM' "$W/ns"; }
python3 tinh_dinh_co.py --bao-cao "$BC/dinh-co.md" > "$W/td" 2>&1 && ok "tính lại định cỡ ($(tail -1 "$W/td"))" || { hong "tính định cỡ"; tail "$W/td"; }

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]

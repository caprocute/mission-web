#!/usr/bin/env bash
# Cài Mission từ gói cách ly (INF-23): đổi khoá kiểm chữ ký của Kyverno sang khoá công khai phát hành, áp chính sách,
# rồi cài chart vt-platform từ thư mục charts/ của gói với values-airgap.yaml và values của khách hàng. Ảnh được trỏ
# sang Harbor nội bộ bằng doi-registry.py theo bảng anh-da-nap.tsv của load-images.sh, không sửa chart. Không dùng
# --post-renderer của Helm 3 vì nó bỏ qua manifest hook (Job nạp realm, webhook-certgen); thay vào đó render bằng
# helm template rồi áp bằng kubectl apply --server-side, giống cách Argo CD dựng chart ở SaaS (hook thành tài nguyên thường). Chạy sau kiem-goi.sh và load-images.sh.
# Cách gọi: tools/cai-dat.sh --kho harbor.qp.local/okr --khoa vt-release.pub --values khach-hang.yaml
#           [--registry-goc harbor.vt.local/okr] [--namespace mission] [--chi-render <tệp>]
set -euo pipefail
GOI=$(cd "$(dirname "$0")/.." && pwd); KHO=""; KHOA=""; VALUES=(); GOC_REG=harbor.vt.local/okr; NS=mission; RENDER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --kho) KHO=${2%/}; shift 2 ;;
    --khoa) KHOA=$2; shift 2 ;;
    --values) VALUES+=(-f "$2"); shift 2 ;;
    --registry-goc) GOC_REG=${2%/}; shift 2 ;;
    --namespace) NS=$2; shift 2 ;;
    --chi-render) RENDER=$2; shift 2 ;;
    *) echo "cai-dat: tham số lạ: $1" >&2; exit 2 ;;
  esac
done
[ -n "$KHO" ] && [ -f "$KHOA" ] || { echo "cai-dat: cần --kho và --khoa" >&2; exit 2; }
[ -f "$GOI/../anh-da-nap.tsv" ] || { echo "cai-dat: chưa nạp ảnh (chạy tools/load-images.sh trước)" >&2; exit 2; }
CHART=$(ls "$GOI"/charts/vt-platform-*.tgz)
mkdir -p "$GOI/../kyverno-khach-hang"
python3 "$GOI/tools/doi-khoa-kyverno.py" --khoa "$KHOA" --registry-goc "${GOC_REG%%/*}" --kho "${KHO%%/*}" \
  --ra "$GOI/../kyverno-khach-hang" "$GOI"/tools/kyverno/vt-*.yaml
helm template vt "$CHART" -n "$NS" --include-crds -f "$GOI/docs/values-airgap.yaml" "${VALUES[@]}" \
  | python3 "$GOI/tools/doi-registry.py" "$GOI/../anh-da-nap.tsv" > "${RENDER:-$GOI/../vt-platform-khach-hang.yaml}"
if [ -n "$RENDER" ]; then echo "cai-dat: đã render ra $RENDER (không áp lên cụm)"; exit 0; fi
kubectl apply --server-side -f "$GOI/../kyverno-khach-hang"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply --server-side -n "$NS" -f "$GOI/../vt-platform-khach-hang.yaml"
echo "cai-dat: đã áp; theo dõi pod rồi chạy tools/kiem-tra-sau-cai.sh"

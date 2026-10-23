#!/usr/bin/env bash
# Nạp ảnh và chữ ký của gói vào Harbor nội bộ của khách hàng (INF-23; TL-04 mục 9.9 tools/load-images.sh).
# Quy ước tên: <kho>/<đường dẫn ảnh gốc bỏ tên máy chủ registry và --bo-tien-to>; ví dụ với --kho harbor.qp.local/okr
# và --bo-tien-to okr/, ảnh harbor.vt.local/okr/mission-core@sha256:… thành harbor.qp.local/okr/mission-core@sha256:….
# Digest giữ nguyên nên chữ ký cosign vẫn khớp; script kiểm lại digest sau khi nạp. Luôn chạy tools/kiem-goi.sh trước.
# Cách gọi: tools/load-images.sh --kho <registry/dự án> [--bo-tien-to <tiền tố>] [--plain-http]
set -euo pipefail
GOI=$(cd "$(dirname "$0")/.." && pwd); KHO=""; BO=""; HTTP=(); RHTTP=(); CHTTP=()
while [ $# -gt 0 ]; do
  case "$1" in
    --kho) KHO=${2%/}; shift 2 ;;
    --bo-tien-to) BO=$2; shift 2 ;;
    --plain-http) HTTP=(--to-plain-http); RHTTP=(--plain-http); CHTTP=(--allow-insecure-registry --allow-http-registry); shift ;;
    *) echo "load-images: tham số lạ: $1" >&2; exit 2 ;;
  esac
done
[ -n "$KHO" ] || { echo "load-images: cần --kho" >&2; exit 2; }
SO=0
while IFS=$'\t' read -r the anh; do
  duong=${anh%@*}; dau=${duong%%/*}
  # Thành phần đầu là máy chủ registry khi có dấu chấm, dấu hai chấm hoặc là localhost (quy tắc của Docker).
  if [[ "$duong" == */* && ( "$dau" == *.* || "$dau" == *:* || "$dau" == localhost ) ]]; then duong=${duong#*/}; fi
  duong=${duong#"$BO"}
  digest=${anh#*@}
  dich="$KHO/$duong"
  oras copy "${HTTP[@]}" --from-oci-layout "$GOI/images:$the" "$dich:$the" >/dev/null
  thuc=$(oras resolve "${RHTTP[@]}" "$dich:$the")
  [ "$thuc" = "$digest" ] || { echo "load-images: $dich digest $thuc khác $digest, dừng" >&2; exit 1; }
  cosign load "${CHTTP[@]}" --dir "$GOI/signatures/$the" "$dich" >/dev/null
  printf '%s\t%s@%s\n' "$anh" "$dich" "$digest"
  SO=$((SO+1))
done < "$GOI/images/anh.tsv" > "$GOI/../anh-da-nap.tsv"
echo "load-images: đã nạp $SO ảnh vào $KHO (bảng đối chiếu ../anh-da-nap.tsv)"

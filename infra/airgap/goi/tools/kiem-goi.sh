#!/usr/bin/env bash
# Kiểm gói cài cách ly phía khách hàng (INF-23, SEC-45): chữ ký bản kê bằng khoá công khai phát hành, rồi băm SHA-256
# của mọi tệp, và không có tệp nào nằm ngoài bản kê. Sai bất kỳ điều gì thì thoát khác 0, các bước nạp phải dừng.
# Cách gọi: tools/kiem-goi.sh --khoa vt-release.pub [--goi <thư mục gói>]
set -euo pipefail
GOI=$(cd "$(dirname "$0")/.." && pwd); KHOA=""
while [ $# -gt 0 ]; do
  case "$1" in
    --khoa) KHOA=$2; shift 2 ;;
    --goi) GOI=$(cd "$2" && pwd); shift 2 ;;
    *) echo "kiem-goi: tham số lạ: $1" >&2; exit 2 ;;
  esac
done
[ -f "$KHOA" ] || { echo "kiem-goi: cần --khoa <khoá công khai phát hành>" >&2; exit 2; }
cd "$GOI"
cosign verify-blob --insecure-ignore-tlog=true --key "$KHOA" --signature MANIFEST.sha256.sig MANIFEST.sha256 >/dev/null 2>&1 \
  || { echo "kiem-goi: CHỮ KÝ BẢN KÊ KHÔNG HỢP LỆ, dừng" >&2; exit 1; }
sha256sum -c MANIFEST.sha256 --quiet --strict || { echo "kiem-goi: TỆP BỊ SỬA HOẶC THIẾU, dừng" >&2; exit 1; }
LA=$(comm -23 <(find . -type f ! -name 'MANIFEST*' | sort) <(awk '{print $2}' MANIFEST.sha256 | sort))
[ -z "$LA" ] || { echo "kiem-goi: TỆP LẠ NGOÀI BẢN KÊ, dừng:" >&2; echo "$LA" >&2; exit 1; }
echo "kiem-goi: đạt ($(wc -l < MANIFEST.sha256) tệp, bản $(cat PHIEN-BAN))"

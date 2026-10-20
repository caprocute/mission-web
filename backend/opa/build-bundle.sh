#!/usr/bin/env bash
# Kiểm tra, đóng gói và (tuỳ chọn) ký bundle chính sách okrkpi (NEN-040, ADR-21).
#
# Dùng:
#   OPA=/duong-dan/opa backend/opa/build-bundle.sh <thư-mục-ra> [khoá-ký-riêng.pem]
#
# Khoá ký ở môi trường thật lấy từ OpenBao lúc chạy pipeline, không nằm trong repo; kiểm thử dùng khoá tạm
# sinh ngoài repo (xem backend/opa/README.md). Không truyền khoá thì bundle không ký (chỉ dùng ở máy dev).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OPA="${OPA:-opa}"
OUT="${1:?Thiếu thư mục ra}"
KEY="${2:-}"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# 1. Dữ liệu sinh ra phải khớp ma trận (docs/phan-quyen/ma-tran.json)
python3 "$ROOT/docs/phan-quyen/gen_matrix.py" --rego-check

# 2. Định dạng, kiểm tra tĩnh, kiểm thử với ngưỡng độ phủ 90 %
"$OPA" fmt --fail --list "$HERE"
"$OPA" check --strict "$HERE"
"$OPA" test "$HERE" --coverage --threshold 90 > "$OUT/coverage.json"

# 3. Đóng gói: chỉ chính sách và dữ liệu, bỏ kiểm thử, công cụ và tài liệu
REVISION="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"
# Chạy trong thư mục gói để đường dẫn trong bundle là tương đối (okrkpi/authz.rego)
cd "$HERE"
ARGS=(build -b . -o "$OUT/okrkpi-bundle.tar.gz" --revision "$REVISION"
	--ignore '*_test.rego' --ignore tools --ignore '*.md' --ignore '*.sh')
if [[ -n "$KEY" ]]; then
	ARGS+=(--signing-key "$KEY" --signing-alg RS256)
fi
"$OPA" "${ARGS[@]}"

echo "Bundle: $OUT/okrkpi-bundle.tar.gz (revision $REVISION$([[ -n "$KEY" ]] && echo ', đã ký RS256'))"

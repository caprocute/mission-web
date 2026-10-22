#!/usr/bin/env bash
# Biên dịch dashboard Grafonnet ra JSON (INF-15). Cần jsonnet và jb (jsonnet-bundler) trong PATH.
# Dùng: dashboards/dung.sh            → ghi json/*.json
#       dashboards/dung.sh --kiem-tra → chỉ so với json/ đã commit, lệch thì trả mã lỗi
set -euo pipefail
cd "$(dirname "$0")"
[ -d vendor ] || jb install >/dev/null
OUT=json; [ "${1:-}" = --kiem-tra ] && OUT=$(mktemp -d)
for f in src/*.jsonnet; do
  jsonnet -J vendor "$f" > "$OUT/$(basename "${f%.jsonnet}").json"
done
if [ "$OUT" != json ]; then
  diff -r json "$OUT" >/dev/null || { echo "json/ lệch với src/: chạy dashboards/dung.sh rồi commit" >&2; exit 1; }
fi

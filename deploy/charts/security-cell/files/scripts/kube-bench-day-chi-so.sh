#!/bin/sh
# Đổi kết quả JSON của kube-bench thành chỉ số Prometheus và đẩy lên Pushgateway (SEC-27, INF-19).
# Biến: KQ (tệp JSON kube-bench), NODE_NAME, TARGET, PUSHGATEWAY (rỗng thì chỉ in chỉ số), CELL.
# Chỉ số: kube_bench_checks{result="pass|fail|warn|info"} và kube_bench_last_run_timestamp_seconds, nhóm theo
# job=kube-bench, node, target trên Pushgateway. Cảnh báo KubeBenchFail (rules/an-ninh-nen.yaml) khi fail > 0.
set -eu
KQ=${KQ:-/ket-qua/kube-bench.json}
: "${NODE_NAME:?thiếu NODE_NAME}"
: "${TARGET:?thiếu TARGET}"
if [ ! -s "$KQ" ]; then
  echo "kube-bench không sinh kết quả ($KQ)" >&2
  exit 1
fi
# Gộp mọi khối Totals (một khối mỗi nhóm kiểm tra) bằng awk; không cần jq trong ảnh busybox.
tong=$(tr -d ' \n\t' < "$KQ" | grep -o '"Totals":{[^}]*}' | awk -F'[{}:,"]+' '
  { for (i = 1; i < NF; i++) { if ($i ~ /^total_(pass|fail|warn|info)$/) { s[$i] += $(i + 1) } } }
  END { printf "%d %d %d %d\n", s["total_pass"], s["total_fail"], s["total_warn"], s["total_info"] }')
read -r so_dat so_hong so_canh_bao so_thong_tin <<KQ
$tong
KQ
bay_gio=$(date +%s)
chi_so=/tmp/chi-so.prom
{
  echo "# HELP kube_bench_checks Số mục CIS theo kết quả ở lần chạy gần nhất."
  echo "# TYPE kube_bench_checks gauge"
  echo "kube_bench_checks{result=\"pass\"} $so_dat"
  echo "kube_bench_checks{result=\"fail\"} $so_hong"
  echo "kube_bench_checks{result=\"warn\"} $so_canh_bao"
  echo "kube_bench_checks{result=\"info\"} $so_thong_tin"
  echo "# HELP kube_bench_last_run_timestamp_seconds Thời điểm kube-bench chạy xong."
  echo "# TYPE kube_bench_last_run_timestamp_seconds gauge"
  echo "kube_bench_last_run_timestamp_seconds $bay_gio"
} > "$chi_so"
cat "$chi_so"
if [ -n "${PUSHGATEWAY:-}" ]; then
  wget -q -O /dev/null --header 'Content-Type: text/plain; version=0.0.4' --post-file="$chi_so" \
    "$PUSHGATEWAY/metrics/job/kube-bench/cell/${CELL:-khong-ro}/node/$NODE_NAME/target/$TARGET"
fi

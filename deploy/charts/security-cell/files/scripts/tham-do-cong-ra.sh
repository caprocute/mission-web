#!/bin/sh
# Thăm dò cổng ra (SEC-36, INF-19): từ namespace đang chạy thử mở kết nối TCP thẳng ra ngoài cụm tới các đích không
# có trong danh sách cổng ra. Kết nối được là rò rỉ (giá trị 1). Đẩy chỉ số lên Pushgateway theo job=egress-probe,
# namespace. Biến: TARGETS ("tên=máy:cổng" cách nhau bởi dấu cách), TIMEOUT, NAMESPACE, PUSHGATEWAY, CELL.
set -eu
: "${NAMESPACE:?thiếu NAMESPACE}"
: "${TARGETS:?thiếu TARGETS}"
TIMEOUT=${TIMEOUT:-5}
chi_so=/tmp/chi-so.prom
ro_ri=0
{
  echo "# HELP vt_egress_probe_reachable 1 khi pod thường trong namespace kết nối thẳng được tới đích ngoài danh sách cổng ra."
  echo "# TYPE vt_egress_probe_reachable gauge"
} > "$chi_so"
for muc in $TARGETS; do
  ten=${muc%%=*}
  dich=${muc#*=}
  may=${dich%:*}
  cong=${dich##*:}
  if nc -z -w "$TIMEOUT" "$may" "$cong" >/dev/null 2>&1; then
    kq=1
    ro_ri=$((ro_ri + 1))
    echo "RÒ RỈ: $NAMESPACE kết nối được $may:$cong ($ten)" >&2
  else
    kq=0
  fi
  echo "vt_egress_probe_reachable{target=\"$ten\"} $kq" >> "$chi_so"
done
{
  echo "# HELP vt_egress_probe_last_run_timestamp_seconds Thời điểm thăm dò cổng ra chạy xong."
  echo "# TYPE vt_egress_probe_last_run_timestamp_seconds gauge"
  echo "vt_egress_probe_last_run_timestamp_seconds $(date +%s)"
} >> "$chi_so"
cat "$chi_so"
if [ -n "${PUSHGATEWAY:-}" ]; then
  wget -q -O /dev/null --header 'Content-Type: text/plain; version=0.0.4' --post-file="$chi_so" \
    "$PUSHGATEWAY/metrics/job/egress-probe/cell/${CELL:-khong-ro}/namespace/$NAMESPACE"
fi
echo "Số luồng ra ngoài danh sách: $ro_ri"

# shellcheck shell=bash
# Hàm dùng chung của các job sao lưu (chart backup-cell, INF-17). Nạp bằng ". /cfg/chung.sh".
# NGAY (YYYY-MM-DD) và BAY_GIO (giây Unix) chỉ đặt khi kiểm thử để cố định ngày giờ.

# aws-cli trỏ tới kho sao lưu ceph-sn-bk.
s3() { aws --endpoint-url "$S3_ENDPOINT" --region "$S3_REGION" ${AWS_CA_BUNDLE:+--ca-bundle "$AWS_CA_BUNDLE"} "$@"; }
bay_gio() { echo "${BAY_GIO:-$(date +%s)}"; }
ngay_hien_tai() { echo "${NGAY:-$(date -d "@$(bay_gio)" +%Y-%m-%d)}"; }
so_ngay() { echo $(( $(date -u -d "$1" +%s) / 86400 )); }
# Đẩy chỉ số dạng văn bản Prometheus (stdin) lên Pushgateway, nhóm job/<job>/cell/<cell>[/<nhãn>/<giá trị>…].
# POST chỉ thay các chỉ số cùng tên, nên chỉ số *_last_success_* không gửi khi lỗi vẫn giữ giá trị cũ.
day_chi_so() {
  local duong="job/$1/cell/$CELL"; shift
  while [ $# -ge 2 ]; do duong="$duong/$1/$2"; shift 2; done
  if [ -z "${PUSHGATEWAY:-}" ]; then
    echo "Chưa đặt Pushgateway, chỉ số $duong:"; cat
    return 0
  fi
  curl -sS --fail --max-time 30 --data-binary @- "$PUSHGATEWAY/metrics/$duong"
}
# INF-18 · Gọi HTTP của ClickHouse (CH_URL, CH_USER, CH_PASSWORD_FILE, CH_CA): câu lệnh đọc từ stdin, kết quả TSV.
ch() {
  curl -sS --fail-with-body --max-time "${CH_THOI_GIAN:-300}" ${CH_CA:+--cacert "$CH_CA"} \
    -H "X-ClickHouse-User: $CH_USER" -H "X-ClickHouse-Key: $(cat "$CH_PASSWORD_FILE")" --data-binary @- "$CH_URL/"
}
# INF-18 · Gọi REST của OpenSearch (OS_URL, OS_USER, OS_PASSWORD_FILE, OS_CA): os_api <phương thức> <đường dẫn> [thân JSON].
os_api() {
  curl -sS --fail-with-body --max-time "${OS_THOI_GIAN:-3600}" ${OS_CA:+--cacert "$OS_CA"} -u "$OS_USER:$(cat "$OS_PASSWORD_FILE")" \
    -X "$1" -H 'Content-Type: application/json' ${3:+--data-binary "$3"} "$OS_URL$2"
}

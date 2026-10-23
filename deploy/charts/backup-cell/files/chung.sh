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

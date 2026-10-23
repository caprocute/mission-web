#!/usr/bin/env bash
# Chụp etcd mã hoá (INF-17, TL-04 mục 8.6): snapshot từ thành viên etcd của master đang chạy pod, kiểm bằng etcdutl,
# mã hoá age với recipient của OpenBao (kv/platform/backup/etcd, nạp qua ESO vào /recipient), đẩy lên
# s3://<bucket>/<tiền tố>/<máy>-<thời điểm>.db.age, xoá tệp rõ bằng shred. Snapshot chứa Secret nên là dữ liệu C2:
# tệp rõ chỉ nằm trong emptyDir của pod và bị xoá ngay sau khi mã hoá.
# Biến môi trường: ETCD_ENDPOINTS, PKI, TAM, BUCKET, PREFIX, RECIPIENT_FILE, S3_ENDPOINT, S3_REGION, PUSHGATEWAY, CELL.
set -euo pipefail
CFG=${CFG:-/cfg}
# shellcheck source=chung.sh
. "$CFG/chung.sh"
TAM=${TAM:-/tam}
may=${NODE_NAME:-$(hostname)}
moc=$(date -u -d "@$(bay_gio)" +%Y%m%dT%H%MZ)
ro="$TAM/etcd-$moc.db"
ma_hoa="$TAM/etcd-$moc.db.age"
dat=0
ket_thuc() {
  local ma=$?
  [ -f "$ro" ] && shred -u "$ro"
  rm -f "$ma_hoa"
  {
    echo "# TYPE backup_etcd_snapshot_success gauge"
    echo "backup_etcd_snapshot_success $dat"
    if [ "$dat" = 1 ]; then
      echo "# TYPE backup_etcd_snapshot_last_success_timestamp_seconds gauge"
      echo "backup_etcd_snapshot_last_success_timestamp_seconds $(bay_gio)"
    fi
  } | day_chi_so etcd-snapshot node "$may" || echo "Không đẩy được chỉ số" >&2
  exit "$ma"
}
trap ket_thuc EXIT

recipient=$(cat "$RECIPIENT_FILE")
[[ "$recipient" == age1* ]] || { echo "LỖI: recipient age không hợp lệ" >&2; exit 1; }
etcdctl --endpoints="$ETCD_ENDPOINTS" --cacert="$PKI/ca.crt" --cert="$PKI/server.crt" --key="$PKI/server.key" \
  snapshot save "$ro"
etcdutl snapshot status "$ro" -w table
age -r "$recipient" -o "$ma_hoa" "$ro"
shred -u "$ro"
khoa="$PREFIX/$may-$moc.db.age"
s3 s3 cp "$ma_hoa" "s3://$BUCKET/$khoa" --only-show-errors
echo "Đã đẩy s3://$BUCKET/$khoa ($(stat -c %s "$ma_hoa") byte)"
dat=1

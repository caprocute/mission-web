#!/usr/bin/env bash
# INF-18 · Sao lưu ngày minh chứng và biểu mẫu ký (TL-04 mục 8.4 "rclone sync --backup-dir chạy dưới tài khoản chỉ đọc
# nguồn", bảng lịch sao lưu): mỗi bucket nguồn của cell (zone master, tài khoản sao-luu-minh-chung chỉ đọc và liệt kê)
# được rclone sync sang s3://bk-cell-<mã>-ev/hien-tai/<bucket>/<tiền tố>; tệp ở đích bị thay hoặc mất ở nguồn chuyển
# sang thay-the/<ngày>/<bucket>/ thay vì bị ghi đè. Khoá nguồn theo nội dung (t/<tenant>/sha256/<ab>/<băm>) và có Object
# Lock nên so theo kích thước là đủ; --immutable làm job lỗi nếu một tệp đã sao lưu lại đổi nội dung ở nguồn. Khoá của
# cell trên kho sao lưu không có quyền xoá: tệp mất ở nguồn làm bước dời sang thay-the lỗi, job báo lỗi, bản sao giữ nguyên.
# Biến: BUCKETS ("<bucket>[:<tiền tố>] …"), BUCKET_DICH, NGUON_ENDPOINT, NGUON_REGION, NGUON_ACCESS_KEY_ID,
# NGUON_SECRET_ACCESS_KEY, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY (khoá đích), S3_ENDPOINT, S3_REGION, AWS_CA_BUNDLE,
# NGUON_CA_BUNDLE, RCLONE_THAM_SO (tham số thêm), CELL, PUSHGATEWAY.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${BUCKETS:?cần BUCKETS}" "${BUCKET_DICH:?cần BUCKET_DICH}"
ngay=$(ngay_hien_tai)

# Hai remote khai bằng biến môi trường của rclone (không có tệp cấu hình chứa khoá). Kiểm thử đặt *_TYPE=local.
export RCLONE_CONFIG_NGUON_TYPE=${RCLONE_CONFIG_NGUON_TYPE:-s3} RCLONE_CONFIG_DICH_TYPE=${RCLONE_CONFIG_DICH_TYPE:-s3}
if [ "$RCLONE_CONFIG_NGUON_TYPE" = s3 ]; then
  export RCLONE_CONFIG_NGUON_PROVIDER=Ceph RCLONE_CONFIG_NGUON_ENDPOINT="$NGUON_ENDPOINT" \
    RCLONE_CONFIG_NGUON_REGION="${NGUON_REGION:-}" RCLONE_CONFIG_NGUON_ACCESS_KEY_ID="$NGUON_ACCESS_KEY_ID" \
    RCLONE_CONFIG_NGUON_SECRET_ACCESS_KEY="$NGUON_SECRET_ACCESS_KEY" RCLONE_CONFIG_NGUON_NO_CHECK_BUCKET=true
fi
if [ "$RCLONE_CONFIG_DICH_TYPE" = s3 ]; then
  export RCLONE_CONFIG_DICH_PROVIDER=Ceph RCLONE_CONFIG_DICH_ENDPOINT="$S3_ENDPOINT" \
    RCLONE_CONFIG_DICH_REGION="${S3_REGION:-}" RCLONE_CONFIG_DICH_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    RCLONE_CONFIG_DICH_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" RCLONE_CONFIG_DICH_NO_CHECK_BUCKET=true
fi
# rclone nhận một bộ CA cho mọi remote: ghép CA của RGW cell và của kho sao lưu khi cả hai có.
ca=""
for f in ${NGUON_CA_BUNDLE:-} ${AWS_CA_BUNDLE:-}; do [ -r "$f" ] && ca="$ca$(cat "$f")"$'\n'; done
if [ -n "$ca" ]; then printf '%s' "$ca" > /tmp/ca-sao-luu.pem; export RCLONE_CA_CERT=/tmp/ca-sao-luu.pem; fi

read -r -a them <<< "${RCLONE_THAM_SO:-}"
loi=0 chi_so=""
for muc in $BUCKETS; do
  b=${muc%%:*} tt=""
  [ "$muc" != "$b" ] && tt=${muc#*:}
  bat_dau=$(date +%s)
  echo "== $b/$tt → $BUCKET_DICH/hien-tai/$b/$tt"
  if rclone sync "nguon:$b/$tt" "dich:$BUCKET_DICH/hien-tai/$b/$tt" \
      --backup-dir "dich:$BUCKET_DICH/thay-the/$ngay/$b/$tt" --immutable --size-only --fast-list \
      --stats-one-line --stats 0 --stats-log-level NOTICE "${them[@]}"; then
    kq=1
    chi_so="${chi_so}backup_evidence_sync_last_success_timestamp_seconds{bucket=\"$b\"} $(bay_gio)"$'\n'
  else
    kq=0 loi=1
    echo "LỖI: rclone sync $b thất bại (tệp đổi nội dung hoặc mất ở nguồn, hoặc lỗi kết nối)" >&2
  fi
  chi_so="${chi_so}backup_evidence_sync_success{bucket=\"$b\"} $kq"$'\n'
  chi_so="${chi_so}backup_evidence_sync_duration_seconds{bucket=\"$b\"} $(( $(date +%s) - bat_dau ))"$'\n'
done
printf '# TYPE backup_evidence_sync_success gauge\n%s' "$chi_so" | day_chi_so sao-luu-minh-chung
exit "$loi"

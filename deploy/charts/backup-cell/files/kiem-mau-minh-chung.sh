#!/usr/bin/env bash
# INF-18 · Kiểm mẫu bản sao lưu minh chứng (TL-04 bảng lịch sao lưu: "Lấy mẫu 1.000 đối tượng mỗi ngày, so SHA-256"):
# chia SO_MAU đều cho các tenant của cell, đọc mẫu phiên bản tệp từ evidence.object_version (SVC-08, bảng mà TL-04 gọi
# "evidence_version") bằng vai trò chỉ đọc ro_evidence, tải đúng bản sao ở s3://<BUCKET_DICH>/hien-tai/<bucket>/<khoá>
# trên kho sao lưu, tính SHA-256 và kích thước rồi so với giá trị đã ghi lúc nhận tệp. Bản sao thiếu hoặc lệch làm job
# lỗi và đẩy số lượng lên Pushgateway (cảnh báo EvidenceBackupSampleMismatch).
# Biến: TENANTS (uuid cách nhau bởi dấu phẩy hoặc cách), SO_MAU, BUCKET_DICH, PGHOST, PGPORT, PGDATABASE, PGUSER,
# PG_PASSWORD_FILE, PGSSLMODE, PGSSLROOTCERT, TAM (thư mục tải tạm), S3_ENDPOINT, S3_REGION, AWS_*, CELL, PUSHGATEWAY.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${TENANTS:?cần TENANTS}" "${BUCKET_DICH:?cần BUCKET_DICH}"
SO_MAU=${SO_MAU:-1000} TAM=${TAM:-/tam}
[ -n "${PG_PASSWORD_FILE:-}" ] && { PGPASSWORD=$(cat "$PG_PASSWORD_FILE"); export PGPASSWORD; }
ngay=$(ngay_hien_tai)
read -r -a ds <<< "${TENANTS//,/ }"
moi=$(( (SO_MAU + ${#ds[@]} - 1) / ${#ds[@]} ))

da_kiem=0 thieu=0 lech=0 loi_db=0
for t in "${ds[@]}"; do
  if ! mau=$(psql -X -q -At -F ' ' -v ON_ERROR_STOP=1 -v tenant="$t" -v ngay="$ngay" -v n="$moi" \
      -f "${CFG:-/cfg}/kiem-mau-minh-chung.sql"); then
    echo "LỖI: không đọc được mẫu của tenant $t" >&2; loi_db=1; continue
  fi
  while read -r bucket khoa bam co; do
    [ -n "$bucket" ] || continue
    da_kiem=$((da_kiem + 1))
    tep="$TAM/doi-tuong"
    if ! s3 s3 cp --only-show-errors "s3://$BUCKET_DICH/hien-tai/$bucket/$khoa" "$tep"; then
      echo "THIẾU: $bucket/$khoa (tenant $t) không có trong bản sao" >&2; thieu=$((thieu + 1)); rm -f "$tep"; continue
    fi
    thuc=$(sha256sum "$tep" | cut -d' ' -f1) co_thuc=$(stat -c %s "$tep")
    rm -f "$tep"
    if [ "$thuc" != "$bam" ] || [ "$co_thuc" != "$co" ]; then
      echo "LỆCH: $bucket/$khoa (tenant $t) băm $thuc kích thước $co_thuc, ghi nhận $bam $co" >&2; lech=$((lech + 1))
    fi
  done <<< "$mau"
done

echo "Đã kiểm $da_kiem bản sao: thiếu $thieu, lệch $lech"
{
  echo "# TYPE backup_evidence_sample_checked gauge"
  echo "backup_evidence_sample_checked $da_kiem"
  echo "backup_evidence_sample_missing $thieu"
  echo "backup_evidence_sample_mismatch $lech"
  echo "backup_evidence_sample_db_error $loi_db"
  echo "backup_evidence_sample_last_run_timestamp_seconds $(bay_gio)"
  if [ $((thieu + lech + loi_db)) -eq 0 ]; then echo "backup_evidence_sample_last_success_timestamp_seconds $(bay_gio)"; fi
} | day_chi_so kiem-mau-minh-chung
[ $((thieu + lech + loi_db)) -eq 0 ]

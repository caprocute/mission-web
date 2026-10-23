#!/usr/bin/env bash
# INF-18 · Sao lưu ClickHouse (TL-04 mục 8.8, bảng lịch sao lưu): chủ nhật (hoặc khi chưa có bản đầy đủ trong 6 ngày)
# BACKUP DATABASE đầy đủ ra S3(bk_sn, 'full-<ngày>'), ngày khác bản gia tăng 'incr-<ngày>' với base_backup là bản đầy đủ
# gần nhất. Khoá S3 nằm trong tập hợp có tên của ClickHouse (Secret ESO), không trong câu lệnh. Lệnh chạy async rồi theo
# dõi system.backups tới khi xong. Giữ 5 tuần bằng Object Lock 35 ngày và vòng đời bucket bk-cell-<mã>-ch.
# Biến: CH_URL, CH_USER, CH_PASSWORD_FILE, CH_CA, CH_DB, CH_CLUSTER, CH_NAMED_COLLECTION, BUCKET, POLL_GIAY, HAN_GIAY,
# S3_ENDPOINT, S3_REGION, AWS_*, CELL, PUSHGATEWAY.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${BUCKET:?cần BUCKET}"
DB=${CH_DB:-analytics} CUM=${CH_CLUSTER:-analytics} NC=${CH_NAMED_COLLECTION:-bk_sn}
POLL=${POLL_GIAY:-30} HAN=${HAN_GIAY:-14400}
ngay=$(ngay_hien_tai)
thu=$(date -u -d "$ngay" +%u)

# Bản đầy đủ gần nhất đã hoàn tất: ClickHouse ghi tệp .backup cuối cùng, nên bản dở dang không được chọn làm nền.
full_gan=$(s3 s3api list-objects-v2 --bucket "$BUCKET" --prefix full- --output json \
  | jq -r '.Contents[]?.Key | select(endswith("/.backup")) | split("/")[0]' | sort | tail -1)
loai=incr base=""
if [ "$thu" = 7 ] || [ -z "$full_gan" ] || [ $(( $(so_ngay "$ngay") - $(so_ngay "${full_gan#full-}") )) -gt 6 ]; then
  loai=full
else
  base=$full_gan
fi
ten="$loai-$ngay"
cau="BACKUP DATABASE $DB ON CLUSTER '$CUM' TO S3($NC, '$ten') SETTINGS async = 1, compression_method = 'zstd'"
[ -n "$base" ] && cau="$cau, base_backup = S3($NC, '$base')"
echo "== $cau"

ket_qua=0
if id=$(printf '%s FORMAT TSV' "$cau" | ch | head -1 | cut -f1) && [ -n "$id" ]; then
  het=$(( $(date +%s) + HAN ))
  while :; do
    tt=$(printf "SELECT status, error FROM system.backups WHERE id = '%s' FORMAT TSV" "$id" | ch | head -1)
    case ${tt%%$'\t'*} in
      BACKUP_CREATED) ket_qua=1; echo "Đã tạo $ten"; break ;;
      CREATING_BACKUP|"") ;;
      *) echo "LỖI: $ten: $tt" >&2; break ;;
    esac
    if [ "$(date +%s)" -ge "$het" ]; then echo "LỖI: $ten quá $HAN giây" >&2; break; fi
    sleep "$POLL"
  done
else
  echo "LỖI: ClickHouse từ chối lệnh BACKUP" >&2
fi
{
  echo "# TYPE backup_clickhouse_success gauge"
  echo "backup_clickhouse_success{loai=\"$loai\"} $ket_qua"
  [ "$ket_qua" = 1 ] && echo "backup_clickhouse_last_success_timestamp_seconds{loai=\"$loai\"} $(bay_gio)"
  true
} | day_chi_so sao-luu-clickhouse
[ "$ket_qua" = 1 ]

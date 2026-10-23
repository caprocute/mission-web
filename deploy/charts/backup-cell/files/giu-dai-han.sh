#!/usr/bin/env bash
# Giữ bản nền cuối tháng 12 tháng và bản cuối năm 10 năm (INF-17, TL-04 bảng lịch sao lưu): chạy các ngày 28–31, chỉ
# làm việc vào ngày cuối tháng. Với mỗi cụm, lấy Backup CNPG đã hoàn tất trong 24 giờ qua, chép thư mục bản nền
# <cụm>/<cụm>/base/<backupId>/ cùng các tệp WAL từ beginWal tới endWal (và tệp .history) sang
# dai-han/<thang|nam>/<cụm>/… trong cùng bucket, rồi kéo dài hạn giữ COMPLIANCE từng đối tượng mới. Bản cuối năm
# của cụm trong CUM_NAM vào dai-han/nam/. Vòng đời bucket dọn dai-han/thang/ sau 367 ngày, dai-han/nam/ sau 3654
# ngày; khoá của cell không xoá được gì (SEC-44).
# Biến môi trường: CUMS ("<cụm>:<namespace> …"), CUM_NAM, GIU_THANG, GIU_NAM_NAM, BUCKET, S3_ENDPOINT, S3_REGION,
# PUSHGATEWAY, CELL.
set -euo pipefail
CFG=${CFG:-/cfg}
# shellcheck source=chung.sh
. "$CFG/chung.sh"

ngay=$(ngay_hien_tai)
if [ "$(date -d "$ngay +1 day" +%d)" != 01 ]; then echo "$ngay không phải ngày cuối tháng, không làm gì"; exit 0; fi
cuoi_nam=false; [ "$(date -d "$ngay" +%m)" = 12 ] && cuoi_nam=true
tu=$(( $(bay_gio) - 86400 ))
loi=0

liet_ke() { # <tiền tố> [--delimiter /]: in khoá đối tượng, mỗi dòng một khoá
  s3 s3api list-objects-v2 --bucket "$BUCKET" --prefix "$@" --output json | jq -r '.Contents[]?.Key'
}
chep() { # <khoá nguồn> <khoá đích> <hạn giữ RFC 3339>
  s3 s3 cp "s3://$BUCKET/$1" "s3://$BUCKET/$2" --only-show-errors
  s3 s3api put-object-retention --bucket "$BUCKET" --key "$2" --retention "Mode=COMPLIANCE,RetainUntilDate=$3"
}

for muc in $CUMS; do
  IFS=: read -r ten ns <<< "$muc"
  loai=thang; han=$(date -u -d "$ngay +$GIU_THANG months" +%Y-%m-%dT00:00:00Z)
  if $cuoi_nam && [[ " $CUM_NAM " == *" $ten "* ]]; then
    loai=nam; han=$(date -u -d "$ngay +$GIU_NAM_NAM years" +%Y-%m-%dT00:00:00Z)
  fi
  bk=$(kubectl -n "$ns" get backups.postgresql.cnpg.io -l "cnpg.io/cluster=$ten" -o json | jq -c --argjson tu "$tu" '
    [.items[] | select(.spec.method == "plugin" and .status.phase == "completed" and .status.stoppedAt != null
                       and (.status.stoppedAt | sub("\\.[0-9]+"; "") | fromdateiso8601) >= $tu)]
    | sort_by(.status.stoppedAt) | last // empty')
  if [ -z "$bk" ]; then echo "LỖI $ten: không có bản nền hoàn tất trong 24 giờ qua" >&2; loi=$((loi + 1)); continue; fi
  id=$(jq -r '.status.backupId // empty' <<< "$bk")
  dau=$(jq -r '.status.beginWal // empty' <<< "$bk")
  cuoi=$(jq -r '.status.endWal // empty' <<< "$bk")
  if [ -z "$id" ] || [ -z "$dau" ] || [ -z "$cuoi" ]; then
    echo "LỖI $ten: Backup thiếu backupId, beginWal hoặc endWal" >&2; loi=$((loi + 1)); continue
  fi
  goc="$ten/$ten"
  dich="dai-han/$loai/$ten"
  so=0
  while read -r k; do
    [ -n "$k" ] || continue
    chep "$k" "$dich/${k#"$goc"/}" "$han"; so=$((so + 1))
  done < <(liet_ke "$goc/base/$id/")
  [ "$so" -gt 0 ] || { echo "LỖI $ten: không thấy đối tượng của bản nền $id" >&2; loi=$((loi + 1)); continue; }
  while read -r k; do
    [ -n "$k" ] || continue
    ten_tep=${k##*/}; doan=${ten_tep:0:24}
    if [[ "$ten_tep" == *.history ]] || { [[ ! "$doan" < "$dau" ]] && [[ ! "$doan" > "$cuoi" ]]; }; then
      chep "$k" "$dich/${k#"$goc"/}" "$han"; so=$((so + 1))
    fi
  done < <({ liet_ke "$goc/wals/" --delimiter /; for thu_muc in $(printf '%s\n' "${dau:0:16}" "${cuoi:0:16}" | sort -u); do liet_ke "$goc/wals/$thu_muc/"; done; })
  echo "$ten: bản nền $id (WAL $dau..$cuoi), $so đối tượng sang $dich, giữ tới $han"
done

{
  echo "# TYPE backup_long_term_copy_success gauge"
  echo "backup_long_term_copy_success $([ "$loi" -eq 0 ] && echo 1 || echo 0)"
  echo "# TYPE backup_long_term_copy_last_run_timestamp_seconds gauge"
  echo "backup_long_term_copy_last_run_timestamp_seconds $(bay_gio)"
} | day_chi_so giu-dai-han || echo "Không đẩy được chỉ số" >&2
echo "xong, lỗi: $loi"
[ "$loi" -eq 0 ]

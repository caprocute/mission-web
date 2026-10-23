#!/usr/bin/env bash
# INF-18 · Khôi phục thử ClickHouse hằng tháng (TL-04 bảng lịch sao lưu): RESTORE bản mới nhất đã hoàn tất (đầy đủ hoặc
# gia tăng, kéo theo chuỗi nền) vào CSDL tạm analytics_khoi_phuc_thu trên cụm thật, so số dòng từng bảng MergeTree với
# CSDL gốc (bảng gốc có ít nhất NGUONG_DONG dòng phải còn ít nhất TI_LE × số dòng, vì gốc lớn lên sau bản sao lưu),
# rồi luôn xoá CSDL tạm. Kết quả đẩy lên Pushgateway (cảnh báo ClickHouseRestoreCheckStale).
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${BUCKET:?cần BUCKET}"
DB=${CH_DB:-analytics} CUM=${CH_CLUSTER:-analytics} NC=${CH_NAMED_COLLECTION:-bk_sn} TAM_DB=${CH_DB_TAM:-analytics_khoi_phuc_thu}
POLL=${POLL_GIAY:-30} HAN=${HAN_GIAY:-21600} NGUONG=${NGUONG_DONG:-1000} TI_LE=${TI_LE:-0.5}

ten=$(s3 s3api list-objects-v2 --bucket "$BUCKET" --output json \
  | jq -r '.Contents[]?.Key | select(endswith("/.backup")) | split("/")[0]' \
  | awk -F- '{ print substr($0, index($0, "-") + 1) " " $0 }' | sort | tail -1 | cut -d' ' -f2)
xoa_tam() { printf 'DROP DATABASE IF EXISTS %s ON CLUSTER %s SYNC' "$TAM_DB" "'$CUM'" | ch > /dev/null || true; }
ket_qua=0
if [ -z "$ten" ]; then
  echo "LỖI: chưa có bản sao lưu ClickHouse nào hoàn tất trong $BUCKET" >&2
else
  echo "== Khôi phục thử $ten vào $TAM_DB"
  xoa_tam
  trap xoa_tam EXIT
  id=$(printf "RESTORE DATABASE %s AS %s ON CLUSTER '%s' FROM S3(%s, '%s') SETTINGS async = 1 FORMAT TSV" \
    "$DB" "$TAM_DB" "$CUM" "$NC" "$ten" | ch | head -1 | cut -f1)
  het=$(( $(date +%s) + HAN )) tt=""
  while [ -n "$id" ]; do
    tt=$(printf "SELECT status, error FROM system.backups WHERE id = '%s' FORMAT TSV" "$id" | ch | head -1)
    case ${tt%%$'\t'*} in RESTORED|RESTORING|"") ;; *) break ;; esac
    [ "${tt%%$'\t'*}" = RESTORED ] && break
    [ "$(date +%s)" -ge "$het" ] && break
    sleep "$POLL"
  done
  if [ "${tt%%$'\t'*}" = RESTORED ]; then
    dem() { printf "SELECT name, total_rows FROM system.tables WHERE database = '%s' AND engine LIKE '%%MergeTree' ORDER BY name FORMAT TSV" "$1" | ch; }
    goc=$(dem "$DB") tam=$(dem "$TAM_DB")
    if sai=$(awk -F'\t' -v n="$NGUONG" -v r="$TI_LE" 'NR == FNR { t[$1] = $2; next }
        { if (!($1 in t)) { print "thiếu bảng " $1; next } if ($2 >= n && t[$1] < r * $2) print $1 ": " t[$1] " < " r " × " $2 }' \
        <(printf '%s\n' "$tam") <(printf '%s\n' "$goc")) && [ -z "$sai" ]; then
      ket_qua=1; echo "Khôi phục thử $ten đạt"
    else
      echo "LỖI: số dòng sau khôi phục không đạt:" >&2; printf '%s\n' "$sai" >&2
    fi
  else
    echo "LỖI: RESTORE $ten: ${tt:-không có mã tác vụ}" >&2
  fi
fi
{
  echo "# TYPE backup_clickhouse_restore_check_success gauge"
  echo "backup_clickhouse_restore_check_success $ket_qua"
  [ "$ket_qua" = 1 ] && echo "backup_clickhouse_restore_check_last_success_timestamp_seconds $(bay_gio)"
  true
} | day_chi_so khoi-phuc-thu-clickhouse
[ "$ket_qua" = 1 ]

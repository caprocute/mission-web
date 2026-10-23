#!/usr/bin/env bash
# QA-15: mô phỏng DR-10 (mất TTDL Hoà Lạc, chuyển vùng một cell sang phía Nam) và DR-16 (quay về) ở tầng
# PostgreSQL bằng hai cụm PostgreSQL cục bộ: "hl" là primary, "sn" là bản sao nhận luồng WAL bất đồng bộ và đọc
# thêm WAL từ kho lưu trữ (thay cho kho Barman, archive_timeout 60 s như TL-04 mục 8.11). Một tiến trình ghi giả
# lập ứng dụng ghi giao dịch kèm dòng outbox; nó chỉ ghi nhận một giao dịch vào nhật ký phía khách sau khi
# COMMIT trả về thành công, và luôn ghi vào cụm mà tệp "dang-hoat-dong" chỉ tới (thay cho active_site và dns-sync).
#
# Đo được: RPO (giao dịch đã xác nhận với khách nhưng không có ở phía Nam, và cửa sổ thời gian của chúng), RTO tính
# từ lúc quyết định (TL-04: quyết định là của IC; ở đây quyết định ngay hoặc sau --cho-quyet-dinh giây), thời gian
# gián đoạn ghi khi quay về có điều phối (mục tiêu dưới 5 phút, không mất dữ liệu).
# Không đo được ở đây (cần hai cụm thật hoặc kind, xem dr-chuyen-vung.sh): Kafka MirrorMaker 2, Ceph multisite,
# GeoDNS, Argo CD và ứng dụng thật. Không phải bài đo tải (YC-PT-19): tải ghi chỉ vài chục giao dịch mỗi giây.
#
# Dùng: mo-phong-pg-hai-ttdl.sh [--chay-truoc 20] [--cho-quyet-dinh 0] [--chay-phia-nam 20] [--wal-dut-truoc 0]
#                               [--cong-hl 55431] [--cong-sn 55432] [--bien-ban <tệp.json>] [--giu]
# Cần: PostgreSQL 14 trở lên (initdb, pg_ctl, pg_basebackup, pg_rewind, psql), chạy bằng root (cụm chạy dưới
# người dùng postgres) hoặc bằng chính người dùng postgres.
set -euo pipefail

CHAY_TRUOC=20; CHO_QD=0; CHAY_SN=20; WAL_DUT=0; CONG_HL=55431; CONG_SN=55432; BIEN_BAN=""; GIU=0
while [ $# -gt 0 ]; do
  case "$1" in
    --chay-truoc) CHAY_TRUOC=$2; shift 2 ;;
    --cho-quyet-dinh) CHO_QD=$2; shift 2 ;;
    --chay-phia-nam) CHAY_SN=$2; shift 2 ;;
    --wal-dut-truoc) WAL_DUT=$2; shift 2 ;;
    --cong-hl) CONG_HL=$2; shift 2 ;;
    --cong-sn) CONG_SN=$2; shift 2 ;;
    --bien-ban) BIEN_BAN=$2; shift 2 ;;
    --giu) GIU=1; shift ;;
    *) echo "mo-phong: tham số lạ $1" >&2; exit 2 ;;
  esac
done

PGBIN=${PGBIN:-$(find /usr/lib/postgresql -mindepth 2 -maxdepth 2 -name bin 2>/dev/null | sort -V | tail -1)}
[ -x "$PGBIN/pg_rewind" ] || { echo "mo-phong: không thấy PostgreSQL (đặt PGBIN)" >&2; exit 2; }
W=$(mktemp -d /tmp/dr-mo-phong.XXXXXX)
chmod 755 "$W"
if [ "$(id -u)" = 0 ]; then chown postgres: "$W"; PG() { su postgres -s /bin/bash -c "$*"; }; else PG() { bash -c "$*"; }; fi
export PGCONNECT_TIMEOUT=2 PGUSER=postgres PGDATABASE=postgres PGHOST=127.0.0.1

GHI_PID=""
don() {
  [ -n "$GHI_PID" ] && kill "$GHI_PID" 2>/dev/null || true
  for c in hl sn; do PG "$PGBIN/pg_ctl -D $W/$c -m immediate stop" >/dev/null 2>&1 || true; done
  if [ "$GIU" = 0 ]; then rm -rf "$W"; else echo "mo-phong: giữ thư mục $W"; fi
}
trap don EXIT

bay() { date +%s.%N; }                       # thời điểm, giây kèm phần lẻ
khoang() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }
buoc() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
q() { psql -p "$1" -qAtX -v ON_ERROR_STOP=1 -c "$2"; }
cong_cua() { [ "$1" = hl ] && echo "$CONG_HL" || echo "$CONG_SN"; }

cau_hinh_chung() {   # $1 thư mục cụm, $2 cổng
  cat >> "$1/postgresql.conf" <<CF
port = $2
listen_addresses = '127.0.0.1'
unix_socket_directories = '$W'
wal_level = replica
max_wal_senders = 10
wal_keep_size = '256MB'
hot_standby = on
archive_mode = always
archive_command = 'test ! -f $W/kho-wal/%f && cp %p $W/kho-wal/%f'
archive_timeout = 60
restore_command = 'cp $W/kho-wal/%f %p'
recovery_target_timeline = 'latest'
fsync = on
synchronous_commit = on
CF
  echo "host replication postgres 127.0.0.1/32 trust" >> "$1/pg_hba.conf"
}

cho_ghi_duoc() {   # $1 cổng, $2 giới hạn giây
  local i
  for ((i = 0; i < $2 * 10; i++)); do
    [ "$(q "$1" 'SELECT pg_is_in_recovery()' 2>/dev/null || true)" = f ] && return 0
    sleep 0.1
  done
  return 1
}

# ---- Dựng hai TTDL ------------------------------------------------------------------------------------------
buoc "Dựng primary Hoà Lạc (cổng $CONG_HL) và bản sao phía Nam (cổng $CONG_SN)"
PG "mkdir -p $W/kho-wal && $PGBIN/initdb -D $W/hl -U postgres --auth=trust -k -E UTF8 >/dev/null"
cau_hinh_chung "$W/hl" "$CONG_HL"
PG "$PGBIN/pg_ctl -D $W/hl -l $W/hl.log -w start >/dev/null"
q "$CONG_HL" "CREATE TABLE giao_dich (id bigint PRIMARY KEY, tao_luc timestamptz NOT NULL DEFAULT clock_timestamp());
               CREATE TABLE outbox_event (id bigserial PRIMARY KEY, giao_dich_id bigint NOT NULL, tao_luc timestamptz NOT NULL DEFAULT clock_timestamp());"
PG "$PGBIN/pg_basebackup -h 127.0.0.1 -p $CONG_HL -U postgres -D $W/sn -R -X stream -c fast >/dev/null"
PG "sed -i 's/^port = $CONG_HL/port = $CONG_SN/' $W/sn/postgresql.conf"
PG "$PGBIN/pg_ctl -D $W/sn -l $W/sn.log -w start >/dev/null"
[ "$(q "$CONG_SN" 'SELECT pg_is_in_recovery()')" = t ] || { echo "mo-phong: phía Nam không ở chế độ bản sao" >&2; exit 1; }

# ---- Tải ghi giả lập ứng dụng ---------------------------------------------------------------------------------
echo hl > "$W/dang-hoat-dong"
: > "$W/nhat-ky-khach.tsv"
ghi() {
  local id=0 dich
  while true; do
    if [ -f "$W/tam-dung" ]; then sleep 0.05; continue; fi
    dich=$(cat "$W/dang-hoat-dong")
    id=$((id + 1))
    if psql -p "$(cong_cua "$dich")" -qAtX -v ON_ERROR_STOP=1 \
         -c "BEGIN; INSERT INTO giao_dich (id) VALUES ($id); INSERT INTO outbox_event (giao_dich_id) VALUES ($id); COMMIT;" \
         >/dev/null 2>&1; then
      printf '%s\t%s\t%s\n' "$id" "$(bay)" "$dich" >> "$W/nhat-ky-khach.tsv"
    else
      printf 'LOI\t%s\t%s\n' "$(bay)" "$dich" >> "$W/nhat-ky-khach.tsv"
      id=$((id - 1)); sleep 0.2
    fi
  done
}
ghi & GHI_PID=$!
buoc "Ứng dụng giả lập ghi vào Hoà Lạc trong ${CHAY_TRUOC} s"
sleep "$CHAY_TRUOC"
TRE=$(q "$CONG_HL" "SELECT coalesce(round(extract(epoch FROM replay_lag) * 1000), 0) FROM pg_stat_replication LIMIT 1")
buoc "Trễ phát lại WAL ở phía Nam trước sự cố: ${TRE:-0} ms"

if [ "$WAL_DUT" != 0 ]; then
  buoc "Cắt luồng WAL trực tiếp sang phía Nam ${WAL_DUT} s trước sự cố: phía Nam chỉ còn kho WAL (archive_timeout 60 s)"
  q "$CONG_SN" "ALTER SYSTEM SET primary_conninfo = ''" && q "$CONG_SN" "SELECT pg_reload_conf()" >/dev/null
  sleep "$WAL_DUT"
fi

# ---- DR-10 ----------------------------------------------------------------------------------------------------
T_SU_CO=$(bay)
PG "$PGBIN/pg_ctl -D $W/hl -m immediate stop >/dev/null"
buoc "SỰ CỐ: mất Hoà Lạc (dừng đột ngột primary). IC quyết định sau ${CHO_QD} s"
sleep "$CHO_QD"
T_QD=$(bay)
if PG "$PGBIN/pg_ctl -D $W/hl status" >/dev/null 2>&1; then echo "mo-phong: primary cũ còn chạy, không promote (split-brain)" >&2; exit 1; fi
buoc "Rào chắn: xác nhận primary cũ không còn nhận ghi; promote phía Nam"
PG "$PGBIN/pg_ctl -D $W/sn -w promote >/dev/null"
cho_ghi_duoc "$CONG_SN" 60 || { echo "mo-phong: phía Nam không thăng cấp được" >&2; exit 1; }
T_PROMOTE=$(bay)
echo sn > "$W/dang-hoat-dong"
T_DNS=$(bay)
buoc "Đổi TTDL hoạt động của cell sang phía Nam (active_site); chờ lần ghi thành công đầu tiên"
for ((i = 0; i < 600; i++)); do
  T_GHI_LAI=$(awk -F'\t' -v t="$T_DNS" '$1 != "LOI" && $3 == "sn" && $2 >= t { print $2; exit }' "$W/nhat-ky-khach.tsv")
  [ -n "$T_GHI_LAI" ] && break
  sleep 0.1
done
[ -n "${T_GHI_LAI:-}" ] || { echo "mo-phong: không ghi được vào phía Nam" >&2; exit 1; }

# RPO: giao dịch khách đã nhận COMMIT ở Hoà Lạc nhưng không có ở phía Nam.
q "$CONG_SN" "SELECT id FROM giao_dich ORDER BY id" > "$W/co-o-sn.txt"
awk -F'\t' '$1 != "LOI" && $3 == "hl" { print $1 "\t" $2 }' "$W/nhat-ky-khach.tsv" > "$W/da-xac-nhan-hl.tsv"
MAT=$(awk -F'\t' 'NR == FNR { co[$1] = 1; next } !($1 in co) { n++ } END { print n + 0 }' "$W/co-o-sn.txt" "$W/da-xac-nhan-hl.tsv")
# Cửa sổ RPO: từ lần COMMIT cuối còn giữ được ở phía Nam tới lần COMMIT cuối bị mất (theo đồng hồ phía khách).
CUA_SO=$(awk -F'\t' 'NR == FNR { co[$1] = 1; next } ($1 in co) { giu = $2 } !($1 in co) { mat = $2 }
  END { if (mat == "") print 0; else printf "%.1f", mat - giu }' "$W/co-o-sn.txt" "$W/da-xac-nhan-hl.tsv")
OUTBOX=$(q "$CONG_SN" "SELECT count(*) FROM outbox_event WHERE tao_luc > now() - interval '72 hours'")
TONG_HL=$(wc -l < "$W/da-xac-nhan-hl.tsv")
buoc "RPO: mất $MAT / $TONG_HL giao dịch đã xác nhận, cửa sổ ${CUA_SO} s; RTO từ quyết định $(khoang "$T_QD" "$T_GHI_LAI") s"
buoc "Phát lại outbox 72 giờ ở phía Nam: $OUTBOX dòng (Debezium slot mới, consumer idempotent bỏ bản trùng)"
buoc "Chạy ở phía Nam ${CHAY_SN} s"
sleep "$CHAY_SN"

# ---- DR-16 ----------------------------------------------------------------------------------------------------
buoc "DR-16: dựng lại Hoà Lạc ở vai trò thứ cấp từ phía Nam (pg_rewind, primary_conninfo = phía Nam)"
T16_BAT_DAU=$(bay)
PG "$PGBIN/pg_rewind -D $W/hl --source-server='host=127.0.0.1 port=$CONG_SN user=postgres dbname=postgres' >/dev/null 2>&1"
PG "touch $W/hl/standby.signal"
echo "primary_conninfo = 'host=127.0.0.1 port=$CONG_SN user=postgres'" >> "$W/hl/postgresql.auto.conf"
PG "sed -i 's/^port = .*/port = $CONG_HL/' $W/hl/postgresql.conf"
PG "$PGBIN/pg_ctl -D $W/hl -l $W/hl.log -w start >/dev/null"
for ((i = 0; i < 600; i++)); do
  TRE16=$(q "$CONG_SN" "SELECT coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn), -1) FROM pg_stat_replication LIMIT 1" 2>/dev/null || true)
  [ -n "$TRE16" ] && [ "${TRE16%.*}" -ge 0 ] && [ "${TRE16%.*}" -lt 1048576 ] && break
  sleep 0.1
done
buoc "Hoà Lạc đã bắt kịp (độ lệch ${TRE16:-?} byte); chuyển có điều phối: dừng ghi, chờ khớp LSN, đảo vai trò"
T_DUNG_GHI=$(bay)
touch "$W/tam-dung"
sleep 0.3                                     # để lần ghi đang dở hoàn tất
LSN=$(q "$CONG_SN" "SELECT pg_current_wal_lsn()")
for ((i = 0; i < 600; i++)); do
  [ "$(q "$CONG_HL" "SELECT pg_last_wal_replay_lsn() >= '$LSN'::pg_lsn")" = t ] && break
  sleep 0.1
done
PG "$PGBIN/pg_ctl -D $W/sn -m fast -w stop >/dev/null"
PG "$PGBIN/pg_ctl -D $W/hl -w promote >/dev/null"
cho_ghi_duoc "$CONG_HL" 60 || { echo "mo-phong: Hoà Lạc không thăng cấp được" >&2; exit 1; }
echo hl > "$W/dang-hoat-dong"
rm -f "$W/tam-dung"
for ((i = 0; i < 600; i++)); do
  T_GHI_LAI16=$(awk -F'\t' -v t="$T_DUNG_GHI" '$1 != "LOI" && $3 == "hl" && $2 >= t { print $2; exit }' "$W/nhat-ky-khach.tsv")
  [ -n "$T_GHI_LAI16" ] && break
  sleep 0.1
done
# Phía Nam trở lại bản sao của Hoà Lạc (đã dừng sạch nên không cần pg_rewind).
PG "touch $W/sn/standby.signal"
echo "primary_conninfo = 'host=127.0.0.1 port=$CONG_HL user=postgres'" >> "$W/sn/postgresql.auto.conf"
PG "$PGBIN/pg_ctl -D $W/sn -l $W/sn.log -w start >/dev/null"
sleep 2
kill "$GHI_PID"; wait "$GHI_PID" 2>/dev/null || true; GHI_PID=""
T16_XONG=$(bay)
awk -F'\t' '$1 != "LOI" && ($3 == "sn" || $2 >= '"$T_DUNG_GHI"') { print $1 }' "$W/nhat-ky-khach.tsv" > "$W/sau-chuyen.txt"
q "$CONG_HL" "SELECT id FROM giao_dich ORDER BY id" > "$W/co-o-hl.txt"
MAT16=$(awk 'NR == FNR { co[$1] = 1; next } !($1 in co) { n++ } END { print n + 0 }' "$W/co-o-hl.txt" "$W/sau-chuyen.txt")
VT_HL=$(q "$CONG_HL" "SELECT pg_is_in_recovery()"); VT_SN=$(q "$CONG_SN" "SELECT pg_is_in_recovery()")
GIAN_DOAN=$(khoang "$T_DUNG_GHI" "$T_GHI_LAI16")
buoc "DR-16 xong: mất $MAT16 giao dịch, gián đoạn ghi ${GIAN_DOAN} s; Hoà Lạc primary=$([ "$VT_HL" = f ] && echo có || echo không), phía Nam bản sao=$([ "$VT_SN" = t ] && echo có || echo không)"

KQ=$(cat <<JS
{"phienBanPostgreSQL": "$(q "$CONG_HL" 'SHOW server_version')",
 "dr10": {"treWalTruocSuCoMs": ${TRE:-0}, "walDutTruocSuCoGiay": $WAL_DUT, "giaoDichDaXacNhanHl": $TONG_HL,
          "giaoDichMat": $MAT, "rpoGiay": $CUA_SO, "choQuyetDinhGiay": $CHO_QD,
          "promoteGiay": $(khoang "$T_QD" "$T_PROMOTE"), "rtoTuQuyetDinhGiay": $(khoang "$T_QD" "$T_GHI_LAI"),
          "rtoTuSuCoGiay": $(khoang "$T_SU_CO" "$T_GHI_LAI"), "outboxPhatLai72h": $OUTBOX},
 "dr16": {"giaoDichMat": $MAT16, "gianDoanGhiGiay": $GIAN_DOAN, "tongThoiGianGiay": $(khoang "$T16_BAT_DAU" "$T16_XONG"),
          "hoaLacLaPrimary": $([ "$VT_HL" = f ] && echo true || echo false), "phiaNamLaBanSao": $([ "$VT_SN" = t ] && echo true || echo false)}}
JS
)
echo "$KQ"
[ -n "$BIEN_BAN" ] && echo "$KQ" > "$BIEN_BAN"
# Đạt khi: không mất giao dịch lúc quay về, đảo vai trò đúng, RPO ≤ 900 s, RTO từ quyết định ≤ 14400 s.
[ "$MAT16" = 0 ] && [ "$VT_HL" = f ] && [ "$VT_SN" = t ] \
  && awk -v r="$CUA_SO" -v t="$(khoang "$T_QD" "$T_GHI_LAI")" 'BEGIN { exit !(r <= 900 && t <= 14400) }'

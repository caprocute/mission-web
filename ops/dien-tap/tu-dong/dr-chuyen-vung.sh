#!/usr/bin/env bash
# QA-15: kịch bản tự động phần dữ liệu của DR-10 (chuyển vùng một cell sang phía Nam) và DR-16 (quay về Hoà Lạc)
# trên hai cụm cell thật hoặc hai cụm kind, theo ops/runbooks/dr/dr-10.md và dr-16.md. Mọi tham số môi trường
# nằm ở tệp env (mẫu: moi-truong.mau.env), nên diễn tập trên TTDL thật chỉ đổi tệp env.
#
# Mặc định chỉ in kế hoạch (chạy thử); thêm --confirm để thực hiện, như quy ước của vtctl. Quyết định chuyển vùng
# là của IC (TL-04 DR-10): kịch bản không tự phát hiện sự cố, không tự quyết, và dừng nếu primary cũ còn ghi được
# mà chưa được rào chắn. Mỗi bước ghi một dòng JSON {bước, lúc, kết quả} vào --nhat-ky để lập biên bản.
#
# Dùng: dr-chuyen-vung.sh dr10|dr16 --env <tệp.env> [--nhat-ky <tệp.jsonl>] [--confirm]
set -euo pipefail

KIEU=${1:-}; shift || true
ENV=""; NHAT_KY=/dev/null; THAT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env) ENV=$2; shift 2 ;;
    --nhat-ky) NHAT_KY=$2; shift 2 ;;
    --confirm) THAT=1; shift ;;
    *) echo "dr-chuyen-vung: tham số lạ $1" >&2; exit 2 ;;
  esac
done
case "$KIEU" in dr10|dr16) ;; *) echo "dùng: dr-chuyen-vung.sh dr10|dr16 --env <tệp> [--confirm]" >&2; exit 2 ;; esac
[ -f "$ENV" ] || { echo "dr-chuyen-vung: thiếu --env" >&2; exit 2; }
# shellcheck source=/dev/null
. "$ENV"
: "${CELL:?}" "${CTX_HL:?}" "${CTX_SN:?}" "${CHE_DO:?}" "${NS_PG:?}" "${CUM_PG:?}" "${NS_KAFKA:?}"
CHO_PROMOTE=${CHO_PROMOTE:-900}; LECH_TOI_DA=${LECH_TOI_DA:-1048576}

ghi() { printf '{"buoc":"%s","luc":"%s","ketQua":"%s"}\n' "$1" "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$2" >> "$NHAT_KY"; }
buoc() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
lam() {   # in lệnh; chỉ chạy khi --confirm
  printf '    $ %s\n' "$*"
  [ "$THAT" = 1 ] && "$@"
  return 0
}
kc() { kubectl --context "$1" "${@:2}"; }
toi_duoc() { kc "$1" get --raw /readyz >/dev/null 2>&1; }
psql_cum() {   # $1 context, $2 cụm, $3 câu lệnh: chạy trên primary hiện tại của cụm CNPG
  local pod
  pod=$(kc "$1" -n "$NS_PG" get cluster "$2" -o jsonpath='{.status.currentPrimary}')
  kc "$1" -n "$NS_PG" exec "$pod" -c postgres -- psql -qAtX -c "$3"
}
dang_phuc_hoi() { [ "$(psql_cum "$1" "$2" 'SELECT pg_is_in_recovery()' 2>/dev/null)" = t ]; }

# Phía nguồn (đang là primary) và phía đích của lượt chuyển này.
if [ "$KIEU" = dr10 ]; then NGUON=hl; DICH=sn; CTX_NGUON=$CTX_HL; CTX_DICH=$CTX_SN; MM2_CU=${MM2_HL_SN:-}; MM2_MOI=${MM2_SN_HL:-}
else NGUON=sn; DICH=hl; CTX_NGUON=$CTX_SN; CTX_DICH=$CTX_HL; MM2_CU=${MM2_SN_HL:-}; MM2_MOI=${MM2_HL_SN:-}; fi
[ "$THAT" = 1 ] || buoc "CHẠY THỬ: chỉ in kế hoạch, thêm --confirm để thực hiện"
buoc "$KIEU cell $CELL: $NGUON → $DICH, chế độ $CHE_DO"
ghi bat-dau "$KIEU $NGUON->$DICH"

# 1. Kiểm trước: phía đích đang là bản sao và bắt kịp.
buoc "1. Kiểm phía đích $CTX_DICH"
toi_duoc "$CTX_DICH" || { echo "dr-chuyen-vung: không tới được cụm $CTX_DICH" >&2; exit 1; }
for c in $CUM_PG; do
  if dang_phuc_hoi "$CTX_DICH" "$c-$DICH"; then echo "    $c-$DICH: đang là bản sao"; else
    echo "dr-chuyen-vung: $c-$DICH không ở chế độ bản sao; dừng (đã chuyển hay cấu hình sai?)" >&2; exit 1; fi
done
ghi kiem-dich dat

# 2. Rào chắn phía nguồn (DR-10) hoặc chờ phía đích bắt kịp (DR-16, chuyển có điều phối).
NGUON_SONG=0; toi_duoc "$CTX_NGUON" && NGUON_SONG=1
if [ "$KIEU" = dr10 ]; then
  if [ "$NGUON_SONG" = 1 ]; then
    buoc "2. Hoà Lạc còn tới được qua kênh quản trị: rào chắn mọi instance (cnpg.io/fencedInstances)"
    for c in $CUM_PG; do lam kc "$CTX_NGUON" -n "$NS_PG" annotate cluster "$c-hl" --overwrite 'cnpg.io/fencedInstances=["*"]'; done
    ghi rao-chan "da-rao-${CUM_PG// /,}"
  else
    buoc "2. Hoà Lạc không tới được: coi như đã rào chắn (mất điện, mất mạng hoàn toàn); ghi vào biên bản"
    ghi rao-chan khong-toi-duoc-coi-nhu-da-rao
  fi
else
  [ "$NGUON_SONG" = 1 ] || { echo "dr-chuyen-vung: DR-16 cần cả hai TTDL; không tới được $CTX_NGUON" >&2; exit 1; }
  buoc "2. Chờ phía đích bắt kịp (độ lệch WAL dưới $LECH_TOI_DA byte)"
  for c in $CUM_PG; do
    lech=$(psql_cum "$CTX_NGUON" "$c-$NGUON" "SELECT coalesce(max(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)), -1) FROM pg_stat_replication" || echo -1)
    echo "    $c: lệch ${lech} byte"
    { [ "${lech%.*}" -ge 0 ] && [ "${lech%.*}" -lt "$LECH_TOI_DA" ]; } || { echo "dr-chuyen-vung: $c chưa bắt kịp, chưa chuyển" >&2; exit 1; }
  done
  ghi kiem-lech dat
fi

# 3. Đổi replica.primary sang phía đích.
buoc "3. Đổi replica.primary của mọi cụm sang phía $DICH"
if [ "$CHE_DO" = gitops ]; then
  echo "    Commit có chữ ký: deploy/cells/$CELL/cell.yaml đặt primarySite: $DICH, standbySite: $NGUON"
  echo "    (ApplicationSet đổi global.siteRole, data-pg đổi replica.primary ở cả hai TTDL, MirrorMaker 2 đảo chiều,"
  echo "    Debezium sang TTDL mới; lớp ứng dụng phía $DICH bỏ minReplicas: 1 ở values-$DICH.yaml)."
  echo "    Kịch bản chờ cụm thăng cấp sau khi Argo CD đồng bộ."
else
  for c in $CUM_PG; do
    patch="{\"spec\":{\"replica\":{\"primary\":\"$c-$DICH\"}}}"
    lam kc "$CTX_DICH" -n "$NS_PG" patch cluster "$c-$DICH" --type merge -p "$patch"
    [ "$NGUON_SONG" = 1 ] && lam kc "$CTX_NGUON" -n "$NS_PG" patch cluster "$c-$NGUON" --type merge -p "$patch"
  done
fi
T_DOI=$(date +%s)
ghi doi-primary "$DICH"

# 4. Chờ phía đích nhận ghi.
buoc "4. Chờ các cụm phía $DICH thăng cấp (tối đa $CHO_PROMOTE s mỗi cụm)"
if [ "$THAT" = 1 ]; then
  for c in $CUM_PG; do
    for ((i = 0; i < CHO_PROMOTE; i += 5)); do dang_phuc_hoi "$CTX_DICH" "$c-$DICH" || break; sleep 5; done
    dang_phuc_hoi "$CTX_DICH" "$c-$DICH" && { echo "dr-chuyen-vung: $c-$DICH chưa thăng cấp" >&2; ghi promote "loi-$c"; exit 1; }
    echo "    $c-$DICH: nhận ghi sau $(( $(date +%s) - T_DOI )) s"
  done
  if [ "$KIEU" = dr10 ] && [ "$NGUON_SONG" = 1 ]; then
    for c in $CUM_PG; do lam kc "$CTX_NGUON" -n "$NS_PG" annotate cluster "$c-hl" cnpg.io/fencedInstances-; done
  fi
fi
ghi promote dat

# 5. Kafka: dừng luồng nhân bản cũ (phía đích thành nguồn sự kiện); DR-16 bật lại luồng theo chiều mới.
buoc "5. Kafka MirrorMaker 2"
if [ -n "$MM2_CU" ]; then lam kc "$CTX_DICH" -n "$NS_KAFKA" annotate kafkamirrormaker2 "$MM2_CU" --overwrite strimzi.io/pause-reconciliation=true; fi
if [ "$CHE_DO" = truc-tiep ] && [ -n "$MM2_CU" ]; then lam kc "$CTX_DICH" -n "$NS_KAFKA" scale kafkamirrormaker2 "$MM2_CU" --replicas=0; fi
[ -n "$MM2_MOI" ] && echo "    Luồng $MM2_MOI (phía $NGUON) do Argo CD dựng khi phía $NGUON chạy lại ở vai trò chờ."
ghi kafka dat

# 6. Các bước có người làm, kịch bản chỉ nhắc và chờ xác nhận bằng biên bản.
buoc "6. Bước thủ công (ghi thời điểm vào biên bản):"
echo "    - Ceph: radosgw-admin zone modify --rgw-zone=cell-$CELL-$DICH --master --default && radosgw-admin period update --commit (sổ tay ceph-multisite.md)"
echo "    - Phát lại outbox từ điểm an toàn (tối đa 72 giờ): vtctl -c $CELL outbox replay --schema <schema> --tenant <uuid> --from <mốc> --to <lúc Debezium chạy> --reason <mã sự cố> --confirm (sổ tay phat-lai-outbox.md mục 3)"
echo "    - Kiểm khói và verify_run 24 giờ (ops-22); health /actuator/health/site và /healthz/site báo role=primary, paused=[]"
echo "    - Đổi TTDL hoạt động: registry.cell.active_dc theo bốn mắt (sổ tay geodns.md mục 3; DR-16 đặt lại NULL)"
ghi ket-thuc "$KIEU"
buoc "Xong phần tự động của $KIEU"

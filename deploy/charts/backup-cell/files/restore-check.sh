#!/usr/bin/env bash
# Khôi phục thử hằng ngày (INF-17, TL-04 bảng lịch sao lưu, OPS-18, nguyên tắc "0" của 3-2-1-1-0): chọn một cụm
# PostgreSQL của cell theo luân phiên, dựng cụm CNPG tạm trong namespace cách ly từ bản nền mới nhất cộng WAL tới
# LUI_GIAY trước (plugin Barman Cloud, ObjectStore chỉ đọc trỏ cùng đường dẫn của cụm gốc), chạy truy vấn kiểm trên
# mọi CSDL, đẩy backup_restore_check_success và backup_restore_duration_seconds, rồi xoá cụm tạm.
# Biến môi trường: NS, CUMS ("<cụm>:<namespace> …"), HAU_TO_SERVER (INF-28, "" hoặc -<ttdl>), LECH_NGAY, LUI_GIAY, BUCKET, PLUGIN, CRED_SECRET, CA_SECRET,
# CA_KEY, IMAGE_PG, STORAGE_CLASS, KICH_THUOC ("<cụm>=<dung lượng> …"), CHO_GIAY, KIEM_FK, NGUONG_DONG, TI_LE,
# SO_CAI ("<cụm>=<CSDL> …"), RESOURCES, NODE_SELECTOR, TOLERATIONS (JSON), S3_ENDPOINT, PUSHGATEWAY, CELL.
set -euo pipefail
CFG=${CFG:-/cfg}
# shellcheck source=chung.sh
. "$CFG/chung.sh"
NGHI=${NGHI:-30}

read -ra ds <<< "$CUMS"
[ "${#ds[@]}" -gt 0 ] || { echo "LỖI: CUMS rỗng" >&2; exit 2; }
ngay=$(ngay_hien_tai)
i=$(( ( $(so_ngay "$ngay") + LECH_NGAY ) % ${#ds[@]} ))
IFS=: read -r ten ns_goc <<< "${ds[$i]}"
tam="rc-$ten-$(date -d "$ngay" +%Y%m%d)"
kho="rc-$ten"
moc=$(date -u -d "@$(( $(bay_gio) - LUI_GIAY ))" '+%Y-%m-%d %H:%M:%S+00')
kich=10Gi
for kv in $KICH_THUOC; do [ "${kv%%=*}" = "$ten" ] && kich=${kv#*=}; done
bat_dau=$(bay_gio)
dat=0
echo "Khôi phục thử $ten (namespace gốc $ns_goc) vào $NS/$tam, mốc $moc"

ket_thuc() {
  local ma=$?
  [ "$dat" = 1 ] || ma=1
  local tg=$(( $(bay_gio) - bat_dau ))
  {
    echo "# TYPE backup_restore_check_success gauge"
    echo "backup_restore_check_success $dat"
    echo "# TYPE backup_restore_duration_seconds gauge"
    echo "backup_restore_duration_seconds $tg"
    echo "# TYPE backup_restore_check_target_reached gauge"
    echo "backup_restore_check_target_reached ${toi_moc:-0}"
    echo "# TYPE backup_restore_check_last_run_timestamp_seconds gauge"
    echo "backup_restore_check_last_run_timestamp_seconds $(bay_gio)"
    if [ "$dat" = 1 ]; then
      echo "# TYPE backup_restore_check_last_success_timestamp_seconds gauge"
      echo "backup_restore_check_last_success_timestamp_seconds $(bay_gio)"
    fi
  } | day_chi_so restore-check cluster "$ten" || echo "Không đẩy được chỉ số" >&2
  kubectl -n "$NS" delete cluster "$tam" --ignore-not-found --wait=false || true
  kubectl -n "$NS" delete objectstore "$kho" --ignore-not-found || true
  if [ "$dat" = 1 ]; then echo "ĐẠT: $ten khôi phục và kiểm xong sau ${tg}s"; else echo "HỎNG: $ten (sau ${tg}s)" >&2; fi
  exit "$ma"
}
trap ket_thuc EXIT

# ObjectStore chỉ đọc trong namespace cách ly: cụm tạm không khai plugin WAL archiver nên không ghi gì vào kho.
ca=""
[ -n "${CA_SECRET:-}" ] && ca="    endpointCA: { name: $CA_SECRET, key: $CA_KEY }"
kubectl apply -f - <<YAML
apiVersion: barmancloud.cnpg.io/v1
kind: ObjectStore
metadata:
  name: $kho
  namespace: $NS
  labels: { vt.io/restore-check: "true" }
spec:
  configuration:
    destinationPath: s3://$BUCKET/$ten
    endpointURL: $S3_ENDPOINT
$ca
    s3Credentials:
      accessKeyId: { name: $CRED_SECRET, key: ACCESS_KEY_ID }
      secretAccessKey: { name: $CRED_SECRET, key: ACCESS_SECRET_KEY }
YAML
# Khối affinity của cụm tạm. nodeSelector {} hay tolerations [] rỗng thì bỏ hẳn: CNPG so PodSpec mong muốn (có trường
# rỗng) với pod thật (API server bỏ trường rỗng) thấy khác mãi nên khởi động lại primary liên tục (gặp trên kind).
dat_cho() {
  local ns_ tl_
  ns_=$(printf '%s' "$NODE_SELECTOR" | tr -d ' ')
  tl_=$(printf '%s' "$TOLERATIONS" | tr -d ' ')
  { [ -n "$ns_" ] && [ "$ns_" != '{}' ]; } || { [ -n "$tl_" ] && [ "$tl_" != '[]' ]; } || return 0
  echo "  affinity:"
  [ -n "$ns_" ] && [ "$ns_" != '{}' ] && echo "    nodeSelector: $NODE_SELECTOR"
  [ -n "$tl_" ] && [ "$tl_" != '[]' ] && echo "    tolerations: $TOLERATIONS"
  return 0
}
# tao_cum <tên> <mốc|"">: cụm tạm khôi phục tới mốc, hoặc tới hết WAL khi mốc rỗng.
tao_cum() {
  local muc_tieu=""
  [ -n "$2" ] && muc_tieu="      recoveryTarget:
        targetTime: \"$2\""
  kubectl apply -f - <<YAML
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $1
  namespace: $NS
  labels: { vt.io/restore-check: "true", vt.io/restore-nguon: $ten }
spec:
  instances: 1
  imageName: $IMAGE_PG
  enableSuperuserAccess: false
  resources: $RESOURCES
$(dat_cho)
  storage:
    storageClass: $STORAGE_CLASS
    size: $kich
  bootstrap:
    recovery:
      source: nguon
$muc_tieu
  externalClusters:
    - name: nguon
      plugin:
        name: $PLUGIN
        parameters:
          barmanObjectName: $kho
          serverName: $ten${HAU_TO_SERVER:-}
YAML
}
tao_cum "$tam" "$moc"
toi_moc=1

# Chờ cụm tạm sẵn sàng; Job khôi phục của CNPG thất bại (bản nền hỏng, thiếu WAL) thì dừng sớm. Riêng trường hợp
# PostgreSQL báo "recovery ended before configured recovery target was reached": cụm nguồn không ghi gì từ trước mốc nên
# WAL không có giao dịch nào sau mốc (cụm nhàn rỗi, archive_timeout chỉ đổi đoạn WAL khi có ghi), trạng thái ở mốc
# trùng trạng thái cuối WAL; dựng lại cụm tạm tới hết WAL (một lần) và ghi backup_restore_check_target_reached 0.
han=$(( $(bay_gio) + CHO_GIAY ))
while :; do
  san_sang=$(kubectl -n "$NS" get cluster "$tam" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
  [ "$san_sang" = True ] && break
  that_bai=$(kubectl -n "$NS" get jobs -l "cnpg.io/cluster=$tam" -o jsonpath='{range .items[*]}{.status.failed}{"\n"}{end}' 2>/dev/null | awk '$1 > 0' | wc -l)
  if [ "$that_bai" -gt 0 ]; then
    nhat_ky=$(kubectl -n "$NS" logs -l "cnpg.io/cluster=$tam" --all-containers --tail=-1 2>&1 || true)
    if [ "$toi_moc" = 1 ] && [[ "$nhat_ky" == *"recovery ended before configured recovery target was reached"* ]]; then
      echo "WAL không có giao dịch sau mốc $moc (cụm nguồn nhàn rỗi): khôi phục tới hết WAL"
      kubectl -n "$NS" delete cluster "$tam" --ignore-not-found --wait=false || true
      tam="$tam-cuoi"
      toi_moc=0
      tao_cum "$tam" ""
      continue
    fi
    # Vài dòng lỗi cuối của Job khôi phục để người trực biết nguyên nhân mà không cần vào namespace cách ly.
    printf '%s\n' "$nhat_ky" | grep -iE 'error|fatal|panic' | tail -5 | cut -c1-400 >&2 || true
    echo "LỖI: job khôi phục của $tam thất bại" >&2; kubectl -n "$NS" get cluster "$tam" -o jsonpath='{.status.phaseReason}' >&2 || true; exit 1
  fi
  if [ "$(bay_gio)" -ge "$han" ]; then echo "LỖI: $tam chưa sẵn sàng sau ${CHO_GIAY}s" >&2; exit 1; fi
  sleep "$NGHI"
done

pod="$tam-1"
psql_tam() { kubectl -n "$NS" exec -i "$pod" -c postgres -- psql -X -q -v ON_ERROR_STOP=1 -At "$@"; }
[ "$(psql_tam -d postgres -c 'SELECT pg_is_in_recovery()')" = f ] || { echo "LỖI: $tam vẫn ở chế độ khôi phục" >&2; exit 1; }
loi=0
mapfile -t csdl < <(psql_tam -d postgres -c "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate AND datname <> 'postgres' ORDER BY 1")
[ "${#csdl[@]}" -gt 0 ] || { echo "LỖI: $tam không có CSDL nào" >&2; exit 1; }
for db in "${csdl[@]}"; do
  if psql_tam -d "$db" -v kiem_fk="$KIEM_FK" -v nguong="$NGUONG_DONG" -v ti_le="$TI_LE" < "$CFG/kiem-tra.sql"; then
    echo "  $db: đạt"
  else echo "  $db: HỎNG" >&2; loi=$((loi + 1)); fi
done
for kv in ${SO_CAI:-}; do
  [ "${kv%%=*}" = "$ten" ] || continue
  if psql_tam -d "${kv#*=}" < "$CFG/kiem-so-cai.sql"; then echo "  sổ cái ${kv#*=}: đạt"
  else echo "  sổ cái ${kv#*=}: HỎNG" >&2; loi=$((loi + 1)); fi
done
[ "$loi" -eq 0 ] && dat=1

#!/usr/bin/env bash
# Khôi phục thử hằng ngày (INF-17, TL-04 bảng lịch sao lưu, OPS-18, nguyên tắc "0" của 3-2-1-1-0): chọn một cụm
# PostgreSQL của cell theo luân phiên, dựng cụm CNPG tạm trong namespace cách ly từ bản nền mới nhất cộng WAL tới
# LUI_GIAY trước (plugin Barman Cloud, ObjectStore chỉ đọc trỏ cùng đường dẫn của cụm gốc), chạy truy vấn kiểm trên
# mọi CSDL, đẩy backup_restore_check_success và backup_restore_duration_seconds, rồi xoá cụm tạm.
# Biến môi trường: NS, CUMS ("<cụm>:<namespace> …"), LECH_NGAY, LUI_GIAY, BUCKET, PLUGIN, CRED_SECRET, CA_SECRET,
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
kubectl apply -f - <<YAML
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $tam
  namespace: $NS
  labels: { vt.io/restore-check: "true", vt.io/restore-nguon: $ten }
spec:
  instances: 1
  imageName: $IMAGE_PG
  enableSuperuserAccess: false
  resources: $RESOURCES
  affinity:
    nodeSelector: $NODE_SELECTOR
    tolerations: $TOLERATIONS
  storage:
    storageClass: $STORAGE_CLASS
    size: $kich
  bootstrap:
    recovery:
      source: nguon
      recoveryTarget:
        targetTime: "$moc"
  externalClusters:
    - name: nguon
      plugin:
        name: $PLUGIN
        parameters:
          barmanObjectName: $kho
          serverName: $ten
YAML

# Chờ cụm tạm sẵn sàng; Job khôi phục của CNPG thất bại (bản nền hỏng, thiếu WAL) thì dừng sớm.
han=$(( $(bay_gio) + CHO_GIAY ))
while :; do
  san_sang=$(kubectl -n "$NS" get cluster "$tam" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
  [ "$san_sang" = True ] && break
  that_bai=$(kubectl -n "$NS" get jobs -l "cnpg.io/cluster=$tam" -o jsonpath='{range .items[*]}{.status.failed}{"\n"}{end}' 2>/dev/null | awk '$1 > 0' | wc -l)
  if [ "$that_bai" -gt 0 ]; then echo "LỖI: job khôi phục của $tam thất bại" >&2; kubectl -n "$NS" get cluster "$tam" -o jsonpath='{.status.phaseReason}' >&2 || true; exit 1; fi
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

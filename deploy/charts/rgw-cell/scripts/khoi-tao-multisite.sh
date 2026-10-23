#!/usr/bin/env bash
# Khởi tạo realm RGW multisite và người dùng S3 của một cell (INF-12, TL-04 mục 8.4 "Ceph: multisite RGW và Object
# Lock"). Chạy trên máy quản trị Ceph của từng TTDL, đúng trình tự: phía Hoà Lạc (zone master) trước, phía Nam sau.
#   VAI=hl: realm cell-<mã>, zonegroup cell-<mã>-zg (master), zone cell-<mã>-hl (master), người dùng hệ thống đồng bộ,
#           lớp lưu trữ HDD EC 4+2 trong placement mặc định, tài khoản quản trị kho và người dùng S3 của service.
#   VAI=sn: kéo realm từ zone master, tạo zone cell-<mã>-sn, nhận lớp lưu trữ HDD.
# Khoá truy cập lấy từ OpenBao lúc chạy (bao kv get, đường dẫn <BAO_DUONG_DAN>/cell-<mã>/<uid>); khoá chưa có thì sinh
# ngẫu nhiên và ghi vào OpenBao, không ghi ra tệp, không in ra màn hình. Chạy lại nhiều lần cho cùng kết quả.
# Dùng: CELL=g4 VAI=hl|sn [ENDPOINT_HL=…] [ENDPOINT_SN=…] scripts/khoi-tao-multisite.sh
# Biến khác: RGW_ADMIN (mặc định radosgw-admin; ví dụ "cephadm shell -- radosgw-admin"), BAO (mặc định bao),
# BAO_DUONG_DAN (mặc định kv/ceph), NGUOI_DUNG (uid service, cách nhau bởi dấu cách), POOL_HDD (pool dữ liệu EC 4+2).
set -euo pipefail
CELL=${CELL:?cần CELL, ví dụ g4}
VAI=${VAI:?cần VAI=hl hoặc VAI=sn}
REALM=cell-$CELL
ZG=$REALM-zg
ENDPOINT_HL=${ENDPOINT_HL:-https://s3.$REALM.hl.vt.local}
ENDPOINT_SN=${ENDPOINT_SN:-https://s3.$REALM.sn.vt.local}
read -r -a RGW <<< "${RGW_ADMIN:-radosgw-admin}"
BAO=${BAO:-bao}
DUONG_DAN=${BAO_DUONG_DAN:-kv/ceph}/$REALM
NGUOI_DUNG=${NGUOI_DUNG:-evidence-service document-service ledger-service tenant-registry pam-ghi-phien}
LOP_HDD=${LOP_HDD:-HDD_EC}
POOL_HDD=${POOL_HDD:-$REALM.rgw.hdd-ec.data}
QUAN_TRI=kho-quan-tri-$REALM
DONG_BO=sync-$REALM

rgw() { "${RGW[@]}" "$@"; }
# khoa <uid> <access-key|secret-key>: đọc khoá từ OpenBao, chưa có thì sinh và ghi cả cặp.
khoa() {
  local uid=$1 truong=$2
  if ! "$BAO" kv get -field="$truong" "$DUONG_DAN/$uid" 2>/dev/null; then
    "$BAO" kv put "$DUONG_DAN/$uid" access-key="$(tr -dc 'A-Z0-9' < /dev/urandom | head -c 20)" \
      secret-key="$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40)" >/dev/null
    "$BAO" kv get -field="$truong" "$DUONG_DAN/$uid"
  fi
}
co() { rgw "$@" >/dev/null 2>&1; }

SYNC_AK=$(khoa "$DONG_BO" access-key)
SYNC_SK=$(khoa "$DONG_BO" secret-key)

if [ "$VAI" = hl ]; then
  co realm get --rgw-realm="$REALM" || rgw realm create --rgw-realm="$REALM" --default >/dev/null
  co zonegroup get --rgw-zonegroup="$ZG" || rgw zonegroup create --rgw-zonegroup="$ZG" --rgw-realm="$REALM" \
    --endpoints="$ENDPOINT_HL" --master --default >/dev/null
  co zone get --rgw-zone="$REALM-hl" || rgw zone create --rgw-zonegroup="$ZG" --rgw-zone="$REALM-hl" \
    --endpoints="$ENDPOINT_HL" --master --default --access-key="$SYNC_AK" --secret="$SYNC_SK" >/dev/null
  co user info --uid="$DONG_BO" || rgw user create --uid="$DONG_BO" --display-name="sync $REALM" \
    --access-key="$SYNC_AK" --secret="$SYNC_SK" --system >/dev/null
  # Lớp lưu trữ HDD EC 4+2 để vòng đời chuyển minh chứng sau 30 ngày (TL-03); pool EC do cephadm tạo trước.
  rgw zonegroup placement add --rgw-realm="$REALM" --rgw-zonegroup="$ZG" --placement-id=default-placement --storage-class="$LOP_HDD" >/dev/null
  rgw zone placement add --rgw-realm="$REALM" --rgw-zonegroup="$ZG" --rgw-zone="$REALM-hl" --placement-id=default-placement --storage-class="$LOP_HDD" \
    --data-pool="$POOL_HDD" >/dev/null
  rgw period update --commit --rgw-realm="$REALM" >/dev/null
  # Tài khoản quản trị kho: chủ mọi bucket, chỉ Job của chart rgw-cell dùng. Người dùng service không tự tạo được
  # bucket (max-buckets -1), chỉ có quyền do chính sách bucket cấp.
  for uid in "$QUAN_TRI" $NGUOI_DUNG; do
    ak=$(khoa "$uid" access-key); sk=$(khoa "$uid" secret-key)
    if [ "$uid" = "$QUAN_TRI" ]; then gioi_han=1000; else gioi_han=-1; fi
    if co user info --uid="$uid"; then
      rgw user modify --uid="$uid" --max-buckets="$gioi_han" >/dev/null
    else
      rgw user create --uid="$uid" --display-name="$uid $REALM" --access-key="$ak" --secret="$sk" \
        --max-buckets="$gioi_han" >/dev/null
    fi
  done
  echo "zone master $REALM-hl sẵn sàng; người dùng: $QUAN_TRI $NGUOI_DUNG"
elif [ "$VAI" = sn ]; then
  co realm get --rgw-realm="$REALM" || rgw realm pull --rgw-realm="$REALM" --url="$ENDPOINT_HL" --default \
    --access-key="$SYNC_AK" --secret="$SYNC_SK" >/dev/null
  co zone get --rgw-zone="$REALM-sn" || rgw zone create --rgw-realm="$REALM" --rgw-zonegroup="$ZG" --rgw-zone="$REALM-sn" \
    --endpoints="$ENDPOINT_SN" --default --access-key="$SYNC_AK" --secret="$SYNC_SK" >/dev/null
  rgw zone placement add --rgw-realm="$REALM" --rgw-zonegroup="$ZG" --rgw-zone="$REALM-sn" --placement-id=default-placement --storage-class="$LOP_HDD" \
    --data-pool="$POOL_HDD" >/dev/null
  rgw period update --commit --rgw-realm="$REALM" >/dev/null
  echo "zone $REALM-sn đã nối realm; kiểm tra: ${RGW[*]} sync status --rgw-zone=$REALM-sn"
else
  echo "VAI phải là hl hoặc sn" >&2; exit 2
fi

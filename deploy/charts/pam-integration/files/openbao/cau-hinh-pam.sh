#!/usr/bin/env bash
# Cấu hình OpenBao cho PAM của một cell (INF-27; TL-04 mục 9.3 SEC-58, mục 9.4, mục 9.7 SEC-42). Chạy sau
# deploy/openbao/cau-hinh.sh (INF-13), cùng biến CELL và PG_CLUSTERS; chạy lại bao nhiêu lần cũng được.
# Cần: bao CLI, BAO_ADDR, BAO_CACERT, BAO_TOKEN của quản trị khoá lấy qua PAM (không dùng root token).
#
# Tạo:
#   - Vai trò database "<cụm>-dba-ops": người dùng động IN ROLE dba_ops, sống 4 giờ (không gia hạn quá 4 giờ), tối đa
#     KET_NOI_MOI_NGUOI kết nối; thu hồi ngắt phiên còn mở rồi xoá vai trò (người dùng động không được sở hữu đối
#     tượng nào; còn sở hữu thì DROP ROLE lỗi, lease thu hồi thất bại và hiện trong nhật ký OpenBao).
#   - Vai trò database "<cụm>-pha-kinh-doc": người dùng động IN ROLE dba_pha_kinh_doc, sống 2 giờ (phá kính SEC-58).
#     OpenBao bản mã nguồn mở không có control groups (tính năng duyệt nhiều người của Vault Enterprise), nên điều
#     kiện hai người duyệt (một từ khách hàng) do PAM thực thi; OpenBao chỉ cho định danh broker của PAM đọc đường này,
#     và mọi lần đọc đi vào thiết bị kiểm toán → otel-siem → sys.siem.v1 (quy tắc SOC "pha-kinh").
#   - Static role "<cụm>-pam-quet": xoay mật khẩu pam_quet 24 giờ cho CronJob quét (đọc qua ESO).
#   - Chính sách pam-broker, pam-quet-<cell>, quan-tri-khoa; auth cert cho broker PAM; vai trò OIDC quan-tri-khoa.
#     Token của người và của broker bị ràng buộc token_bound_cidrs = dải proxy PAM, nên chỉ dùng được khi đi qua PAM.
#
# Biến:
#   CELL                bắt buộc
#   PG_CLUSTERS         "<cụm>:<namespace>" cách nhau dấu cách (mặc định pg-core:data-pg như cau-hinh.sh)
#   PAM_CIDRS           dải địa chỉ proxy PAM, cách nhau dấu phẩy (bắt buộc; ví dụ 192.0.2.0/28 trong thử nghiệm)
#   PAM_BROKER_CA       tệp PEM CA cấp chứng thư máy khách cho broker PAM (PKI nội bộ, INF-13); trống thì bỏ qua auth cert
#   PAM_BROKER_CN       CN chứng thư của broker (mặc định pam-broker.vt.local)
#   OIDC_PATH           đường auth OIDC của Keycloak platform-ops (mặc định oidc-platform-ops; trống thì bỏ qua)
#   NHOM_QUAN_TRI_KHOA  nhóm Keycloak được nhận chính sách quan-tri-khoa (mặc định platform-ops-quan-tri-khoa)
#   APP_NS_QUET         namespace CronJob quét (mặc định pam-kiem-soat); SA pam-quet-csdl
#   THOI_HAN_DBA        mặc định 4h;  THOI_HAN_PHA_KINH mặc định 2h;  KET_NOI_MOI_NGUOI mặc định 2
set -euo pipefail
PG_CLUSTERS=${PG_CLUSTERS-pg-core:data-pg}
PAM_BROKER_CA=${PAM_BROKER_CA:-}
PAM_BROKER_CN=${PAM_BROKER_CN:-pam-broker.vt.local}
OIDC_PATH=${OIDC_PATH-oidc-platform-ops}
NHOM_QUAN_TRI_KHOA=${NHOM_QUAN_TRI_KHOA:-platform-ops-quan-tri-khoa}
APP_NS_QUET=${APP_NS_QUET:-pam-kiem-soat}
THOI_HAN_DBA=${THOI_HAN_DBA:-4h}
THOI_HAN_PHA_KINH=${THOI_HAN_PHA_KINH:-2h}
KET_NOI_MOI_NGUOI=${KET_NOI_MOI_NGUOI:-2}
THU_MUC=$(cd "$(dirname "$0")" && pwd)

co_auth() { local ds; ds=$(bao auth list -format=json); grep -q "\"$1/\"" <<<"$ds"; }
buoc()    { echo "== $*"; }

# Câu SQL của vai trò động (tách hàm để kiểm thử chạy được trên PostgreSQL thật, xem tests/sql).
cau_tao() { # <vai trò nhóm> <số kết nối>
  printf 'CREATE ROLE "{{name}}" WITH LOGIN INHERIT NOBYPASSRLS NOCREATEDB NOCREATEROLE CONNECTION LIMIT %s PASSWORD '"'"'{{password}}'"'"' VALID UNTIL '"'"'{{expiration}}'"'"' IN ROLE %s;' "$2" "$1"
}
cau_thu_hoi() {
  printf '%s' 'SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '"'"'{{name}}'"'"'; DROP ROLE IF EXISTS "{{name}}";'
}
# "--in-sql <vai trò nhóm> <số kết nối>": chỉ in câu tạo và câu thu hồi (kiểm thử), không gọi OpenBao.
if [ "${1:-}" = "--in-sql" ]; then cau_tao "${2:-dba_ops}" "${3:-$KET_NOI_MOI_NGUOI}"; echo; cau_thu_hoi; echo; exit 0; fi
: "${CELL:?cần CELL}"
: "${PAM_CIDRS:?cần PAM_CIDRS (dải proxy PAM)}"
AUTH=k8s-cell-$CELL

chinh_sach_broker=""
chinh_sach_quet=""
buoc "Vai trò database cho PAM"
for muc in $PG_CLUSTERS; do
  cum=${muc%%:*}
  ket_noi="$cum-$CELL"
  bao read "database/config/$ket_noi" >/dev/null 2>&1 || { echo "  $ket_noi chưa cấu hình (chạy deploy/openbao/cau-hinh.sh trước)" >&2; exit 1; }
  bao write "database/roles/$cum-dba-ops" db_name="$ket_noi" default_ttl="$THOI_HAN_DBA" max_ttl="$THOI_HAN_DBA" \
    creation_statements="$(cau_tao dba_ops "$KET_NOI_MOI_NGUOI")" revocation_statements="$(cau_thu_hoi)" >/dev/null
  bao write "database/roles/$cum-pha-kinh-doc" db_name="$ket_noi" default_ttl="$THOI_HAN_PHA_KINH" max_ttl="$THOI_HAN_PHA_KINH" \
    creation_statements="$(cau_tao dba_pha_kinh_doc 1)" revocation_statements="$(cau_thu_hoi)" >/dev/null
  bao write "database/static-roles/$cum-pam-quet" db_name="$ket_noi" username=pam_quet rotation_period=24h >/dev/null
  echo "  $cum-dba-ops ($THOI_HAN_DBA), $cum-pha-kinh-doc ($THOI_HAN_PHA_KINH), $cum-pam-quet (xoay 24h)"
  chinh_sach_broker+="path \"database/creds/$cum-dba-ops\"      { capabilities = [\"read\"] }"$'\n'
  chinh_sach_broker+="path \"database/creds/$cum-pha-kinh-doc\" { capabilities = [\"read\"] }"$'\n'
  chinh_sach_quet+="path \"database/static-creds/$cum-pam-quet\" { capabilities = [\"read\"] }"$'\n'
done

buoc "Chính sách"
# Broker PAM: lấy thông tin động khi yêu cầu đã duyệt, thu hồi sớm khi phiên kết thúc. Không có quyền nào khác.
{ printf '%s' "$chinh_sach_broker"
  echo 'path "sys/leases/revoke"            { capabilities = ["update"] }'
  echo 'path "sys/leases/lookup"            { capabilities = ["update"] }'
} | bao policy write pam-broker - >/dev/null
printf '%s' "$chinh_sach_quet" | bao policy write "pam-quet-$CELL" - >/dev/null
HCL=$THU_MUC/chinh-sach/quan-tri-khoa.hcl
[ -f "$HCL" ] || HCL=$THU_MUC/quan-tri-khoa.hcl   # bản trong ConfigMap pam-openbao để phẳng
sed -e "s/@CELL@/$CELL/g" "$HCL" | bao policy write quan-tri-khoa - >/dev/null
echo "  pam-broker, pam-quet-$CELL, quan-tri-khoa"

buoc "Auth kubernetes: CronJob quét đọc mật khẩu pam_quet (ESO SecretStore trong $APP_NS_QUET)"
bao write "auth/$AUTH/role/pam-quet" bound_service_account_names=pam-quet-csdl bound_service_account_namespaces="$APP_NS_QUET" \
  audience=openbao token_policies="pam-quet-$CELL" token_ttl=15m token_max_ttl=1h >/dev/null

if [ -n "$PAM_BROKER_CA" ]; then
  buoc "Auth cert cho broker PAM (token 15 phút, chỉ dùng được từ dải proxy PAM)"
  co_auth cert-pam || bao auth enable -path=cert-pam cert
  bao write auth/cert-pam/certs/pam-broker certificate=@"$PAM_BROKER_CA" allowed_common_names="$PAM_BROKER_CN" \
    token_policies=pam-broker token_ttl=15m token_max_ttl=1h token_bound_cidrs="$PAM_CIDRS" >/dev/null
fi

if [ -n "$OIDC_PATH" ] && co_auth "$OIDC_PATH"; then
  buoc "Vai trò OIDC quan-tri-khoa (nhóm $NHOM_QUAN_TRI_KHOA, token 1 giờ, chỉ từ proxy PAM)"
  bao write "auth/$OIDC_PATH/role/quan-tri-khoa" role_type=oidc user_claim=preferred_username groups_claim=groups \
    bound_claims="{\"groups\":[\"$NHOM_QUAN_TRI_KHOA\"]}" oidc_scopes=openid allowed_redirect_uris="${OIDC_REDIRECT:-https://pam.vt.local/oidc/callback}" \
    token_policies=quan-tri-khoa token_ttl=1h token_max_ttl=4h token_bound_cidrs="$PAM_CIDRS" >/dev/null
fi
echo "Xong. Kiểm chứng: bao read database/roles/<cụm>-pha-kinh-doc (max_ttl 2h); bao policy read pam-broker."

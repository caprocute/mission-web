#!/usr/bin/env bash
# Cấu hình dạng mã cho OpenBao của một cell (INF-13; TL-04 mục 9.4; SEC-10, SEC-11, SEC-12, SEC-59).
# Chạy lại bao nhiêu lần cũng được (idempotent): bật engine nếu chưa có, ghi đè chính sách và vai trò theo Git.
# Cần: bao CLI, BAO_ADDR, BAO_CACERT, BAO_TOKEN của quản trị khoá (token ngắn hạn cấp theo quy trình phá kính, không
# dùng root token lâu dài). Không có bí mật nào trong tệp này: mật khẩu khởi tạo bao_admin đọc từ KV rồi bị
# rotate-root thay ngay, nên sau bước này không ai biết mật khẩu quản trị CSDL mà OpenBao dùng.
#
# Biến:
#   CELL                  mã cell (hcm, g4...), bắt buộc
#   K8S_HOST              API server mà auth kubernetes gọi TokenReview (mặc định https://kubernetes.default.svc)
#   PG_CLUSTERS           cụm có secret động, dạng "<cụm>:<namespace>" cách nhau dấu cách (mặc định pg-core:data-pg;
#                         đặt rỗng để bỏ qua bước CSDL khi cụm PostgreSQL chưa dựng)
#   PG_DB                 CSDL để OpenBao kết nối quản trị (vai trò là toàn cụm; mặc định khoi_tao của data-pg)
#   PG_CORE_SVC_ROLES     vai trò nhóm của mission-core mà người dùng động được nhận, cách nhau dấu phẩy
#   PG_CA_DIR             thư mục CA của PostgreSQL trong pod OpenBao (chart mount ConfigMap openbao-pg-ca)
#   APP_NS                namespace của mission-core (mặc định core)
#   TENANTS               danh sách tenant cần KEK ngay (thường do tenant-registry tạo khi cấp tenant mới)
#   PKI_CSR_OUT           nơi ghi CSR của CA trung gian để ký ngoại tuyến (mặc định ./pki-<cell>.csr)
#   PKI_SIGNED_CERT       chứng thư CA trung gian đã ký (PEM, kèm chuỗi tới CA gốc); có thì nạp vào mount PKI
set -euo pipefail
cd "$(dirname "$0")"
: "${CELL:?cần CELL}"
K8S_HOST=${K8S_HOST:-https://kubernetes.default.svc}
PG_CLUSTERS=${PG_CLUSTERS-pg-core:data-pg}
PG_DB=${PG_DB:-khoi_tao}
PG_CORE_SVC_ROLES=${PG_CORE_SVC_ROLES:-svc_task,svc_okr,svc_appraisal,svc_dossier,svc_policy,svc_config,svc_oversight,svc_product,svc_org_replica}
PG_CA_DIR=${PG_CA_DIR:-/openbao/pg-ca}
APP_NS=${APP_NS:-core}
TENANTS=${TENANTS:-}
AUTH=k8s-cell-$CELL
PKI=pki_int_cell_${CELL//-/_}
PKI_CSR_OUT=${PKI_CSR_OUT:-./pki-$CELL.csr}

# Lấy danh sách trước rồi mới grep: "bao … | grep -q" dưới pipefail lỗi ngẫu nhiên do SIGPIPE.
co_mount() { local ds; ds=$(bao secrets list -format=json); grep -q "\"$1/\"" <<<"$ds"; }
co_auth()  { local ds; ds=$(bao auth list -format=json); grep -q "\"$1/\"" <<<"$ds"; }
buoc()     { echo "== $*"; }

buoc "KV v2 tại kv/"
co_mount kv || bao secrets enable -path=kv -version=2 kv

buoc "Auth kubernetes tại $AUTH (TokenReview bằng token của pod OpenBao)"
co_auth "$AUTH" || bao auth enable -path="$AUTH" kubernetes
bao write "auth/$AUTH/config" kubernetes_host="$K8S_HOST" >/dev/null

buoc "Chính sách"
for f in chinh-sach/*.hcl; do
  ten=$(basename "$f" .hcl)
  sed -e "s/@CELL@/$CELL/g" -e "s/@PKI@/$PKI/g" "$f" | bao policy write "$ten" - >/dev/null
  echo "  $ten"
done

buoc "Vai trò auth kubernetes (token_ttl 1h, token_max_ttl 4h, audience openbao)"
vai_tro() { # <vai trò> <SA> <namespace> <chính sách> [audience]
  bao write "auth/$AUTH/role/$1" bound_service_account_names="$2" bound_service_account_namespaces="$3" \
    audience="${5:-openbao}" token_policies="$4" token_ttl=1h token_max_ttl=4h >/dev/null
  echo "  $1 ← $3/$2"
}
vai_tro mission-core mission-core "$APP_NS" mission-core
vai_tro external-secrets external-secrets external-secrets external-secrets
vai_tro cert-manager openbao-issuer cert-manager cert-manager
vai_tro xoay-khoa xoay-khoa secure-data xoay-khoa
vai_tro sao-luu-raft sao-luu-raft secure-data sao-luu-raft

buoc "Secret engine database"
co_mount database || bao secrets enable database
for muc in $PG_CLUSTERS; do
  cum=${muc%%:*}; ns=${muc##*:}
  ket_noi="$cum-$CELL"
  # Mật khẩu khởi tạo chỉ dùng một lần ở đây: rotate-root thay ngay sau khi cấu hình.
  if ! bao read "database/config/$ket_noi" >/dev/null 2>&1; then
    mk=$(bao kv get -field=password "kv/data-pg/$cum/bao_admin")
    bao write "database/config/$ket_noi" plugin_name=postgresql-database-plugin \
      connection_url="postgresql://{{username}}:{{password}}@$cum-rw.$ns.svc:5432/$PG_DB?sslmode=verify-full&sslrootcert=$PG_CA_DIR/$cum.crt" \
      username=bao_admin password="$mk" password_authentication=scram-sha-256 \
      allowed_roles="$cum-*" verify_connection=true \
      root_rotation_statements="ALTER ROLE \"{{name}}\" WITH PASSWORD '{{password}}';" >/dev/null
    unset mk
    bao write -force "database/rotate-root/$ket_noi" >/dev/null
    echo "  $ket_noi: đã cấu hình và rotate-root"
  else
    echo "  $ket_noi: đã có (giữ mật khẩu đã xoay)"
  fi
done
# Người dùng động của mission-core: NOBYPASSRLS, sống 24 giờ, gia hạn tối đa 72 giờ, thu hồi khi lease hết hay
# pod chết (TL-04 mục 9.4). Thành viên các vai trò svc_<schema> của mission-core (INF-11 tách vai trò theo schema).
nhom=${PG_CORE_SVC_ROLES%%,*}
if [[ " $PG_CLUSTERS " == *" pg-core:"* ]]; then
bao write database/roles/pg-core-mission db_name="pg-core-$CELL" default_ttl=24h max_ttl=72h \
  creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN INHERIT NOBYPASSRLS PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' IN ROLE $PG_CORE_SVC_ROLES;" \
  revocation_statements="REASSIGN OWNED BY \"{{name}}\" TO $nhom; DROP OWNED BY \"{{name}}\"; DROP ROLE IF EXISTS \"{{name}}\";" >/dev/null
echo "  pg-core-mission → $PG_CORE_SVC_ROLES"
fi

buoc "Transit: KEK theo tenant (aes256-gcm96) và khoá ký cosign-release"
co_mount transit || bao secrets enable transit
for t in $TENANTS; do
  bao read "transit/keys/kek-tenant-$t" >/dev/null 2>&1 || bao write -f "transit/keys/kek-tenant-$t" type=aes256-gcm96 >/dev/null
  bao write "transit/keys/kek-tenant-$t/config" deletion_allowed=false exportable=false >/dev/null
  echo "  kek-tenant-$t"
done
bao read transit/keys/cosign-release >/dev/null 2>&1 || bao write -f transit/keys/cosign-release type=ecdsa-p256 >/dev/null
bao write transit/keys/cosign-release/config deletion_allowed=false exportable=false >/dev/null
echo "  cosign-release"

buoc "PKI trung gian $PKI (CA gốc ngoại tuyến, ops/runbooks/openbao-pki.md)"
co_mount "$PKI" || { bao secrets enable -path="$PKI" pki; bao secrets tune -max-lease-ttl=43800h "$PKI" >/dev/null; }
if [ -n "${PKI_SIGNED_CERT:-}" ]; then
  bao write "$PKI/intermediate/set-signed" certificate=@"$PKI_SIGNED_CERT" >/dev/null
  echo "  đã nạp CA trung gian đã ký"
elif ! bao read "$PKI/cert/ca" >/dev/null 2>&1 || [ -z "$(bao read -field=certificate "$PKI/cert/ca" 2>/dev/null)" ]; then
  bao write -field=csr "$PKI/intermediate/generate/internal" common_name="VT Cell $CELL Intermediate CA" \
    key_type=ec key_bits=384 ttl=43800h > "$PKI_CSR_OUT"
  echo "  đã sinh CSR $PKI_CSR_OUT: ký bằng CA gốc ngoại tuyến rồi chạy lại với PKI_SIGNED_CERT=<tệp>"
fi
bao write "$PKI/config/urls" issuing_certificates="https://openbao.secure-data.svc:8200/v1/$PKI/ca" \
  crl_distribution_points="https://openbao.secure-data.svc:8200/v1/$PKI/crl" >/dev/null
# Chứng thư dịch vụ nội bộ: tên miền dịch vụ của cụm, 90 ngày (cert-manager gia hạn ở ngày 60), ECDSA P-256.
bao write "$PKI/roles/internal-services" allowed_domains="svc,svc.cluster.local" allow_subdomains=true \
  allow_bare_domains=false allow_localhost=true allow_ip_sans=true allow_wildcard_certificates=true \
  enforce_hostnames=true require_cn=false server_flag=true client_flag=true \
  key_type=ec key_bits=256 max_ttl=2160h ttl=2160h no_store=true >/dev/null
echo "  vai trò internal-services"
echo "Xong cấu hình OpenBao cell $CELL."

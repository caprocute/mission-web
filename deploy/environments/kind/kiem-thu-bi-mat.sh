#!/usr/bin/env bash
# Kiểm thử kho bí mật và PKI của cell trên kind (INF-13): OpenBao 5 node Raft tự mở khoá bằng SoftHSM2 qua plugin
# PKCS#11 và mở lại sau khi xoá toàn bộ pod; cấu hình dạng mã deploy/openbao/cau-hinh.sh; secret động PostgreSQL cho
# ServiceAccount mission-core qua Pooler (verify-full), SA khác bị từ chối, thu hồi lease thì không kết nối được;
# cert-manager cấp chứng thư từ openbao-pki-cell; ESO tạo Secret từ KV; CronJob xoay khoá; nhật ký kiểm toán.
# Cần cụm kind dựng bằng tao-cum.sh, operator CNPG (kiem-thu-du-lieu.sh đã cài) và ảnh openbao-softhsm/dung-anh.sh.
# Mọi mật khẩu, PIN, root token sinh lúc chạy và chỉ nằm trong biến của tiến trình này hoặc Secret của cụm thử.
# Dùng: deploy/environments/kind/kiem-thu-bi-mat.sh
set -euo pipefail
cd "$(dirname "$0")"
CHARTS=../../charts/vt-platform/ha-tang
NS=secure-data
CM_VER=v1.21.2
ESO_VER=2.13.0
ANH=vt-kind/openbao-softhsm:2.7.1
WORKERS=$(kind get nodes --name "${CLUSTER:-vt-cell-kind}" | grep worker | tr '\n' ' ')
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d); PF=""
trap '[ -n "$PF" ] && kill "$PF" 2>/dev/null; rm -rf "${W:?}"' EXIT
mk() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
# Chuyển cổng tới node đang hoạt động (nhãn openbao-active của service_registration): standby có thể trả chuyển
# hướng sang api_addr nội bộ mà máy chạy kiểm thử không phân giải được.
mo_cong() {
  local p=""
  for _ in $(seq 40); do
    p=$(kubectl -n $NS get pod -l app.kubernetes.io/name=openbao,openbao-active=true -o name 2>/dev/null | head -1)
    [ -n "$p" ] && break; sleep 3
  done
  kubectl -n $NS port-forward "$p" 18200:8200 >/dev/null 2>&1 & PF=$!
}
cho_dk() { # <số lần> <lệnh...>: thử lại mỗi 3 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 3; done; return 1
}

echo "== Cell-base (luồng mission-core → OpenBao), nạp ảnh, cài cert-manager $CM_VER và ESO $ESO_VER"
helm upgrade --install cell-base ../../charts/cell-base --set cell=kind --wait >/dev/null
NAP_NODES="$WORKERS" ./nap-anh.sh quay.io/jetstack/cert-manager-controller:$CM_VER quay.io/jetstack/cert-manager-webhook:$CM_VER \
  quay.io/jetstack/cert-manager-cainjector:$CM_VER quay.io/jetstack/cert-manager-startupapicheck:$CM_VER \
  ghcr.io/external-secrets/external-secrets:v$ESO_VER >/dev/null
# Ảnh dựng tại chỗ không có digest registry: dùng ID ảnh làm digest và gắn tên repo@sha256 trong containerd của node.
ID=$(docker image inspect -f '{{.Id}}' "$ANH")
docker save -o "$W/bao.tar" "$ANH"
for n in $WORKERS; do
  docker exec "$n" ctr -n k8s.io images ls -q | grep -qx "docker.io/${ANH%:*}@$ID" && continue
  docker exec -i "$n" ctr -n k8s.io images import --snapshotter=overlayfs - < "$W/bao.tar" >/dev/null
  docker exec "$n" ctr -n k8s.io images tag --force "docker.io/$ANH" "docker.io/${ANH%:*}@$ID" >/dev/null
done
rm -f "$W/bao.tar"
helm upgrade --install cert-manager jetstack/cert-manager --version $CM_VER -n cert-manager --create-namespace \
  --set crds.enabled=true --set prometheus.enabled=false --wait --timeout 5m >/dev/null && ok "cert-manager sẵn sàng"
helm upgrade --install external-secrets external-secrets/external-secrets --version $ESO_VER -n external-secrets \
  --create-namespace --wait --timeout 5m >/dev/null && ok "External Secrets Operator sẵn sàng"

echo "== CA khởi đầu (giả lập CA trung gian ký từ CA gốc ngoại tuyến), token SoftHSM và PIN"
kubectl apply -f - >/dev/null <<'EOF'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: { name: kind-tu-ky }
spec: { selfSigned: {} }
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: vt-noi-bo-goc-ca, namespace: cert-manager }
spec:
  isCA: true
  commonName: VT Noi Bo Goc (kind)
  secretName: vt-noi-bo-goc-ca
  privateKey: { algorithm: ECDSA, size: 384 }
  issuerRef: { kind: ClusterIssuer, name: kind-tu-ky }
EOF
kubectl -n cert-manager wait certificate/vt-noi-bo-goc-ca --for=condition=Ready --timeout=120s >/dev/null
kubectl -n cert-manager get secret vt-noi-bo-goc-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > "$W/ca.crt"
PIN=$(mk)
mkdir -p "$W/t"; chmod 777 "$W/t"
docker run --rm -e PIN="$PIN" -v "$W/t:/t" --entrypoint sh "$ANH" -c '
  printf "directories.tokendir = /t\n" > /tmp/s.conf && export SOFTHSM2_CONF=/tmp/s.conf
  softhsm2-util --init-token --free --label openbao-kind --pin "$PIN" --so-pin "$PIN" >/dev/null
  pkcs11-tool --module /usr/lib64/pkcs11/libsofthsm2.so --token-label openbao-kind --login --pin "$PIN" \
    --keypairgen --key-type rsa:3072 --label openbao-unseal --id 01 >/dev/null 2>&1'
# Ảnh không có tar: mỗi tệp token thành một khoá Secret, "<thư mục>__<tệp>" (khoá Secret không chứa "/").
TU=(); for f in "$W"/t/*/*; do TU+=(--from-file="$(basename "$(dirname "$f")")__$(basename "$f")=$f"); done
kubectl -n $NS create secret generic openbao-softhsm-token "${TU[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
printf %s "$PIN" | kubectl -n $NS create secret generic openbao-hsm-pin --from-file=pin=/dev/stdin --dry-run=client -o yaml | kubectl apply -f - >/dev/null
unset PIN; rm -rf "${W:?}/t"
SHA=$(docker run --rm --entrypoint sha256sum "$ANH" /usr/local/libexec/openbao/openbao-plugin-kms-pkcs11 | cut -d' ' -f1)
docker run --rm --entrypoint cat "$ANH" /usr/bin/bao > "$W/bao" && chmod +x "$W/bao"
bao() { "$W/bao" "$@"; }

echo "== Cài OpenBao 5 node và khởi tạo"
# Cài lại từ đầu: xoá release, PVC Raft cũ để khởi tạo sạch.
helm uninstall openbao -n $NS --wait >/dev/null 2>&1 || true
kubectl -n $NS wait --for=delete pod -l app.kubernetes.io/name=openbao --timeout=180s >/dev/null 2>&1 || true
for p in $(kubectl -n $NS get pvc -o name | grep -E 'data-openbao|audit-openbao' || true); do kubectl -n $NS delete "$p" --wait=true >/dev/null; done
OB=(-n $NS -f values-kind-openbao.yaml --set image.digest="$ID" --set seal.plugin.sha256="$SHA" --set-file tls.caBundle="$W/ca.crt")
helm upgrade --install openbao "$CHARTS/openbao" "${OB[@]}" >/dev/null && ok "helm install openbao"
cho_dk 60 sh -c "[ \$(kubectl -n $NS get pods -l app.kubernetes.io/name=openbao --field-selector=status.phase=Running -o name | wc -l) -eq 5 ]" \
  || hong "5 pod OpenBao chưa chạy"
# bao status trả 2 khi còn niêm phong (chưa khởi tạo), 1 khi chưa kết nối được.
cho_dk 40 sh -c "kubectl -n $NS exec openbao-0 -c openbao -- bao status >/dev/null 2>&1; [ \$? -eq 2 ]"
INIT=$(kubectl -n $NS exec openbao-0 -c openbao -- bao operator init -recovery-shares=1 -recovery-threshold=1 -format=json)
export BAO_TOKEN; BAO_TOKEN=$(printf %s "$INIT" | python3 -c 'import json,sys;print(json.load(sys.stdin)["root_token"])')
unset INIT
kiem_mo_khoa() {
  local mo=0
  for i in 0 1 2 3 4; do
    [ "$(kubectl -n $NS exec openbao-$i -c openbao -- bao status -format=json 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin)["sealed"])')" = False ] && mo=$((mo+1))
  done
  [ "$mo" -eq 5 ]
}
cho_dk 60 kiem_mo_khoa && ok "5 node tự mở khoá bằng HSM mềm qua PKCS#11 (không nhập khoá tay)" || hong "chưa đủ 5 node mở khoá"
so_peer=$(kubectl -n $NS exec openbao-0 -c openbao -- env BAO_TOKEN="$BAO_TOKEN" bao operator raft list-peers -format=json \
  | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["config"]["servers"]))')
[ "$so_peer" = 5 ] && ok "Raft có 5 thành viên (retry_join qua TLS)" || hong "Raft có $so_peer thành viên"
kubectl -n $NS exec openbao-0 -c openbao -- sh -c 'test -s /openbao/audit/audit.log' && ok "thiết bị kiểm toán ghi tệp" || hong "không có nhật ký kiểm toán"
kubectl -n $NS exec openbao-0 -c openbao -- env BAO_TOKEN="$BAO_TOKEN" bao audit disable tep >/dev/null 2>&1 \
  && hong "tắt được thiết bị kiểm toán" || ok "không tắt được thiết bị kiểm toán khai trong cấu hình"

mo_cong
export BAO_ADDR=https://127.0.0.1:18200 BAO_CACERT=$W/ca.crt BAO_TLS_SERVER_NAME=openbao.$NS.svc
cho_dk 20 bao status

echo "== Cấu hình dạng mã lần 1 (KV, auth kubernetes, chính sách, Transit, CSR của PKI) và bí mật CSDL"
ds=$(bao secrets list -format=json); grep -q '"kv/"' <<<"$ds" || bao secrets enable -path=kv -version=2 kv >/dev/null
for s in task okr; do for l in mig svc ro cdc; do bao kv put "kv/data-pg/pg-core/${l}_$s" password="$(mk)" >/dev/null; done; done
bao kv put kv/data-pg/pg-core/bao_admin password="$(mk)" >/dev/null
CELL=kind PG_CLUSTERS="" TENANTS=a PKI_CSR_OUT="$W/pki.csr" PATH="$W:$PATH" ../../openbao/cau-hinh.sh > "$W/ch1.log" 2>&1 \
  && ok "cau-hinh.sh lần 1 chạy xong" || { hong "cau-hinh.sh lần 1"; cat "$W/ch1.log"; }
# Giả lập nghi thức ký ngoại tuyến: một mount PKI gốc tạm ký CSR của CA trung gian rồi bị gỡ.
bao secrets enable -path=pki_goc_thu pki >/dev/null; bao secrets tune -max-lease-ttl=87600h pki_goc_thu >/dev/null
bao write -field=certificate pki_goc_thu/root/generate/internal common_name="VT Root CA (kind)" key_type=ec key_bits=384 ttl=87600h > "$W/goc.crt"
bao write -field=certificate pki_goc_thu/root/sign-intermediate csr=@"$W/pki.csr" format=pem_bundle ttl=43800h > "$W/tg.crt"
echo >> "$W/tg.crt"; cat "$W/goc.crt" >> "$W/tg.crt"
bao secrets disable pki_goc_thu >/dev/null

echo "== data-pg (pg-core) cài mới với ESO thật và bao_admin"
helm uninstall data-pg >/dev/null 2>&1 || true
kubectl -n data-pg delete cluster --all --wait=true --timeout=180s >/dev/null 2>&1 || true
kubectl -n data-pg delete pvc --all --wait=false >/dev/null 2>&1 || true
kubectl -n data-pg delete secret -l cnpg.io/reload=true >/dev/null 2>&1 || true
kubectl -n data-pg delete secret $(kubectl -n data-pg get secret -o name | grep -E 'pg-core-(mig|svc|ro|cdc)-' | sed 's|.*/||') >/dev/null 2>&1 || true
helm upgrade --install data-pg "$CHARTS/data-pg" -f values-kind-data-pg.yaml --set externalSecrets.enabled=true --timeout 15m >/dev/null \
  && ok "helm install data-pg (Job cấp quyền chạy xong)" || hong "helm install data-pg"
so_es=$(kubectl -n data-pg get externalsecret -o jsonpath='{range .items[*]}{.status.conditions[0].reason}{"\n"}{end}' | grep -c SecretSynced || true)
[ "$so_es" -eq 9 ] && ok "ESO tạo 9 Secret của data-pg từ KV qua ClusterSecretStore openbao-cell" || hong "ESO mới đồng bộ $so_es/9 Secret"
kubectl -n data-pg wait cluster/pg-core --for=condition=Ready --timeout=600s >/dev/null && ok "cụm pg-core sẵn sàng"
kubectl -n data-pg get secret pg-core-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > "$W/pg-ca.crt"
kubectl -n $NS create configmap openbao-pg-ca --from-file=pg-core.crt="$W/pg-ca.crt" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "== Xoá toàn bộ pod OpenBao: phải tự mở khoá lại (và nạp CA PostgreSQL)"
kubectl -n $NS delete pod -l app.kubernetes.io/name=openbao --wait=true >/dev/null
cho_dk 60 kiem_mo_khoa && ok "sau khi xoá cả 5 pod, cụm tự mở khoá lại" || hong "không tự mở khoá lại"
# Chờ bầu xong leader: mọi node đều biết địa chỉ node hoạt động.
co_leader() {
  for i in 0 1 2 3 4; do
    kubectl -n $NS exec openbao-$i -c openbao -- bao status -format=json 2>/dev/null \
      | python3 -c 'import json,sys;d=json.load(sys.stdin);sys.exit(0 if d.get("leader_address") else 1)' || return 1
  done
}
cho_dk 40 co_leader || hong "chưa bầu được leader"
kill "$PF" 2>/dev/null || true
mo_cong
cho_dk 20 bao status

echo "== Cấu hình dạng mã lần 2 (secret engine database, rotate-root, CA trung gian đã ký)"
CELL=kind PG_CORE_SVC_ROLES=svc_task,svc_okr TENANTS=a PKI_SIGNED_CERT="$W/tg.crt" PKI_CSR_OUT="$W/pki2.csr" PATH="$W:$PATH" ../../openbao/cau-hinh.sh > "$W/ch2.log" 2>&1 \
  && ok "cau-hinh.sh lần 2 chạy xong" || { hong "cau-hinh.sh lần 2"; cat "$W/ch2.log"; }
CELL=kind PG_CORE_SVC_ROLES=svc_task,svc_okr TENANTS=a PKI_CSR_OUT="$W/pki3.csr" PATH="$W:$PATH" ../../openbao/cau-hinh.sh > "$W/ch3.log" 2>&1 \
  && ok "cau-hinh.sh chạy lại lần 3 không lỗi (idempotent)" || { hong "cau-hinh.sh lần 3"; cat "$W/ch3.log"; }
PSQL_ANH=$(kubectl -n data-pg get cluster pg-core -o jsonpath='{.spec.imageName}')
cu=$(bao kv get -field=password kv/data-pg/pg-core/bao_admin)
kubectl -n data-pg exec pg-core-1 -c postgres -- env PGPASSWORD="$cu" psql -h pg-core-rw -U bao_admin -d khoi_tao \
  "sslmode=require" -tAc 'select 1' >/dev/null 2>&1 && hong "mật khẩu khởi tạo bao_admin vẫn dùng được" \
  || ok "rotate-root: mật khẩu khởi tạo bao_admin hết hiệu lực"
unset cu

echo "== Secret động PostgreSQL cho ServiceAccount mission-core"
kubectl -n core create serviceaccount mission-core --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n core create serviceaccount thu-khac --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n core create configmap pg-core-ca --from-file=ca.crt="$W/pg-ca.crt" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n core create configmap openbao-ca --from-file=ca.crt="$W/ca.crt" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n core delete pod thu-mission-core --ignore-not-found --wait=true >/dev/null
kubectl apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: thu-mission-core
  namespace: core
  labels: { app.kubernetes.io/name: mission-core }
spec:
  serviceAccountName: mission-core
  automountServiceAccountToken: false
  securityContext: { runAsNonRoot: true, runAsUser: 26, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: bao
      image: docker.io/$ANH
      command: [sleep, "3600"]
      env:
        - { name: BAO_ADDR, value: "https://openbao.$NS.svc:8200" }
        - { name: BAO_CACERT, value: /ca-bao/ca.crt }
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
      volumeMounts:
        - { name: token, mountPath: /var/run/secrets/openbao }
        - { name: ca-bao, mountPath: /ca-bao }
    - name: psql
      image: $PSQL_ANH
      command: [sleep, "3600"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
      volumeMounts: [{ name: ca-pg, mountPath: /ca-pg }]
  volumes:
    - name: token
      projected: { sources: [{ serviceAccountToken: { path: token, audience: openbao, expirationSeconds: 3600 } }] }
    - { name: ca-bao, configMap: { name: openbao-ca } }
    - { name: ca-pg, configMap: { name: pg-core-ca } }
EOF
kubectl -n core wait pod/thu-mission-core --for=condition=Ready --timeout=180s >/dev/null
CRED=$(kubectl -n core exec thu-mission-core -c bao -- sh -c \
  'BAO_TOKEN=$(bao write -field=token auth/k8s-cell-kind/login role=mission-core jwt=@/var/run/secrets/openbao/token) bao read -format=json database/creds/pg-core-mission' 2>"$W/cred.err") \
  && ok "pod mission-core đăng nhập auth kubernetes và nhận người dùng PostgreSQL động" || { hong "lấy credential động"; cat "$W/cred.err"; }
U=$(printf %s "$CRED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["username"])')
P=$(printf %s "$CRED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["password"])')
LEASE=$(printf %s "$CRED" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["lease_id"], d["lease_duration"])')
unset CRED
[ "${LEASE#* }" = 86400 ] && ok "lease 24 giờ (default_ttl)" || hong "lease_duration ${LEASE#* }"
LEASE=${LEASE% *}
noi() { kubectl -n core exec thu-mission-core -c psql -- env PGPASSWORD="$P" psql \
  "host=pg-core-pooler-rw.data-pg.svc dbname=td_a user=$U sslmode=verify-full sslrootcert=/ca-pg/ca.crt" -tAc "$1"; }
kq=$(noi "select current_user = '$U', (select rolbypassrls from pg_roles where rolname = current_user), pg_has_role('svc_task','member'), pg_has_role('svc_okr','member')" 2>&1 | tr '|' ' ')
[ "$kq" = "t f t t" ] && ok "kết nối qua Pooler (verify-full) bằng người dùng động: NOBYPASSRLS, thuộc svc_task, svc_okr" || hong "kết nối người dùng động: $kq"
[ "$(noi "select has_schema_privilege('task','USAGE')" 2>/dev/null || true)" = t ] && ok "người dùng động có quyền của svc_task trên schema task" || hong "thiếu quyền schema task"
JWT=$(kubectl -n core create token thu-khac --audience openbao --duration 10m)
bao write auth/k8s-cell-kind/login role=mission-core jwt="$JWT" >/dev/null 2>&1 \
  && hong "ServiceAccount khác đăng nhập được vai trò mission-core" || ok "ServiceAccount khác bị từ chối ở vai trò mission-core"
unset JWT
bao lease revoke "$LEASE" >/dev/null && sleep 2
noi "select 1" >/dev/null 2>&1 && hong "vẫn kết nối được sau khi thu hồi lease" || ok "thu hồi lease: người dùng động bị xoá, kết nối mới thất bại"
[ "$(kubectl -n data-pg exec pg-core-1 -c postgres -- psql -U postgres -tAc "select count(*) from pg_roles where rolname = '$U'" 2>/dev/null)" = 0 ] \
  && ok "vai trò động không còn trong pg_roles" || hong "vai trò động còn tồn tại"
unset P

echo "== cert-manager cấp chứng thư từ openbao-pki-cell"
kubectl apply -f - >/dev/null <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: thu-pki, namespace: core }
spec:
  secretName: thu-pki
  duration: 2160h
  privateKey: { algorithm: ECDSA, size: 256 }
  dnsNames: [thu.core.svc]
  issuerRef: { kind: ClusterIssuer, name: openbao-pki-cell }
EOF
kubectl -n core wait certificate/thu-pki --for=condition=Ready --timeout=120s >/dev/null \
  && ok "Certificate thu-pki Ready" || { hong "Certificate thu-pki"; kubectl -n core describe certificaterequest | tail -5; }
kubectl -n core get secret thu-pki -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -issuer 2>/dev/null \
  | grep -q "VT Cell kind Intermediate CA" && ok "chứng thư do CA trung gian của cell ký" || hong "sai bên cấp chứng thư"

echo "== ESO: mẫu ExternalSecret của vt-service (refresh 15m, Owner, Retain)"
GT=$(mk); bao kv put kv/kind/mission-core/thu api_key="$GT" >/dev/null
helm dependency build ../../charts/vt-service/tests/vt-service-example >/dev/null 2>&1 || true
helm template es ../../charts/vt-service/tests/vt-service-example --set externalSecret.enabled=true \
  --set 'externalSecret.data[0].secretKey=API_KEY' --set 'externalSecret.data[0].remoteRef.key=kind/mission-core/thu' \
  --set 'externalSecret.data[0].remoteRef.property=api_key' --show-only templates/all.yaml 2>/dev/null \
  | python3 -c '
import sys,yaml
for d in yaml.safe_load_all(sys.stdin):
    if d and d["kind"]=="ExternalSecret":
        d["metadata"]["namespace"]="core"; print("---"); print(yaml.safe_dump(d))' > "$W/es.yaml"
grep -q "deletionPolicy: Retain" "$W/es.yaml" && grep -q "refreshInterval: 15m" "$W/es.yaml" && grep -q "name: openbao-cell" "$W/es.yaml" \
  && ok "mẫu vt-service: openbao-cell, refreshInterval 15m, deletionPolicy Retain" || hong "mẫu ExternalSecret của vt-service"
kubectl delete -f "$W/es.yaml" --ignore-not-found >/dev/null
kubectl apply -f "$W/es.yaml" >/dev/null
TEN=$(python3 -c 'import yaml,sys;print([d for d in yaml.safe_load_all(open(sys.argv[1])) if d][0]["spec"]["target"]["name"])' "$W/es.yaml")
cho_dk 20 kubectl -n core get secret "$TEN" || true
[ "$(kubectl -n core get secret "$TEN" -o jsonpath='{.data.API_KEY}' | base64 -d)" = "$GT" ] \
  && ok "ESO tạo Secret $TEN từ KV đúng giá trị" || hong "Secret từ ESO sai hoặc chưa có"
unset GT

echo "== Xoay vòng khoá (CronJob) và chỉ số vt_key_age_days"
helm upgrade openbao "$CHARTS/openbao" "${OB[@]}" --set rotation.kekMaxAgeDays=0 >/dev/null
kubectl -n $NS delete job xoay-thu --ignore-not-found >/dev/null
kubectl -n $NS create job xoay-thu --from=cronjob/openbao-xoay-khoa >/dev/null
kubectl -n $NS wait job/xoay-thu --for=condition=Complete --timeout=180s >/dev/null && ok "Job xoay khoá chạy xong" \
  || { hong "Job xoay khoá"; kubectl -n $NS logs job/xoay-thu | tail -5; }
kubectl -n $NS logs job/xoay-thu | grep -q 'vt_key_age_days{khoa="kek-tenant-a",loai="kek"} 0' && ok "chỉ số vt_key_age_days có cho kek-tenant-a" || hong "thiếu chỉ số tuổi khoá"
[ "$(bao read -field=latest_version transit/keys/kek-tenant-a)" -ge 2 ] && ok "KEK quá hạn được xoay (phiên bản mới)" || hong "KEK chưa xoay"
helm upgrade openbao "$CHARTS/openbao" "${OB[@]}" >/dev/null
DEK=$(kubectl -n core exec thu-mission-core -c bao -- sh -c \
  'BAO_TOKEN=$(bao write -field=token auth/k8s-cell-kind/login role=mission-core jwt=@/var/run/secrets/openbao/token) bao write -field=ciphertext -f transit/datakey/wrapped/kek-tenant-a' 2>/dev/null || true)
case "$DEK" in vault:v*) ok "mission-core tạo DEK bọc bằng kek-tenant-a (transit/datakey/wrapped)";; *) hong "không tạo được DEK";; esac
kubectl -n core exec thu-mission-core -c bao -- sh -c \
  'BAO_TOKEN=$(bao write -field=token auth/k8s-cell-kind/login role=mission-core jwt=@/var/run/secrets/openbao/token) bao write -f transit/keys/kek-tenant-a/rotate' >/dev/null 2>&1 \
  && hong "mission-core xoay được KEK" || ok "mission-core không có quyền xoay KEK"

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

#!/usr/bin/env bash
# Dựng stack tối thiểu cho kiểm thử vận hành INF-25, INF-26 trên máy không có Docker hay kind: kube-apiserver và etcd
# thật (envtest), CRD tối thiểu, PostgreSQL thật, Keycloak vận hành giả lập (oidc_gia_lap.py), tenant-registry thật,
# bộ giả lập hệ ngoài của saga (provision-gia-lap), chart api-gateway (ApisixPluginConfig, ConfigMap bộ plugin từng mức)
# và RBAC của chart degrade-controller áp lên API server, degrade-controller chạy bằng token ServiceAccount của chart,
# Alertmanager của cell chạy đúng cấu hình observability-cell. Mọi trạng thái nằm ở $W (mặc định /dev/shm/vt-van-hanh).
# Dùng: dung-stack.sh [len|dung]. Cần công cụ của tai-cong-cu.sh ở $VT_CONG_CU.
set -euo pipefail
T=${VT_CONG_CU:-/dev/shm/vt-cong-cu}; W=${W:-/dev/shm/vt-van-hanh}
DIR=$(cd "$(dirname "$0")" && pwd); GOC=$(cd "$DIR/../../../.." && pwd); CH=$GOC/deploy/charts
export PATH=$T:$PATH KUBECONFIG=$W/kubeconfig
P_ETCD=12379 P_API=16443 P_PG=15432 P_OIDC=38080 P_REG=38081 P_GIA=38083 P_DC=18080 P_AM=19093 P_THONG_BAO=18090

dung() {
  local pids=()
  for f in "$W"/*.pid; do [ -f "$f" ] && pids+=("$(cat "$f")"); done
  [ ${#pids[@]} -gt 0 ] && kill "${pids[@]}" 2>/dev/null || true
  for _ in $(seq 60); do
    local con=0; for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && con=1; done
    [ $con = 0 ] && break; sleep 0.25
  done
  [ ${#pids[@]} -gt 0 ] && kill -9 "${pids[@]}" 2>/dev/null || true
  [ -d "$W/pgdata" ] && runuser -u nobody -- "$T/pg/bin/pg_ctl" -D "$W/pgdata" stop -m fast >/dev/null 2>&1 || true
  rm -rf "$W"
}
chay() { # $1 tên, còn lại là lệnh; ghi pid và nhật ký
  local ten=$1; shift
  ( cd "$W" && exec "$@" ) > "$W/$ten.log" 2>&1 &
  echo $! > "$W/$ten.pid"
}
cho() { # $1 mô tả, $2 lệnh kiểm
  for _ in $(seq 120); do eval "$2" >/dev/null 2>&1 && return 0; sleep 0.25; done
  echo "không lên: $1"; return 1
}
[ "${1:-len}" = dung ] && { dung; exit 0; }
dung; mkdir -p "$W"; cd "$W"

echo "== API server Kubernetes thật (etcd, kube-apiserver 1.34)"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=kube-apiserver" -addext "subjectAltName=IP:127.0.0.1,DNS:localhost" \
  -keyout api.key -out ca.crt >/dev/null 2>&1
openssl genrsa -out sa.key 2048 >/dev/null 2>&1; openssl rsa -in sa.key -pubout -out sa.pub >/dev/null 2>&1
for u in admin truc khach; do openssl rand -hex 16 | tr -d '\n' > $u.token; done
# Người trực: chỉ có nhóm OIDC của ca trực, quyền đến từ RoleBinding operate-app của chart security-cell (SEC-29).
{ echo "$(cat admin.token),admin,admin,system:masters"; echo "$(cat truc.token),nguoi-truc,nguoi-truc,\"oidc:platform-ops-sre-truc\""
  echo "$(cat khach.token),nguoi-khac,nguoi-khac"; } > tokens.csv
chay etcd etcd --data-dir "$W/etcd" --listen-client-urls http://127.0.0.1:$P_ETCD --advertise-client-urls http://127.0.0.1:$P_ETCD \
  --listen-peer-urls http://127.0.0.1:$((P_ETCD+1))
chay kube-apiserver kube-apiserver --etcd-servers=http://127.0.0.1:$P_ETCD --secure-port=$P_API --bind-address=127.0.0.1 \
  --tls-cert-file=ca.crt --tls-private-key-file=api.key --token-auth-file=tokens.csv --authorization-mode=RBAC \
  --service-account-issuer=https://kubernetes.default.svc --service-account-key-file=sa.pub --service-account-signing-key-file=sa.key \
  --service-cluster-ip-range=10.96.0.0/16 --disable-admission-plugins=ServiceAccount
cat > kubeconfig <<K
apiVersion: v1
kind: Config
clusters: [{name: van-hanh, cluster: {server: "https://127.0.0.1:$P_API", certificate-authority: $W/ca.crt}}]
users: [{name: admin, user: {tokenFile: $W/admin.token}}, {name: nguoi-truc, user: {tokenFile: $W/truc.token}}, {name: nguoi-khac, user: {tokenFile: $W/khach.token}}]
contexts: [{name: van-hanh, context: {cluster: van-hanh, user: admin}}, {name: truc, context: {cluster: van-hanh, user: nguoi-truc}}, {name: khach, context: {cluster: van-hanh, user: nguoi-khac}}]
current-context: van-hanh
K
cho "kube-apiserver" "kubectl get --raw /readyz"
kubectl apply -f "$DIR/crd-toi-thieu.yaml" >/dev/null
kubectl wait --for=condition=Established crd/apisixpluginconfigs.apisix.apache.org crd/scaledobjects.keda.sh >/dev/null
for ns in edge observability analytics core identity platform secure secure-data workers data-pg data-kafka data-search platform-privileged; do kubectl create ns $ns >/dev/null; done

# Vai trò vận hành thường trực của chart security-cell (view-app, operate-app) và một Deployment consumer để thử
# vtctl consumer restart (không có controller nên chỉ kiểm phần vá chú thích của template).
helm template sc "$CH/security-cell" 2>/dev/null | python3 -I "$DIR/loc_tai_nguyen.py" ClusterRole/view-app ClusterRole/operate-app RoleBinding \
  | kubectl apply -f - >/dev/null
kubectl -n workers create deployment scoring-service --image=registry.invalid/scoring-service@sha256:$(printf '0%.0s' $(seq 64)) >/dev/null

echo "== Cổng API: ApisixPluginConfig và bộ plugin từng mức từ chart api-gateway (degrade.controller.external=true)"
helm template gw "$CH/api-gateway" -n edge -f "$CH/api-gateway/tests/values-ci.yaml" --set degrade.controller.external=true 2>/dev/null \
  | python3 -I "$DIR/loc_tai_nguyen.py" ApisixPluginConfig ConfigMap/apisix-degrade-levels > gw.yaml
kubectl apply -f gw.yaml >/dev/null
mkdir -p levels; kubectl -n edge get cm apisix-degrade-levels -o json | python3 -c '
import json,sys,os
for k,v in json.load(sys.stdin)["data"].items(): open(os.path.join("levels",k),"w").write(v)'
# Tác vụ nền: một ScaledObject tạm dừng từ mức 2 và một ScaledObject không gắn nhãn (không được đụng tới).
kubectl apply -f - >/dev/null <<Y
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: tinh-lai-hang-loat, namespace: workers, labels: { vt.io/degrade-pause-from: "2" } }
spec: { scaleTargetRef: { name: tinh-lai-hang-loat }, triggers: [{ type: kafka, metadata: { topic: x } }] }
---
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: tinh-diem-tang-dan, namespace: workers }
spec: { scaleTargetRef: { name: tinh-diem-tang-dan }, triggers: [{ type: kafka, metadata: { topic: y } }] }
Y

echo "== degrade-controller: RBAC của chart, token ServiceAccount"
helm template dc "$CH/degrade-controller" -n edge -f "$GOC/deploy/environments/kind/degrade-controller/values.yaml" 2>/dev/null \
  | python3 -I "$DIR/loc_tai_nguyen.py" ServiceAccount Role RoleBinding > dc-rbac.yaml
kubectl apply -f dc-rbac.yaml >/dev/null
kubectl -n edge create token degrade-controller --duration=2h > dc.token
openssl rand -hex 24 | tr -d '\n' > webhook.token
kubectl -n edge create secret generic apisix-degrade-webhook --from-file=token=webhook.token >/dev/null
GW_NS=$(helm template dc "$CH/degrade-controller" -n edge -f "$GOC/deploy/environments/kind/degrade-controller/values.yaml" 2>/dev/null \
  | python3 -c 'import sys,yaml
for d in yaml.safe_load_all(sys.stdin):
  if d and d["kind"]=="Deployment":
    print([e["value"] for e in d["spec"]["template"]["spec"]["containers"][0]["env"] if e["name"]=="GATEWAY_NAMESPACES"][0])')
chay thong-bao python3 -I "$DIR/thu_thong_bao.py" $P_THONG_BAO "$W/thong-bao.jsonl"
chay degrade-controller env CELL=kind-dev PORT=$P_DC K8S_API=https://127.0.0.1:$P_API K8S_TOKEN_FILE="$W/dc.token" K8S_CA_FILE="$W/ca.crt" \
  GATEWAY_NAMESPACES="$GW_NS" JOB_NAMESPACES=workers LEVELS_DIR="$W/levels" WEBHOOK_TOKEN_FILE="$W/webhook.token" \
  HOLD_DOWN=${HOLD_DOWN:-20s} TICK_INTERVAL=2s RECONCILE_INTERVAL=10s ESCALATE_AFTER=${ESCALATE_AFTER:-15s} \
  NOTIFY_URL=http://127.0.0.1:$P_THONG_BAO/ degrade-controller
cho "degrade-controller" "curl -sf http://127.0.0.1:$P_DC/healthz"

echo "== Alertmanager của cell theo cấu hình observability-cell"
helm template oc "$CH/observability-cell" -n observability -f "$CH/observability-cell/tests/values-ci.yaml" 2>/dev/null | python3 -c '
import sys,yaml,base64
for d in yaml.safe_load_all(sys.stdin):
  if d and d["kind"]=="Secret" and d["metadata"]["name"]=="alertmanager-kps-alertmanager":
    s=d.get("stringData") or {k: base64.b64decode(v).decode() for k,v in d["data"].items()}
    print(s["alertmanager.yaml"])' \
  | sed -e "s#http://degrade-controller.edge.svc:8080#http://127.0.0.1:$P_DC#" \
        -e "s#/etc/alertmanager/secrets/apisix-degrade-webhook/token#$W/webhook.token#" > am.yaml
grep -q "127.0.0.1:$P_DC/alertmanager" am.yaml
chay alertmanager alertmanager --config.file=am.yaml --storage.path="$W/am-data" --web.listen-address=127.0.0.1:$P_AM --cluster.listen-address=
cho "Alertmanager" "curl -sf http://127.0.0.1:$P_AM/-/ready"

echo "== PostgreSQL, Keycloak vận hành giả lập, tenant-registry"
mkdir -p pgdata; chown nobody pgdata
runuser -u nobody -- "$T/pg/bin/initdb" -D "$W/pgdata" -U postgres --auth=trust -E UTF8 >/dev/null
runuser -u nobody -- "$T/pg/bin/pg_ctl" -D "$W/pgdata" -o "-p $P_PG -k $W/pgdata -c listen_addresses=127.0.0.1" -l "$W/pgdata/postgres.log" start >/dev/null
psql -q -h 127.0.0.1 -p $P_PG -U postgres -c "CREATE DATABASE mission" >/dev/null
chay oidc python3 -I "$DIR/oidc_gia_lap.py" $P_OIDC platform-ops
cho "OIDC giả lập" "curl -sf http://127.0.0.1:$P_OIDC/realms/platform-ops/.well-known/openid-configuration"
chay provision-gia-lap env HTTP_ADDR=127.0.0.1:$P_GIA provision-gia-lap
k32() { openssl rand -base64 32; }
# Bật dịch vụ chuyển cell (ADR-29) để thử đề nghị, bốn mắt, theo dõi, huỷ; kiểm thử không chạy bước sao chép dữ liệu.
python3 -c 'import json,sys; u="postgres://postgres@127.0.0.1:'$P_PG'/postgres?sslmode=disable"
json.dump({c: {"adminUrl": u, "peerConnInfo": "host=127.0.0.1 port='$P_PG' user=postgres"} for c in ("CELL-G1","CELL-G2")}, sys.stdout)' > move-cells.json
chay tenant-registry env HTTP_ADDR=127.0.0.1:$P_REG APP_ENV=dev AUTO_MIGRATE=true \
  MIGRATION_DIR="$GOC/services-go/tenant-registry/db/migration/registry" \
  DATABASE_URL="postgres://postgres@127.0.0.1:$P_PG/mission?sslmode=disable" \
  OIDC_ISSUERS=http://127.0.0.1:$P_OIDC/realms/platform-ops OIDC_JWKS_URL=http://127.0.0.1:$P_OIDC/realms/platform-ops/protocol/openid-connect/certs \
  ROUTING_COOKIE_KEY="$(k32)" C2_KEY="$(k32)" MOVE_CELL_DB_FILE="$W/move-cells.json" \
  PROVISION_KEYCLOAK_URL=http://127.0.0.1:$P_OIDC PROVISION_TOKEN_URL=http://127.0.0.1:$P_GIA/token PROVISION_CLIENT_ID=tenant-registry-provisioner \
  PROVISION_CLIENT_SECRET=gia-lap PROVISION_CORE_URL=http://127.0.0.1:$P_GIA PROVISION_ORG_URL=http://127.0.0.1:$P_GIA \
  PDNS_API_URL=http://127.0.0.1:$P_GIA PDNS_ZONE=okr.dev.local PDNS_API_KEY=gia-lap CERT_K8S_API=http://127.0.0.1:$P_GIA \
  CERT_NAMESPACE=apisix CERT_ISSUER=gia-lap CERT_TOKEN_FILE="$W/admin.token" PROVISION_BACKOFF=1s tenant-registry
cho "tenant-registry" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$P_REG/api/v1/registry/cells | grep -q 401"
# Seed cell dev của tenant-registry (chạy lại được) và một tenant đang vận hành ở CELL-G1.
psql -q -h 127.0.0.1 -p $P_PG -U postgres -d mission -f "$GOC/services-go/tenant-registry/db/seed/dev/R__seed_cells_dev.sql" >/dev/null
psql -q -h 127.0.0.1 -p $P_PG -U postgres -d mission -f "$DIR/seed-tenant.sql" >/dev/null

cat > moi-truong.sh <<E
export KUBECONFIG=$W/kubeconfig PATH=$T:\$PATH W=$W
export P_DC=$P_DC P_AM=$P_AM P_REG=$P_REG P_OIDC=$P_OIDC P_PG=$P_PG P_THONG_BAO=$P_THONG_BAO
E
echo "Stack đã lên ở $W (source $W/moi-truong.sh)"

#!/usr/bin/env bash
# Kiểm thử tầng dữ liệu của cell trên kind (INF-11): CNPG pg-core thu nhỏ qua Pooler thật, RLS qua PgBouncer,
# rò rỉ SET cấp phiên, từ chối kết nối không TLS, chuyển đổi dự phòng ≤ 30 s; Valkey Cluster 3 shard với TLS, ACL.
# Cần cụm kind đã dựng bằng tao-cum.sh, cell-base đã cài (default-deny), ảnh CNPG và Valkey đã nạp (nap-anh.sh).
# OpenSearch và ClickHouse không chạy trên kind vì tài nguyên (đĩa, bộ nhớ); hai chart đó chỉ kiểm tĩnh.
# Mật khẩu thử sinh ngẫu nhiên lúc chạy, không ghi ra tệp nào. Dùng: deploy/environments/kind/kiem-thu-du-lieu.sh
set -euo pipefail
cd "$(dirname "$0")"
CHARTS=../../charts/vt-platform/ha-tang
NS=data-pg
CNPG_VER=0.29.1
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
mk() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }

echo "== Cập nhật cell-base (luồng tới Pooler <cụm>-pooler-rw) và cài operator CNPG $CNPG_VER"
helm upgrade --install cell-base ../../charts/cell-base --set cell=kind --wait >/dev/null
helm upgrade --install cnpg cnpg/cloudnative-pg --version "$CNPG_VER" -n cnpg-system --create-namespace \
  --set image.repository=ghcr.io/cloudnative-pg/cloudnative-pg \
  --set image.tag=1.30.1@sha256:923c267ec29636db3bee20f993d0ec4973fa22998e1adad37da79e4d32b5bc07 \
  --set monitoring.podMonitorEnabled=false --wait --timeout 5m >/dev/null
kubectl -n cnpg-system rollout status deploy/cnpg-cloudnative-pg --timeout=180s >/dev/null && ok "operator CNPG sẵn sàng"

echo "== Secret vai trò (giả lập ESO) và cài data-pg thu nhỏ"
for s in task okr; do
  for l in mig svc ro cdc; do
    kubectl -n $NS create secret generic "pg-core-$l-$s" --type=kubernetes.io/basic-auth \
      --from-literal=username="${l}_$s" --from-literal=password="$(mk)" --dry-run=client -o yaml \
      | kubectl label --local -f - cnpg.io/reload=true -o yaml | kubectl apply -f - >/dev/null
  done
done
helm upgrade --install data-pg "$CHARTS/data-pg" -f values-kind-data-pg.yaml --timeout 15m >/dev/null \
  && ok "helm install data-pg (Job cấp quyền chạy xong)" || hong "helm install data-pg"
kubectl -n $NS wait cluster/pg-core --for=condition=Ready --timeout=600s >/dev/null && ok "cụm pg-core 3 instance sẵn sàng"
kubectl -n $NS rollout status deploy/pg-core-pooler-rw --timeout=180s >/dev/null && ok "Pooler pg-core-pooler-rw sẵn sàng"
[ "$(kubectl -n $NS get database pg-core-td-a -o jsonpath='{.status.applied}')" = true ] \
  && ok "CR Database td_a đã áp (schema task, okr)" || hong "CR Database td_a chưa áp"

PRIMARY=$(kubectl -n $NS get cluster pg-core -o jsonpath='{.status.currentPrimary}')
sqlp() { kubectl -n $NS exec -i "$PRIMARY" -c postgres -- psql -X -qtA -v ON_ERROR_STOP=1 -d td_a "$@"; }
# Vai trò đúng thuộc tính TL-03: NOINHERIT, NOBYPASSRLS, REPLICATION cho cdc_, giới hạn kết nối.
thuoc_tinh=$(sqlp -c "select string_agg(rolname||':'||rolinherit||rolbypassrls||rolreplication||':'||rolconnlimit, ' ' order by rolname) from pg_roles where rolname ~ '^(svc|ro|mig|cdc)_task$'")
[ "$thuoc_tinh" = "cdc_task:falsefalsetrue:5 mig_task:falsefalsefalse:10 ro_task:falsefalsefalse:20 svc_task:falsefalsefalse:2" ] \
  && ok "vai trò của schema task đúng thuộc tính ($thuoc_tinh)" || hong "thuộc tính vai trò: $thuoc_tinh"
[ "$(sqlp -c "select nspowner::regrole from pg_namespace where nspname='task'")" = mig_task ] \
  && ok "schema task thuộc mig_task" || hong "chủ schema task"

echo "== Bảng thử có RLS (tạo bằng mig_task như migration Flyway)"
sqlp <<'SQL' >/dev/null
SET ROLE mig_task;
DROP TABLE IF EXISTS task.cong_viec;
CREATE TABLE task.cong_viec (id bigserial PRIMARY KEY, tenant_id text NOT NULL, ten text);
ALTER TABLE task.cong_viec ENABLE ROW LEVEL SECURITY;
ALTER TABLE task.cong_viec FORCE ROW LEVEL SECURITY;
CREATE POLICY theo_tenant ON task.cong_viec
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
RESET ROLE;
INSERT INTO task.cong_viec (tenant_id, ten) VALUES ('A', 'việc của A'), ('B', 'việc của B');
SQL
ok "đã tạo task.cong_viec, quyền DML của svc_task đến từ ALTER DEFAULT PRIVILEGES của Job"

echo "== Máy khách trong namespace core (nhãn mission-core, đúng luồng cell-base)"
kubectl -n core delete pod khach-pg --ignore-not-found --wait >/dev/null
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: { name: khach-pg, namespace: core, labels: { app.kubernetes.io/name: mission-core } }
spec:
  automountServiceAccountToken: false
  securityContext: { runAsNonRoot: true, runAsUser: 26, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: psql
      image: ghcr.io/cloudnative-pg/postgresql@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc
      command: [sleep, infinity]
      resources: { requests: { cpu: 50m, memory: 64Mi }, limits: { cpu: 500m, memory: 128Mi } }
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
EOF
kubectl -n core wait pod/khach-pg --for=condition=Ready --timeout=120s >/dev/null
kubectl -n $NS get secret pg-core-ca -o jsonpath='{.data.ca\.crt}' | base64 -d | kubectl -n core exec -i khach-pg -- sh -c 'cat > /tmp/ca.crt'
SVC_PW=$(kubectl -n $NS get secret pg-core-svc-task -o jsonpath='{.data.password}' | base64 -d)
qp() { # qp <sslmode> <sql>: chạy qua Pooler rw bằng svc_task
  kubectl -n core exec khach-pg -- env PGPASSWORD="$SVC_PW" PGCONNECT_TIMEOUT=5 psql -X -qtA \
    "host=pg-core-pooler-rw.$NS.svc dbname=td_a user=svc_task sslmode=$1 sslrootcert=/tmp/ca.crt" -c "$2"; }

echo "== RLS qua PgBouncer (chế độ transaction, TLS verify-full)"
kq=$(qp verify-full "BEGIN; SELECT set_config('app.tenant_id','A',true); SELECT string_agg(ten, ',') FROM task.cong_viec; COMMIT;" | sed -n 2p || true)
[ "$kq" = "việc của A" ] && ok "tenant A chỉ thấy dòng của A qua Pooler (verify-full trên tên Pooler)" || hong "RLS tenant A: '$kq'"
kq=$(qp verify-full "SELECT count(*) FROM task.cong_viec" || true)
[ "$kq" = 0 ] && ok "không đặt app.tenant_id thì không thấy dòng nào" || hong "không đặt tenant thấy $kq dòng"
if qp verify-full "BEGIN; SELECT set_config('app.tenant_id','A',true); INSERT INTO task.cong_viec(tenant_id,ten) VALUES ('B','ghi lén'); COMMIT;" >/dev/null 2>&1; then
  hong "tenant A ghi được dòng của B"
else ok "tenant A không ghi được dòng mang tenant B (WITH CHECK)"; fi
if qp verify-full "CREATE TABLE task.x (id int)" >/dev/null 2>&1; then hong "svc_task tạo được bảng"; else ok "svc_task không có quyền DDL"; fi
kq=$(qp verify-full "BEGIN; SELECT set_config('app.tenant_id','A',true); SELECT count(*) FROM task.cong_viec; COMMIT;" | sed -n 2p || true)
kq2=$(qp verify-full "SELECT current_setting('app.tenant_id', true) IS NULL OR current_setting('app.tenant_id', true) = ''" || true)
[ "$kq" = 1 ] && [ "$kq2" = t ] && ok "set_config(..., true) không còn lại sau giao dịch trên kết nối máy chủ dùng chung" \
  || hong "set_config cục bộ còn sót ($kq, $kq2)"

echo "== SET cấp phiên rò sang client khác (default_pool_size=1): lý do quy ước cấm SET app.*"
qp verify-full "SET app.tenant_id = 'B'" >/dev/null || true
kq=$(qp verify-full "SELECT string_agg(ten, ',') FROM task.cong_viec" || true)
if [ "$kq" = "việc của B" ]; then
  ok "đã tái hiện: client sau (không đặt tenant) thấy dữ liệu B do SET của client trước còn trên kết nối máy chủ"
else
  hong "không tái hiện được rò rỉ SET cấp phiên (client sau thấy '$kq')"
fi
qp verify-full "RESET app.tenant_id" >/dev/null || true

echo "== Từ chối kết nối không TLS"
if qp disable "SELECT 1" >"$W/e1" 2>&1; then hong "Pooler nhận kết nối không TLS"; else ok "Pooler từ chối không TLS ($(grep -o 'SSL required\|server does not support SSL\|ssl' "$W/e1" | head -1))"; fi
kubectl -n $NS delete pod khach-noi-bo --ignore-not-found --wait >/dev/null
kubectl -n $NS run khach-noi-bo --restart=Never --labels=vt.io/db-grants=true \
  --image=ghcr.io/cloudnative-pg/postgresql@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":26,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"p","image":"ghcr.io/cloudnative-pg/postgresql@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc","command":["sleep","infinity"],"resources":{"limits":{"cpu":"200m","memory":"64Mi"}},"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
kubectl -n $NS wait pod/khach-noi-bo --for=condition=Ready --timeout=120s >/dev/null
if kubectl -n $NS exec khach-noi-bo -- env PGPASSWORD="$SVC_PW" PGCONNECT_TIMEOUT=5 psql -X "host=pg-core-rw dbname=td_a user=svc_task sslmode=disable" -c 'select 1' >"$W/e2" 2>&1; then
  hong "PostgreSQL nhận kết nối không TLS"
else
  grep -q 'pg_hba.conf rejects connection\|no encryption' "$W/e2" && ok "PostgreSQL từ chối không TLS bằng pg_hba (hostnossl reject)" || { hong "lỗi khác khi không TLS"; cat "$W/e2"; }
fi
kubectl -n $NS delete pod khach-noi-bo --wait=false >/dev/null

echo "== NetworkPolicy: namespace không phải khách của pg-core"
kubectl -n platform delete pod khach-la --ignore-not-found --wait >/dev/null
kubectl -n platform run khach-la --restart=Never \
  --image=ghcr.io/cloudnative-pg/postgresql@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":26,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"p","image":"ghcr.io/cloudnative-pg/postgresql@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc","command":["sleep","infinity"],"resources":{"limits":{"cpu":"200m","memory":"64Mi"}},"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
kubectl -n platform wait pod/khach-la --for=condition=Ready --timeout=120s >/dev/null
if kubectl -n platform exec khach-la -- env PGCONNECT_TIMEOUT=5 psql -X "host=pg-core-pooler-rw.$NS.svc dbname=td_a user=svc_task sslmode=require" -c 'select 1' >"$W/e3" 2>&1; then
  hong "namespace platform nối được pg-core-pooler-rw"
else
  grep -qi 'timeout\|timed out' "$W/e3" && ok "namespace platform bị chặn tới Pooler pg-core (hết thời gian kết nối)" || { hong "lỗi khác"; cat "$W/e3"; }
fi
kubectl -n platform delete pod khach-la --wait=false >/dev/null

echo "== Chuyển đổi dự phòng: xoá pod primary, ghi liên tục qua Pooler"
kubectl -n core exec khach-pg -- sh -c "rm -f /tmp/ghi.log; i=0; while [ \$i -lt 180 ]; do i=\$((i+1));
  if PGPASSWORD='$SVC_PW' PGCONNECT_TIMEOUT=2 psql -X -qtA 'host=pg-core-pooler-rw.$NS.svc dbname=td_a user=svc_task sslmode=verify-full sslrootcert=/tmp/ca.crt' \
     -c \"BEGIN; SELECT set_config('app.tenant_id','A',true); INSERT INTO task.cong_viec(tenant_id,ten) VALUES ('A','ghi '||\$i); COMMIT;\" >/dev/null 2>&1;
  then echo \"ok \$(date +%s.%N)\"; else echo \"loi \$(date +%s.%N)\"; fi >> /tmp/ghi.log; sleep 0.5; done" &
GHI=$!
sleep 8
CU=$(kubectl -n $NS get cluster pg-core -o jsonpath='{.status.currentPrimary}')
T0=$(date +%s.%N)
kubectl -n $NS delete pod "$CU" --wait=false >/dev/null
MOI=$CU
for _ in $(seq 1 120); do
  MOI=$(kubectl -n $NS get cluster pg-core -o jsonpath='{.status.currentPrimary}')
  [ "$MOI" != "$CU" ] && break; sleep 0.5
done
T1=$(date +%s.%N)
wait "$GHI" || true
kubectl -n core exec khach-pg -- cat /tmp/ghi.log > "$W/ghi.log"
read -r GAP SAU < <(python3 - "$W/ghi.log" "$T0" <<'PY'
import sys
t0 = float(sys.argv[2]); rows = [l.split() for l in open(sys.argv[1]) if l.strip()]
oks = [float(t) for s, t in rows if s == "ok"]
# Khoảng ngừng ghi dài nhất giữa hai lần ghi thành công liên tiếp kể từ lúc xoá primary.
moc = [max([t for t in oks if t <= t0] or [t0])] + [t for t in oks if t > t0]
gap = max([b - a for a, b in zip(moc, moc[1:])] or [float("inf")])
print(f"{gap:.1f}", len(moc) - 1)
PY
)
echo "  primary $CU → $MOI sau $(python3 -c "print(f'{$T1-$T0:.1f}')") s; khoảng ngừng ghi qua Pooler: $GAP s; số lần ghi thành công sau đó: $SAU"
if [ "$MOI" != "$CU" ] && python3 -c "import sys; sys.exit(0 if $GAP <= 30 else 1)" && [ "$SAU" -gt 0 ]; then
  ok "bản dự phòng lên primary và ghi tiếp qua Pooler sau $GAP s (≤ 30 s)"
else
  hong "chuyển đổi dự phòng: primary $CU → $MOI, ngừng ghi $GAP s"
fi
kubectl -n core delete pod khach-pg --wait=false >/dev/null

echo "== Valkey Cluster: TLS (CA tự ký, giả lập cert-manager) và ACL (giả lập ESO)"
VK=data-kafka
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj "/CN=vt-thu-ca" \
  -keyout "$W/ca.key" -out "$W/ca.crt" 2>/dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=valkey.$VK.svc" -keyout "$W/tls.key" -out "$W/tls.csr" 2>/dev/null
printf 'subjectAltName=DNS:valkey.%s.svc,DNS:*.valkey-headless.%s.svc,DNS:localhost\nextendedKeyUsage=serverAuth,clientAuth\n' "$VK" "$VK" > "$W/ext"
openssl x509 -req -in "$W/tls.csr" -CA "$W/ca.crt" -CAkey "$W/ca.key" -CAcreateserial -days 2 -extfile "$W/ext" -out "$W/tls.crt" 2>/dev/null
kubectl -n $VK create secret generic valkey-tls --from-file=tls.crt="$W/tls.crt" --from-file=tls.key="$W/tls.key" \
  --from-file=ca.crt="$W/ca.crt" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
declare -A PW; for u in vt-admin vt-replica vt-exporter mission-core apisix; do PW[$u]=$(mk); done
h() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
cat > "$W/users.acl" <<EOF
user default off
user vt-admin on #$(h "${PW[vt-admin]}") resetchannels ~* &* +@all
user vt-replica on #$(h "${PW[vt-replica]}") resetchannels +psync +replconf +ping
user vt-exporter on #$(h "${PW[vt-exporter]}") resetchannels -@all +@connection +info
user mission-core on #$(h "${PW[mission-core]}") resetchannels ~* &* +@all -@admin -@dangerous
user apisix on #$(h "${PW[apisix]}") resetchannels ~plugin-limit-* ~vt:revoked:* +@read +@write +@scripting +@connection -@dangerous
EOF
printf 'masterauth %s\n' "${PW[vt-replica]}" > "$W/sao-chep.conf"
kubectl -n $VK create secret generic valkey-acl --from-file=users.acl="$W/users.acl" --from-file=sao-chep.conf="$W/sao-chep.conf" \
  --from-literal=vt-admin="${PW[vt-admin]}" --from-literal=vt-exporter="${PW[vt-exporter]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n $VK delete job valkey-tao-cum --ignore-not-found >/dev/null
helm template valkey "$CHARTS/valkey" -f values-kind-valkey.yaml > "$W/vk.yaml"
python3 -c 'import sys,yaml; d=[x for x in yaml.safe_load_all(open(sys.argv[1])) if x and x["kind"] not in ("Certificate","ExternalSecret")]; yaml.safe_dump_all(d,open(sys.argv[1],"w"))' "$W/vk.yaml"
kubectl apply -f "$W/vk.yaml" >/dev/null
kubectl -n $VK wait job/valkey-tao-cum --for=condition=Complete --timeout=600s >/dev/null \
  && ok "Job tạo cụm Valkey chạy xong ($(kubectl -n $VK logs job/valkey-tao-cum | tail -1))" || { hong "Job tạo cụm Valkey"; kubectl -n $VK logs job/valkey-tao-cum | tail -5; }
vk() { kubectl -n $VK exec valkey-a-0 -c valkey -- env REDISCLI_AUTH="${PW[$1]}" valkey-cli --no-auth-warning --tls --cacert /tls/ca.crt --user "$1" -h "$2.valkey-headless.$VK.svc" "${@:3}"; }
vk vt-admin valkey-a-0 cluster info | grep -q 'cluster_state:ok' && ok "cluster_state:ok, 3 shard" || hong "cụm Valkey chưa ok"
vk vt-admin valkey-a-0 cluster nodes > "$W/nodes"
python3 - "$W/nodes" > "$W/ghep" <<'PY'
import sys
rows = [l.split() for l in open(sys.argv[1]) if l.strip()]
ten = {r[0]: r[1].split(",")[1].split(".")[0] for r in rows}
for r in rows:
    if "slave" in r[2]:
        print(ten[r[3]], ten[r[0]])
PY
lech=0
while read -r chinh phu; do
  zc=$(kubectl get node "$(kubectl -n $VK get pod "$chinh" -o jsonpath='{.spec.nodeName}')" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}')
  zp=$(kubectl get node "$(kubectl -n $VK get pod "$phu" -o jsonpath='{.spec.nodeName}')" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}')
  echo "  shard: primary $chinh ($zc) ← replica $phu ($zp)"; [ "$zc" != "$zp" ] || lech=1
done < "$W/ghep"
[ "$(wc -l < "$W/ghep")" = 3 ] && [ $lech = 0 ] && ok "mỗi primary có một replica ở vùng khác" || hong "bố trí replica"
kq=$(kubectl -n $VK exec valkey-a-0 -c valkey -- valkey-cli --tls --cacert /tls/ca.crt -h valkey-b-0.valkey-headless.$VK.svc get x 2>&1 || true)
echo "$kq" | grep -q NOAUTH && ok "không xác thực bị từ chối (NOAUTH)" || hong "không xác thực: $kq"
kq=$(kubectl -n $VK exec valkey-a-0 -c valkey -- sh -c "valkey-cli -h valkey-b-0.valkey-headless.$VK.svc ping 2>&1" || true)
echo "$kq" | grep -q PONG && hong "kết nối không TLS được nhận" || ok "kết nối không TLS bị từ chối"
vk mission-core valkey-a-0 -c set "{t1}:task:x" 1 >/dev/null; kq=$(vk mission-core valkey-c-0 -c get "{t1}:task:x")
[ "$kq" = 1 ] && ok "mission-core ghi, đọc qua cụm (chuyển hướng giữa shard)" || hong "mission-core get: $kq"
kq=$(vk apisix valkey-a-0 -c get "{t1}:task:x" 2>&1 || true)
echo "$kq" | grep -q NOPERM && ok "apisix không đọc được khoá ngoài tiền tố được cấp (NOPERM)" || hong "apisix: $kq"
kq=$(vk mission-core valkey-a-0 -c config get maxmemory 2>&1 || true)
echo "$kq" | grep -q NOPERM && ok "mission-core không chạy được lệnh quản trị (CONFIG)" || hong "mission-core CONFIG: $kq"

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

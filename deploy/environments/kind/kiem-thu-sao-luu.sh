#!/usr/bin/env bash
# Kiểm thử chạy thật sao lưu PostgreSQL trên kind (INF-17; TL-04 mục 8.2, 8.7, 8.9, bảng lịch sao lưu; NFR-09):
#   - Kho sao lưu ceph-sn-bk giả lập bằng một cụm Ceph 19 (quay.io/ceph/demo, container ceph-bk, 172.18.0.51) trong mạng
#     docker "kind"; khoi-tao-multisite.sh tạo realm, người dùng bk-cell-kind-*; chart rgw-cell với values-kho-sao-luu.yaml
#     khai báo bucket Object Lock COMPLIANCE, vòng đời, chính sách theo người dùng.
#   - CNPG 1.30.1 + plugin Barman Cloud 0.15.1 + cert-manager; chart data-pg dựng pg-core 3 bản, lưu WAL liên tục
#     lên s3://bk-cell-kind-pg/pg-core (nén zstd); Backup theo yêu cầu (prefer-standby) hoàn tất.
#   - PITR: ghi 1.000 dòng, chốt mốc T1, xoá 990 dòng; cụm khôi phục tới T1 có lại đủ 1.000 dòng.
#   - Khoá của cell không xoá được phiên bản, không đổi cấu hình Object Lock; quản trị kho cũng bị Object Lock chặn.
#   - Chart backup-cell: CronJob restore-check chạy thật, cụm tạm khôi phục tới mốc (có ghi sau mốc) và tới hết WAL
#     (cụm nguồn nhàn rỗi), kiểm SQL đạt, đẩy chỉ số lên Pushgateway (success 1, target_reached 1 và 0), cụm tạm bị
#     xoá; bản nền mới nhất bị ghi rác (một phiên bản mới, phiên bản gốc còn khoá) thì restore-check báo hỏng (success 0).
# Cần cụm kind gọn (KIND_CONFIG=cluster-gon.yaml KHONG_CILIUM=1 ./tao-cum.sh), docker, helm (repo jetstack, cnpg),
# kubectl, openssl, curl. Ảnh công cụ backup-tools dựng từ deploy/charts/backup-cell/anh (DOCKER_BUILD_OPTS cho máy
# sau proxy, ví dụ "--network host --build-arg HTTPS_PROXY=..."). Khoá S3 sinh ngẫu nhiên vào OpenBao giả ($W/kho).
# Dùng: deploy/environments/kind/kiem-thu-sao-luu.sh   (GIU_LAI=1 giữ cụm, container Ceph và thư mục khoá)
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
RGW_CHART=$GOC/deploy/charts/rgw-cell
CLUSTER=${CLUSTER:-vt-cell-kind}
NODE=$CLUSTER-worker
CEPH_ANH=quay.io/ceph/demo:latest-squid
AWS_ANH=mirror.gcr.io/amazon/aws-cli@sha256:1a491b258590cc66866cd550f592edff80082f44daf1d903820daeca2f4954de
CNPG_ANH=ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1@sha256:923c267ec29636db3bee20f993d0ec4973fa22998e1adad37da79e4d32b5bc07
PG_ANH=ghcr.io/cloudnative-pg/postgresql:17.6-minimal-bookworm@sha256:d45d7a78ec14e80476974c6b123bf929ddb5908f4f4581c275bbe85e61fb93bc
PGB_ANH=ghcr.io/cloudnative-pg/pgbouncer:1.24.1@sha256:9bce905b0be1ce9c0de8c694ef0cfb8d3707f7dbfb4b51213fc867b5d66b87c3
BARMAN_VER=v0.15.1
CM_VER=v1.21.2
PGW_ANH=docker.io/prom/pushgateway:v1.11.1
BK_ANH=localhost/mission/backup-tools:dev
SN=172.18.0.51
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
export KHO_BAO=$W/kho
don() {
  if [ "${GIU_LAI:-0}" != 1 ]; then
    kubectl -n data-pg-restore delete cluster --all --wait=false >/dev/null 2>&1 || true
    helm uninstall backup-cell >/dev/null 2>&1 || true
    helm uninstall data-pg >/dev/null 2>&1 || true
    helm uninstall bk -n secure-data >/dev/null 2>&1 || true
    docker rm -f ceph-bk >/dev/null 2>&1 || true
    rm -rf "${W:?}"
  else echo "Giữ lại cụm, container ceph-bk và thư mục khoá $W"; fi
}
trap don EXIT
cho() { local n=$1; shift; for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1; }
khoa() { cat "$KHO_BAO/kv/ceph/cell-kind/$1/$2"; }
# s3 <uid> <lệnh s3api…>: in kết quả và mã lỗi S3 (RGW trả <Message> rỗng làm aws-cli 2.31 hỏng, nên đọc mã từ --debug).
s3() {
  local uid=$1 nk; shift
  nk=$(docker run --rm --network kind -v "$W:/w" -e AWS_ACCESS_KEY_ID="$(khoa "$uid" access-key)" \
    -e AWS_SECRET_ACCESS_KEY="$(khoa "$uid" secret-key)" "$AWS_ANH" --debug \
    --endpoint-url "http://$SN:8080" --region cell-kind-zg s3api "$@" 2>&1 >"$W/s3.out") || true
  cat "$W/s3.out"
  grep -o '<Code>[^<]*</Code>' <<<"$nk" | tail -1 || true
}
# NetworkPolicy chỉ có trên kind: kindnet không có CiliumNetworkPolicy mà chart dùng để gọi kube-apiserver.
ra_apiserver() { # <namespace> <podSelector JSON>
  kubectl apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: kind-ra-kube-apiserver, namespace: $1 }
spec:
  podSelector: $2
  policyTypes: [Egress]
  egress:
    - to: [{ ipBlock: { cidr: 172.18.0.0/16 } }]
      ports: [{ port: 6443, protocol: TCP }]
EOF
}
pg() { # <CSDL> <SQL>: chạy trên primary của pg-core
  kubectl -n data-pg exec -i "$(kubectl -n data-pg get cluster pg-core -o jsonpath='{.status.currentPrimary}')" -c postgres -- \
    psql -X -At -v ON_ERROR_STOP=1 -d "$1" -c "$2"
}
chi_so() { kubectl -n observability exec deploy/pushgateway -- wget -qO- http://localhost:9091/metrics 2>/dev/null | grep "^$1{.*cluster=\"pg-core\"" | awk '{print $2}'; }
chay_rc() { # <tên job>: chạy restore-check một lần, in nhật ký
  kubectl -n data-pg-restore delete job -l vt.io/kiem-thu=sao-luu >/dev/null 2>&1 || true
  kubectl -n data-pg-restore create job "$1" --from=cronjob/restore-check >/dev/null
  kubectl -n data-pg-restore label job "$1" vt.io/kiem-thu=sao-luu >/dev/null
  until [ -n "$(kubectl -n data-pg-restore get job "$1" -o jsonpath='{.status.conditions[?(@.status=="True")].type}')" ]; do sleep 5; done
  kubectl -n data-pg-restore logs "job/$1" | grep -v NOTICE
}
backup() { # <tên>: Backup theo yêu cầu, in backupId
  kubectl apply -f - >/dev/null <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata: { name: $1, namespace: data-pg }
spec: { cluster: { name: pg-core }, method: plugin, target: prefer-standby, pluginConfiguration: { name: barman-cloud.cloudnative-pg.io } }
EOF
  until kubectl -n data-pg get backup "$1" -o jsonpath='{.status.phase}' | grep -qE 'completed|failed'; do sleep 5; done
  kubectl -n data-pg get backup "$1" -o jsonpath='{.status.phase} {.status.backupId}'
}

echo "== Kho sao lưu Ceph (ceph-bk, $SN)"
cat > "$W/bao" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = kv ] || exit 2
case $2 in
  get) t=${3#-field=}; f="$KHO_BAO/$4/$t"; [ -f "$f" ] || exit 2; cat "$f" ;;
  put) d=$3; shift 3; mkdir -p "$KHO_BAO/$d"; for kv in "$@"; do printf '%s' "${kv#*=}" > "$KHO_BAO/$d/${kv%%=*}"; done ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$W/bao"
docker rm -f ceph-bk >/dev/null 2>&1 || true
docker run -d --name ceph-bk --network kind --ip $SN --tmpfs /var/lib/ceph:rw,size=3g -e MON_IP=$SN \
  -e CEPH_PUBLIC_NETWORK=172.18.0.0/16 -e DEMO_DAEMONS=osd,rgw -e RGW_FRONTEND_PORT=8080 -e OSD_SIZE=1024 \
  -e CEPH_DEMO_UID=demo -e CEPH_DEMO_ACCESS_KEY="$(openssl rand -hex 10)" -e CEPH_DEMO_SECRET_KEY="$(openssl rand -hex 20)" \
  $CEPH_ANH demo >/dev/null
cho 60 sh -c "docker exec ceph-bk ceph -s | grep -q 'rgw: 1 daemon'" || { hong "Ceph chưa lên"; exit 1; }
env CELL=kind VAI=hl ENDPOINT_HL=http://$SN:8080 BAO="$W/bao" POOL_HDD=default.rgw.buckets.data RGW_ADMIN="docker exec ceph-bk radosgw-admin" \
  NGUOI_DUNG="bk-cell-kind-pg bk-cell-kind-ch bk-cell-kind-k8s bk-cell-kind-ev bk-cell-kind-os bk-cell-kind-bao bk-cell-kind-git" \
  "$RGW_CHART/scripts/khoi-tao-multisite.sh" > "$W/ms.log" 2>&1 || { hong "khoi-tao-multisite.sh"; tail -5 "$W/ms.log"; exit 1; }
# radosgw của container demo phải khởi động lại để phục vụ zone mới (cụm thật: ceph orch redeploy rgw).
lenh=$(docker exec ceph-bk ps -eo args | grep '^radosgw' | head -1 | sed 's/ --rgw-zone=.*//')
docker exec ceph-bk pkill -x radosgw || true
sleep 2
docker exec -d ceph-bk sh -c "$lenh --rgw-zone=cell-kind-hl --rgw-realm=cell-kind --rgw-zonegroup=cell-kind-zg > /tmp/rgw.log 2>&1"
cho 20 docker exec ceph-bk curl -sf -o /dev/null http://localhost:8080/ || true
NAP_NODES=$NODE ./nap-anh.sh "$AWS_ANH" >/dev/null
kubectl create ns secure-data --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n secure-data create secret generic rgw-quan-tri --from-file=access-key="$KHO_BAO/kv/ceph/cell-kind/kho-quan-tri-cell-kind/access-key" \
  --from-file=secret-key="$KHO_BAO/kv/ceph/cell-kind/kho-quan-tri-cell-kind/secret-key" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install bk "$RGW_CHART" -n secure-data -f "$RGW_CHART/values-kho-sao-luu.yaml" -f values-kind-rgw-sao-luu.yaml >/dev/null
J=$(kubectl -n secure-data get job -o name | head -1)
if kubectl -n secure-data wait "$J" --for=condition=Complete --timeout=300s >/dev/null && kubectl -n secure-data logs "$J" | grep -q 'lỗi: 0'; then
  ok "rgw-cell khai báo $(kubectl -n secure-data logs "$J" | grep -c 'đã áp') bucket kho sao lưu (versioning, Object Lock, vòng đời, chính sách)"
else hong "Job khai báo bucket kho sao lưu"; kubectl -n secure-data logs "$J" --tail=15 || true; exit 1; fi

echo "== CNPG, Barman Cloud, pg-core"
./nap-anh.sh quay.io/jetstack/cert-manager-controller:$CM_VER quay.io/jetstack/cert-manager-webhook:$CM_VER \
  quay.io/jetstack/cert-manager-cainjector:$CM_VER quay.io/jetstack/cert-manager-startupapicheck:$CM_VER \
  $CNPG_ANH $PG_ANH $PGB_ANH ghcr.io/cloudnative-pg/plugin-barman-cloud:$BARMAN_VER \
  ghcr.io/cloudnative-pg/plugin-barman-cloud-sidecar:$BARMAN_VER $PGW_ANH >/dev/null
helm upgrade --install cert-manager jetstack/cert-manager --version $CM_VER -n cert-manager --create-namespace \
  --set crds.enabled=true --wait --timeout 6m >/dev/null
helm upgrade --install cnpg cnpg/cloudnative-pg --version 0.29.1 -n cnpg-system --create-namespace \
  --set image.repository="${CNPG_ANH%%:*}" --set image.tag="${CNPG_ANH#*:}" --set monitoring.podMonitorEnabled=false --wait --timeout 5m >/dev/null
curl -sSfL -o "$W/barman.yaml" https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/$BARMAN_VER/manifest.yaml
kubectl apply -f "$W/barman.yaml" >/dev/null
kubectl -n cnpg-system rollout status deploy/barman-cloud --timeout=180s >/dev/null
kubectl create ns data-pg --dry-run=client -o yaml | kubectl apply -f - >/dev/null
mk() { openssl rand -hex 16; }
for s in task okr; do for l in mig svc ro cdc; do
  kubectl -n data-pg create secret generic "pg-core-$l-$s" --type=kubernetes.io/basic-auth --from-literal=username="${l}_$s" \
    --from-literal=password="$(mk)" --dry-run=client -o yaml | kubectl label --local -f - cnpg.io/reload=true -o yaml | kubectl apply -f - >/dev/null
done; done
kubectl -n data-pg create secret generic bk-sn-s3-pg --from-file=ACCESS_KEY_ID="$KHO_BAO/kv/ceph/cell-kind/bk-cell-kind-pg/access-key" \
  --from-file=ACCESS_SECRET_KEY="$KHO_BAO/kv/ceph/cell-kind/bk-cell-kind-pg/secret-key" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ra_apiserver data-pg '{ matchExpressions: [{ key: cnpg.io/cluster, operator: Exists }] }'
helm upgrade --install data-pg ../../charts/vt-platform/ha-tang/data-pg -f values-kind-data-pg.yaml --set global.cell=kind \
  --set cilium=false --timeout 20m >/dev/null
if kubectl -n data-pg wait cluster/pg-core --for=condition=Ready --timeout=900s >/dev/null; then ok "pg-core sẵn sàng, plugin Barman Cloud gắn vào"
else hong "pg-core chưa sẵn sàng"; exit 1; fi
pg td_a "CREATE TABLE IF NOT EXISTS public.kt_sao_luu (id int PRIMARY KEY); TRUNCATE public.kt_sao_luu" >/dev/null
pg postgres "SELECT pg_switch_wal()" >/dev/null
if cho 24 sh -c "docker run --rm --network kind -e AWS_ACCESS_KEY_ID=$(khoa bk-cell-kind-pg access-key) -e AWS_SECRET_ACCESS_KEY=$(khoa bk-cell-kind-pg secret-key) $AWS_ANH --endpoint-url http://$SN:8080 --region cell-kind-zg s3 ls --recursive s3://bk-cell-kind-pg/pg-core/pg-core/wals/ | grep -q '\.zst$'"; then
  ok "WAL lưu liên tục lên s3://bk-cell-kind-pg/pg-core (zstd)"
else hong "chưa thấy WAL trên kho"; fi
KQ=$(backup thu-nen-1)
[[ "$KQ" == completed* ]] && ok "Backup theo yêu cầu (prefer-standby) hoàn tất: ${KQ#* }" || hong "Backup: $KQ"

echo "== PITR"
pg td_a "INSERT INTO public.kt_sao_luu SELECT generate_series(1, 1000)" >/dev/null
pg postgres "SELECT pg_switch_wal()" >/dev/null
sleep 5; T1=$(date -u '+%Y-%m-%d %H:%M:%S+00'); sleep 5
pg td_a "DELETE FROM public.kt_sao_luu WHERE id > 10" >/dev/null
pg postgres "SELECT pg_switch_wal()" >/dev/null
sleep 20
kubectl apply -f - >/dev/null <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: pitr-thu, namespace: data-pg }
spec:
  instances: 1
  imageName: $PG_ANH
  storage: { storageClass: standard, size: 2Gi }
  bootstrap: { recovery: { source: nguon, recoveryTarget: { targetTime: "$T1" } } }
  externalClusters:
    - name: nguon
      plugin: { name: barman-cloud.cloudnative-pg.io, parameters: { barmanObjectName: bk-sn-pg-core, serverName: pg-core } }
EOF
if kubectl -n data-pg wait cluster/pitr-thu --for=condition=Ready --timeout=900s >/dev/null; then
  n=$(kubectl -n data-pg exec -i pitr-thu-1 -c postgres -- psql -X -At -d td_a -c "SELECT count(*) FROM public.kt_sao_luu")
  [ "$n" = 1000 ] && ok "PITR tới $T1: 1000 dòng (nguồn hiện còn $(pg td_a 'SELECT count(*) FROM public.kt_sao_luu'))" || hong "PITR có $n dòng, mong 1000"
else hong "cụm PITR chưa sẵn sàng"; fi
kubectl -n data-pg delete cluster pitr-thu --wait=false >/dev/null

echo "== Object Lock trên kho sao lưu"
k=$(docker run --rm --network kind -e AWS_ACCESS_KEY_ID="$(khoa bk-cell-kind-pg access-key)" -e AWS_SECRET_ACCESS_KEY="$(khoa bk-cell-kind-pg secret-key)" \
  "$AWS_ANH" --endpoint-url http://$SN:8080 --region cell-kind-zg s3api list-object-versions --bucket bk-cell-kind-pg --prefix pg-core/pg-core/base/ \
  --query 'Versions[?ends_with(Key, `backup.info`)] | [0].[Key,VersionId]' --output text)
read -r KEY VID <<<"$k"
[ "$(s3 bk-cell-kind-pg delete-object --bucket bk-cell-kind-pg --key "$KEY" --version-id "$VID" | tail -1)" = "<Code>AccessDenied</Code>" ] \
  && ok "khoá cell không xoá được phiên bản bản nền" || hong "khoá cell xoá được phiên bản"
[ "$(s3 bk-cell-kind-pg put-object-lock-configuration --bucket bk-cell-kind-pg --object-lock-configuration '{"ObjectLockEnabled":"Enabled"}' | tail -1)" = "<Code>AccessDenied</Code>" ] \
  && ok "khoá cell không đổi được cấu hình Object Lock" || hong "khoá cell đổi được Object Lock"
[ "$(s3 kho-quan-tri-cell-kind delete-object --bucket bk-cell-kind-pg --key "$KEY" --version-id "$VID" | tail -1)" = "<Code>AccessDenied</Code>" ] \
  && ok "quản trị kho cũng không xoá được phiên bản đang khoá (COMPLIANCE)" || hong "quản trị kho xoá được phiên bản đang khoá"
han=$(s3 bk-cell-kind-pg get-object-retention --bucket bk-cell-kind-pg --key "$KEY" --version-id "$VID" | jq -r '.Retention | "\(.Mode) \(.RetainUntilDate)"')
[[ "$han" == COMPLIANCE* ]] && ok "phiên bản giữ $han" || hong "hạn giữ: $han"

echo "== restore-check của backup-cell"
docker image inspect $BK_ANH >/dev/null 2>&1 || docker build ${DOCKER_BUILD_OPTS:-} -t $BK_ANH "$GOC/deploy/charts/backup-cell/anh" >/dev/null
NAP_NODES=$NODE ./nap-anh.sh $BK_ANH >/dev/null
kubectl create ns observability --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - >/dev/null <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: { name: pushgateway, namespace: observability, labels: { app: pushgateway } }
spec:
  replicas: 1
  selector: { matchLabels: { app: pushgateway } }
  template:
    metadata: { labels: { app: pushgateway } }
    spec: { containers: [{ name: pushgateway, image: $PGW_ANH, ports: [{ containerPort: 9091 }] }] }
---
apiVersion: v1
kind: Service
metadata: { name: pushgateway, namespace: observability }
spec: { selector: { app: pushgateway }, ports: [{ port: 9091, targetPort: 9091 }] }
EOF
kubectl -n observability rollout status deploy/pushgateway --timeout=120s >/dev/null
helm upgrade --install backup-cell ../../charts/backup-cell -f values-kind-backup-cell.yaml \
  --set pushgateway=http://pushgateway.observability.svc:9091 --set cilium=false --set restoreCheck.luiGiay=120 >/dev/null
for ns in data-pg-restore sao-luu; do ra_apiserver $ns '{}'; done
kubectl -n data-pg-restore create secret generic bk-sn-s3-pg --from-file=ACCESS_KEY_ID="$KHO_BAO/kv/ceph/cell-kind/bk-cell-kind-pg/access-key" \
  --from-file=ACCESS_SECRET_KEY="$KHO_BAO/kv/ceph/cell-kind/bk-cell-kind-pg/secret-key" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# (a) Có ghi sau mốc (mốc = bây giờ − 120 s): khôi phục đúng tới mốc.
pg td_a "INSERT INTO public.kt_sao_luu SELECT generate_series(2001, 2100)" >/dev/null
pg postgres "SELECT pg_switch_wal()" >/dev/null
sleep 15
NK=$(chay_rc rc-thu-moc)
if grep -q '^ĐẠT' <<<"$NK" && [ "$(chi_so backup_restore_check_success)" = 1 ] && [ "$(chi_so backup_restore_check_target_reached)" = 1 ]; then
  ok "restore-check tới mốc: đạt, success 1, target_reached 1 ($(grep -o 'sau [0-9]*s' <<<"$NK"))"
else hong "restore-check tới mốc"; echo "$NK" | tail -8; fi
cho 24 sh -c "[ -z \"\$(kubectl -n data-pg-restore get cluster,objectstore -o name)\" ]" && ok "cụm tạm và ObjectStore tạm đã xoá" || hong "còn cụm tạm"
# (b) Cụm nguồn nhàn rỗi quá LUI_GIAY: không có giao dịch sau mốc, khôi phục tới hết WAL.
sleep 130
NK=$(chay_rc rc-thu-nhan-roi)
if grep -q 'cụm nguồn nhàn rỗi' <<<"$NK" && grep -q '^ĐẠT' <<<"$NK" && [ "$(chi_so backup_restore_check_target_reached)" = 0 ]; then
  ok "restore-check khi cụm nguồn nhàn rỗi: khôi phục tới hết WAL, đạt, target_reached 0"
else hong "restore-check khi cụm nguồn nhàn rỗi"; echo "$NK" | tail -8; fi
# (c) Bản nền mới nhất bị ghi rác bằng khoá cell (được ghi phiên bản mới, không xoá được phiên bản gốc).
ID=$(backup thu-nen-hong); ID=${ID#* }
head -c 2000000 /dev/urandom > "$W/rac.bin"
docker run --rm --network kind -v "$W:/w" -e AWS_ACCESS_KEY_ID="$(khoa bk-cell-kind-pg access-key)" -e AWS_SECRET_ACCESS_KEY="$(khoa bk-cell-kind-pg secret-key)" \
  "$AWS_ANH" --endpoint-url http://$SN:8080 --region cell-kind-zg s3api put-object --bucket bk-cell-kind-pg \
  --key "pg-core/pg-core/base/$ID/data.tar.lz4" --body /w/rac.bin --checksum-algorithm SHA256 >/dev/null
sleep 130
NK=$(chay_rc rc-thu-hong || true)
if grep -q '^HỎNG' <<<"$NK" && [ "$(chi_so backup_restore_check_success)" = 0 ]; then
  ok "bản nền $ID bị ghi rác: restore-check báo hỏng, success 0"
else hong "bản nền hỏng không bị phát hiện"; echo "$NK" | tail -8; fi
# Bản nền mới sau sự cố đưa lần kiểm sau về đạt (phiên bản rác vẫn bị khoá tới hết hạn giữ).
backup thu-nen-sau >/dev/null

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

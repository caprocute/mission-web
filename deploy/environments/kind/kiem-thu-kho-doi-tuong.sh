#!/usr/bin/env bash
# Kiểm thử kho đối tượng của cell (INF-12; ADR-13, TL-03 "Kho đối tượng Ceph", TL-04 mục 8.4, SEC-44):
#   - Hai cụm Ceph 19 (quay.io/ceph/demo, mỗi cụm một OSD trên tmpfs) đóng vai zone Hoà Lạc và phía Nam trong mạng
#     docker "kind"; scripts/khoi-tao-multisite.sh dựng realm cell-kind, hai zone, người dùng S3 (khoá trong OpenBao
#     giả lập bằng thư mục tạm); đồng bộ multisite bắt kịp.
#   - Chart rgw-cell cài vào kind: Job khai báo 6 bucket với Object Lock, vòng đời, chính sách; chạy lại vẫn đạt.
#   - Xoá phiên bản và giảm hạn giữ trong bucket COMPLIANCE bị từ chối, kể cả với tài khoản quản trị kho.
#   - Người dùng service chỉ đọc ghi đúng bucket và tiền tố được cấp, không xoá được, không tạo được bucket.
#   - Đối tượng ghi ở zone Hoà Lạc xuất hiện ở zone phía Nam.
# Cần cụm kind dựng bằng tao-cum.sh, docker, helm, kubectl. Ảnh: quay.io/ceph/demo:latest-squid, aws-cli 2.31.18.
# Dùng: deploy/environments/kind/kiem-thu-kho-doi-tuong.sh   (GIU_LAI=1 để giữ container Ceph sau khi chạy)
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CHART=$GOC/deploy/charts/rgw-cell
CLUSTER=${CLUSTER:-vt-cell-kind}
NODE=$CLUSTER-worker
CEPH_ANH=quay.io/ceph/demo:latest-squid
AWS_ANH=mirror.gcr.io/amazon/aws-cli@sha256:1a491b258590cc66866cd550f592edff80082f44daf1d903820daeca2f4954de
HL=172.18.0.50; SN=172.18.0.51
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
export KHO_BAO=$W/kho
KHAC=$(kind get nodes --name "$CLUSTER" | grep worker | grep -v "^$NODE$" | tr '\n' ' ')
don() {
  for n in $KHAC; do kubectl uncordon "$n" >/dev/null 2>&1 || true; done
  if [ "${GIU_LAI:-0}" != 1 ]; then
    docker rm -f ceph-hl ceph-sn >/dev/null 2>&1 || true
    helm uninstall rgw -n secure-data >/dev/null 2>&1 || true
    rm -rf "${W:?}"
  else echo "Giữ lại container Ceph và thư mục khoá $W"; fi
}
trap don EXIT
# OpenBao giả: KV là tệp trong $KHO_BAO, đủ cho "kv get -field" và "kv put" mà script khởi tạo dùng.
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
khoa() { cat "$KHO_BAO/kv/ceph/cell-kind/$1/$2"; }
# s3 <uid> <ip zone> <lệnh s3api…>: gọi API S3 bằng khoá của người dùng; in kết quả và mã lỗi S3.
# RGW trả lỗi với <Message> rỗng làm aws-cli 2.31 tự hỏng (TypeError) mà không in mã lỗi, nên mã lỗi
# được lấy từ thân phản hồi trong nhật ký --debug.
s3() {
  local uid=$1 ip=$2 nk; shift 2
  nk=$(docker run --rm --network kind -v "$W:/w" -e AWS_ACCESS_KEY_ID="$(khoa "$uid" access-key)" \
    -e AWS_SECRET_ACCESS_KEY="$(khoa "$uid" secret-key)" "$AWS_ANH" --debug \
    --endpoint-url "http://$ip:8080" --region cell-kind-zg s3api "$@" 2>&1 >"$W/s3.out") || true
  cat "$W/s3.out"
  grep -o '<Code>[^<]*</Code><Message>[^<]*</Message>' <<<"$nk" | tail -1 || true
  grep -m1 'An error occurred' <<<"$nk" || true
}
cho() { local n=$1; shift; for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }
# Khởi động lại radosgw trong container demo để phục vụ zone mới (cụm thật: ceph orch redeploy rgw).
rgw_lai() {
  local c=$1 zone=$2 lenh
  lenh=$(docker exec "$c" ps -eo args | grep '^radosgw' | head -1 | sed 's/ --rgw-zone=.*//')
  docker exec "$c" pkill -x radosgw || true
  sleep 2
  docker exec -d "$c" sh -c "$lenh --rgw-zone=$zone --rgw-realm=cell-kind --rgw-zonegroup=cell-kind-zg > /tmp/rgw.log 2>&1"
}

echo "== Hai cụm Ceph giả lập zone Hoà Lạc và phía Nam, realm cell-kind"
for c in hl sn; do
  ip=$HL; [ $c = sn ] && ip=$SN
  docker rm -f ceph-$c >/dev/null 2>&1 || true
  docker run -d --name ceph-$c --network kind --ip $ip --tmpfs /var/lib/ceph:rw,size=3g -e MON_IP=$ip \
    -e CEPH_PUBLIC_NETWORK=172.18.0.0/16 -e DEMO_DAEMONS=osd,rgw -e RGW_FRONTEND_PORT=8080 -e OSD_SIZE=1024 \
    -e CEPH_DEMO_UID=demo -e CEPH_DEMO_ACCESS_KEY="$(openssl rand -hex 10)" -e CEPH_DEMO_SECRET_KEY="$(openssl rand -hex 20)" \
    $CEPH_ANH demo >/dev/null
done
for c in hl sn; do cho 60 sh -c "docker exec ceph-$c ceph -s | grep -q 'rgw: 1 daemon'"; done
chung=(CELL=kind "ENDPOINT_HL=http://$HL:8080" "ENDPOINT_SN=http://$SN:8080" BAO="$W/bao" POOL_HDD=default.rgw.buckets.data
       NGUOI_DUNG="evidence-service document-service ledger-service tenant-registry evidence-tenant-a")
env "${chung[@]}" VAI=hl RGW_ADMIN="docker exec ceph-hl radosgw-admin" "$CHART/scripts/khoi-tao-multisite.sh" > "$W/hl.log" 2>&1
rgw_lai ceph-hl cell-kind-hl
cho 20 docker exec ceph-hl curl -sf -o /dev/null "http://localhost:8080/"  || true
env "${chung[@]}" VAI=sn RGW_ADMIN="docker exec ceph-sn radosgw-admin" "$CHART/scripts/khoi-tao-multisite.sh" > "$W/sn.log" 2>&1
rgw_lai ceph-sn cell-kind-sn
if cho 40 sh -c "docker exec ceph-sn radosgw-admin sync status --rgw-zone=cell-kind-sn 2>/dev/null | grep -q 'data is caught up with source'"; then
  ok "realm cell-kind: zone master cell-kind-hl, zone cell-kind-sn, đồng bộ siêu dữ liệu và dữ liệu bắt kịp"
else hong "multisite chưa đồng bộ"; tail -5 "$W/hl.log" "$W/sn.log"; fi
if env "${chung[@]}" VAI=hl RGW_ADMIN="docker exec ceph-hl radosgw-admin" "$CHART/scripts/khoi-tao-multisite.sh" > "$W/hl2.log" 2>&1; then
  ok "khoi-tao-multisite.sh chạy lại không lỗi, khoá giữ nguyên trong OpenBao"
else hong "chạy lại khoi-tao-multisite.sh"; tail -5 "$W/hl2.log"; fi
if [ "$(docker exec ceph-hl radosgw-admin user info --uid=evidence-service | grep -c '"max_buckets": -1')" = 1 ]; then
  ok "người dùng service không được tạo bucket (max_buckets -1)"; else hong "max_buckets của evidence-service"; fi

echo "== Chart rgw-cell trên kind: Job khai báo bucket"
for n in $KHAC; do kubectl cordon "$n" >/dev/null; done
NAP_NODES=$NODE ./nap-anh.sh "$AWS_ANH" >/dev/null
kubectl create ns secure-data --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n secure-data create secret generic rgw-quan-tri --from-file=access-key="$KHO_BAO/kv/ceph/cell-kind/kho-quan-tri-cell-kind/access-key" \
  --from-file=secret-key="$KHO_BAO/kv/ceph/cell-kind/kho-quan-tri-cell-kind/secret-key" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install rgw "$CHART" -n secure-data -f values-kind-rgw.yaml >/dev/null
JOB=$(kubectl -n secure-data get job -o name | grep rgw-bucket | head -1)
if kubectl -n secure-data wait "$JOB" --for=condition=Complete --timeout=300s >/dev/null; then
  ok "Job khai báo 6 bucket: $(kubectl -n secure-data logs "$JOB" | grep -c 'đã áp') bucket đã áp cấu hình"
else hong "Job khai báo bucket"; kubectl -n secure-data logs "$JOB" --tail=20 || true; fi
helm upgrade rgw "$CHART" -n secure-data -f values-kind-rgw.yaml --set canhBao.tuoiChuaDongBoToiDaGiay=601 \
  --set buckets.templates.vongDoi.huyMultipartSauNgay=2 >/dev/null
JOB2=$(kubectl -n secure-data get job -o name | grep rgw-bucket | grep -v "${JOB#*/}" | head -1)
if [ -n "$JOB2" ] && kubectl -n secure-data wait "$JOB2" --for=condition=Complete --timeout=300s >/dev/null \
   && kubectl -n secure-data logs "$JOB2" | grep -q "ds-cell-kind: đã có"; then
  ok "đổi cấu hình thì Job mới chạy lại trên bucket đã có (idempotent)"
else hong "Job chạy lại"; fi

echo "== Object Lock COMPLIANCE"
KQ=$(s3 kho-quan-tri-cell-kind $HL get-object-lock-configuration --bucket ev-cell-kind)
if grep -q '"Mode": "COMPLIANCE"' <<<"$KQ" && grep -q '"Years": 10' <<<"$KQ"; then ok "ev-cell-kind: COMPLIANCE 10 năm"; else hong "khoá ev-cell-kind: $KQ"; fi
KQ=$(s3 kho-quan-tri-cell-kind $HL get-object-lock-configuration --bucket ds-cell-kind)
if grep -q '"Years": 20' <<<"$KQ"; then ok "ds-cell-kind: COMPLIANCE 20 năm (TL-03)"; else hong "khoá ds-cell-kind: $KQ"; fi
KQ=$(s3 kho-quan-tri-cell-kind $HL get-bucket-lifecycle-configuration --bucket tmp-cell-kind)
if grep -q '"Days": 1' <<<"$KQ"; then ok "tmp-cell-kind: vòng đời xoá sau 1 ngày"; else hong "vòng đời tmp: $KQ"; fi
BAM=$(printf 'minh-chung-thu' | sha256sum | cut -c1-64)
KHOA_A="t/tenant-a/sha256/${BAM:0:2}/$BAM"
KHOA_B="t/tenant-b/sha256/${BAM:0:2}/$BAM"
printf 'minh-chung-thu' > "$W/mc.bin"
KQ=$(s3 evidence-service $HL put-object --bucket ev-cell-kind --key "$KHOA_A" --body /w/mc.bin)
VID=$(sed -n 's/.*"VersionId": "\([^"]*\)".*/\1/p' <<<"$KQ")
s3 evidence-service $HL put-object --bucket ev-cell-kind --key "$KHOA_B" --body /w/mc.bin >/dev/null
if [ -n "$VID" ]; then ok "evidence-service ghi minh chứng theo khoá nội dung t/<tenant>/sha256/… (phiên bản $VID)"; else hong "ghi minh chứng: $KQ"; fi
# Chủ bucket xoá được mọi phiên bản nếu không có khoá; đối tượng của chính tài khoản quản trị trong bucket dossier
# cho thấy chỉ Object Lock chặn việc xoá.
KQ=$(s3 kho-quan-tri-cell-kind $HL put-object --bucket ds-cell-kind --key t/tenant-a/ho-so/x --body /w/mc.bin)
VID_DS=$(sed -n 's/.*"VersionId": "\([^"]*\)".*/\1/p' <<<"$KQ")
KQ=$(s3 kho-quan-tri-cell-kind $HL delete-object --bucket ds-cell-kind --key t/tenant-a/ho-so/x --version-id "$VID_DS" \
  --bypass-governance-retention)
if grep -q "forbidden by object lock" <<<"$KQ"; then ok "tài khoản quản trị kho xoá phiên bản bị Object Lock từ chối (COMPLIANCE)"; else hong "xoá phiên bản được: $KQ"; fi
NGAY=$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)
KQ=$(s3 evidence-service $HL put-object-retention --bucket ev-cell-kind --key "$KHOA_A" --version-id "$VID" \
  --retention "{\"Mode\":\"COMPLIANCE\",\"RetainUntilDate\":\"$NGAY\"}" --bypass-governance-retention)
if grep -q "AccessDenied\|InvalidRequest" <<<"$KQ"; then ok "giảm hạn giữ bị từ chối"; else hong "giảm hạn giữ được: $KQ"; fi
KQ=$(s3 kho-quan-tri-cell-kind $HL put-object-lock-configuration --bucket ev-cell-kind \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"GOVERNANCE","Days":1}}}')
KQ2=$(s3 evidence-service $HL get-object-retention --bucket ev-cell-kind --key "$KHOA_A" --version-id "$VID")
if grep -q '"Mode": "COMPLIANCE"' <<<"$KQ2" && grep -q '"RetainUntilDate": "20[3-9]' <<<"$KQ2"; then ok "đổi khoá mặc định của bucket không nới hạn của đối tượng đã ghi"; else hong "hạn của đối tượng: $KQ2"; fi
s3 kho-quan-tri-cell-kind $HL put-object-lock-configuration --bucket ev-cell-kind \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Years":10}}}' >/dev/null

echo "== Chính sách bucket theo service và tiền tố tenant"
KQ=$(s3 evidence-service $HL delete-object --bucket ev-cell-kind --key "$KHOA_A")
if grep -q "AccessDenied" <<<"$KQ"; then ok "evidence-service không có quyền xoá"; else hong "evidence-service xoá được: $KQ"; fi
KQ=$(s3 evidence-tenant-a $HL get-object --bucket ev-cell-kind --key "$KHOA_A" /tmp/a)
if grep -q '"ContentLength": 14' <<<"$KQ"; then ok "tài khoản tenant-a đọc được đối tượng của tenant-a"; else hong "tenant-a đọc tiền tố của mình: $KQ"; fi
KQ=$(s3 evidence-tenant-a $HL get-object --bucket ev-cell-kind --key "$KHOA_B" /tmp/b)
if grep -q "AccessDenied" <<<"$KQ"; then ok "tài khoản tenant-a không đọc được tiền tố t/tenant-b/"; else hong "tenant-a đọc được tenant-b: $KQ"; fi
KQ=$(s3 evidence-tenant-a $HL list-objects-v2 --bucket ev-cell-kind --prefix t/tenant-b/)
if grep -q "AccessDenied" <<<"$KQ"; then ok "tài khoản tenant-a không liệt kê được tiền tố t/tenant-b/"; else hong "tenant-a liệt kê được tenant-b: $KQ"; fi
KQ=$(s3 evidence-service $HL get-object --bucket ds-cell-kind --key t/tenant-a/x /tmp/c)
if grep -q "AccessDenied" <<<"$KQ"; then ok "evidence-service không đọc được bucket dossier"; else hong "evidence-service đọc được dossier: $KQ"; fi
KQ=$(s3 evidence-service $HL put-bucket-policy --bucket ev-cell-kind --policy '{"Version":"2012-10-17","Statement":[]}')
if grep -q "AccessDenied" <<<"$KQ"; then ok "evidence-service không đổi được chính sách bucket"; else hong "evidence-service đổi được chính sách: $KQ"; fi
KQ=$(s3 evidence-service $HL create-bucket --bucket tu-tao-cell-kind)
if grep -q "AccessDenied\|TooManyBuckets" <<<"$KQ"; then ok "evidence-service không tự tạo được bucket"; else hong "evidence-service tạo được bucket: $KQ"; fi
KQ=$(s3 document-service $HL put-object --bucket tpl-cell-kind --key mau/x.docx --body /w/mc.bin)
if grep -q "AccessDenied" <<<"$KQ"; then ok "document-service chỉ đọc bucket mẫu"; else hong "document-service ghi được mẫu: $KQ"; fi

echo "== Đồng bộ multisite đối tượng"
if cho 40 sh -c "docker run --rm --network kind -e AWS_ACCESS_KEY_ID=$(khoa evidence-service access-key) -e AWS_SECRET_ACCESS_KEY=$(khoa evidence-service secret-key) $AWS_ANH --endpoint-url http://$SN:8080 --region cell-kind-zg s3api get-object-retention --bucket ev-cell-kind --key $KHOA_A --version-id $VID | grep -q COMPLIANCE"; then
  ok "minh chứng ghi ở zone Hoà Lạc có ở zone phía Nam, còn nguyên khoá COMPLIANCE"
else hong "đối tượng chưa đồng bộ sang zone phía Nam"; fi
cho 40 sh -c "docker run --rm --network kind -e AWS_ACCESS_KEY_ID=$(khoa kho-quan-tri-cell-kind access-key) -e AWS_SECRET_ACCESS_KEY=$(khoa kho-quan-tri-cell-kind secret-key) $AWS_ANH --endpoint-url http://$SN:8080 --region cell-kind-zg s3api head-object --bucket ds-cell-kind --key t/tenant-a/ho-so/x --version-id $VID_DS" || true
KQ=$(s3 kho-quan-tri-cell-kind $SN delete-object --bucket ds-cell-kind --key t/tenant-a/ho-so/x --version-id "$VID_DS" \
  --bypass-governance-retention)
if grep -q "forbidden by object lock" <<<"$KQ"; then ok "xoá phiên bản ở zone phía Nam cũng bị từ chối"; else hong "xoá được ở zone phía Nam: $KQ"; fi

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

#!/usr/bin/env bash
# Kiểm thử lớp mạng và lưu trữ của cell trên kind (INF-09, TL-04 mục 3.7, 3.8, 9.5; SEC-21, SEC-32, SEC-36).
# Cần cụm từ tao-cum.sh và Istio từ cai-istio.sh. Cài cell-base, cell-mesh, cell-egress, rồi kiểm:
#   mesh   pod ứng dụng dùng HBONE (istioctl ztunnel-config); esign-allow-callers ở waypoint: mission-core chỉ gọi được
#          POST hai đường dẫn ký, pod khác trong core bị từ chối, gọi thẳng IP pod bỏ qua waypoint bị từ chối;
#          lưu lượng không mTLS từ namespace ngoài mesh bị từ chối (STRICT), trừ cổng thu số liệu đã mở.
#   egress đích ngoài cụm giả lập (container trên mạng kind, tên miền nạp vào CoreDNS): qua egress gateway chỉ tới
#          được đích trong danh sách; pod ngoài namespace egress không ra được; toFQDNs chặn ở tầng mạng; Hubble ghi
#          luồng bị chặn.
#   lưu trữ StorageClass nvme-local và ceph-rbd qua schema; PVC mẫu chạy trên local-path của kind (TopoLVM và Ceph
#          CSI thật không có trên kind).
# Dùng: deploy/environments/kind/kiem-thu-mang-cell.sh
set -euo pipefail
cd "$(dirname "$0")"
CH=../../charts
ECHO=docker.io/hashicorp/http-echo@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186
CURL=docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6
ENVOY=docker.io/envoyproxy/envoy@sha256:fc09f2e087b6a68f8464738f6bcc0757ff8be9ad50c9aec0450e82615fdb9e66
# Đích ngoài cụm giả lập: một đích trong danh sách cổng ra và một đích ngoài danh sách.
IP_DUOC=172.18.0.250; IP_CAM=172.18.0.251
TEN_DUOC=api.vneid.gov.vn; TEN_CAM=khong-duoc-phep.example.vn
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
SC='"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}'
PSC='"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}}'

./nap-anh.sh "$ECHO" "$CURL" "$ENVOY" >/dev/null

echo "== Cài cell-base, cell-mesh, cell-egress"
helm upgrade --install cell-base "$CH/cell-base" --set cell=kind --wait >/dev/null && ok "cell-base" || hong "cell-base"
helm upgrade --install cell-mesh "$CH/cell-mesh" --set waypointDeployment.replicas=1 >/dev/null && ok "cell-mesh" || hong "cell-mesh"
helm upgrade --install cell-egress "$CH/cell-egress" --set gateway.replicas=1 --set gateway.nodeSelector=null \
  --set gateway.image.repository=docker.io/envoyproxy/envoy \
  --set-json "destinations=[{\"name\":\"vneid\",\"fqdn\":\"${TEN_DUOC}\",\"ports\":[443]}]" \
  --set alerts.enabled=false >/dev/null && ok "cell-egress" || hong "cell-egress"
kubectl -n egress rollout status deploy/egress-gateway --timeout=180s >/dev/null && ok "egress gateway sẵn sàng" || hong "egress gateway chưa chạy"
for ns in secure egress; do
  kubectl -n "$ns" wait --for=condition=Available deploy/waypoint --timeout=180s >/dev/null && ok "waypoint của $ns sẵn sàng" || hong "waypoint $ns"
done

# Pod máy chủ http-echo: $1 namespace, $2 tên (nhãn app và tài khoản dịch vụ).
may_chu() {
  kubectl -n "$1" create serviceaccount "$2" >/dev/null 2>&1 || true
  kubectl -n "$1" run "$2" --image="$ECHO" --restart=Never --labels="app.kubernetes.io/name=$2" \
    --overrides='{"spec":{"serviceAccountName":"'"$2"'",'"$PSC"',"containers":[{"name":"c","image":"'"$ECHO"'","imagePullPolicy":"IfNotPresent","args":["-listen=:8080","-text='"$2"'"],"ports":[{"containerPort":8080}],'"$SC"'}]}}' >/dev/null
  kubectl -n "$1" expose pod "$2" --port=8080 >/dev/null
  kubectl -n "$1" wait --for=condition=Ready "pod/$2" --timeout=90s >/dev/null
}
# Chạy curl trong một pod tạm: $1 namespace, $2 tài khoản dịch vụ và nhãn app, $3 tham số curl. In mã HTTP (000 khi
# kết nối hỏng). Nhãn thêm qua biến NHAN.
goi() {
  local p="thu-$RANDOM"
  kubectl -n "$1" create serviceaccount "$2" >/dev/null 2>&1 || true
  kubectl -n "$1" run "$p" --image="$CURL" --restart=Never --labels="app.kubernetes.io/name=$2${NHAN:+,$NHAN}" \
    --overrides='{"spec":{"serviceAccountName":"'"$2"'",'"$PSC"',"containers":[{"name":"c","image":"'"$CURL"'","imagePullPolicy":"IfNotPresent","command":["sh","-c","curl -s -o /dev/null -w %{http_code} -m 8 '"$3"' || true"],'"$SC"'}]}}' >/dev/null
  kubectl -n "$1" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$p" --timeout=90s >/dev/null || true
  kubectl -n "$1" logs "$p" 2>/dev/null | tail -c 3; echo
  kubectl -n "$1" delete pod "$p" --wait=false >/dev/null
}
# NetworkPolicy riêng của bài thử: mở đường mạng để lớp mesh là lớp quyết định.
cat <<'Y' | kubectl apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: thu-ra-secure-egress, namespace: core }
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector: { matchExpressions: [{ key: kubernetes.io/metadata.name, operator: In, values: [secure, egress] }] }
      ports: [{ protocol: TCP, port: 15008 }, { protocol: TCP, port: 8080 }, { protocol: TCP, port: 443 }]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: thu-ra-secure, namespace: data-pg }
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: secure } } }]
      ports: [{ protocol: TCP, port: 8080 }]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: thu-vao-esign, namespace: secure }
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: esign-service } }
  policyTypes: ["Ingress"]
  ingress:
    - from: [{ namespaceSelector: { matchExpressions: [{ key: kubernetes.io/metadata.name, operator: In, values: [core, data-pg] }] } }]
      ports: [{ protocol: TCP, port: 8080 }, { protocol: TCP, port: 15008 }]
Y

echo "== Mesh: mTLS STRICT, HBONE, esign-allow-callers"
may_chu secure esign-service
may_chu core mission-core
if istioctl ztunnel-config workloads -o json 2>/dev/null | python3 -c '
import json,sys
w=[x for x in json.load(sys.stdin) if x.get("namespace")=="secure" and x.get("name","").startswith("esign-service")]
sys.exit(0 if w and all(x.get("protocol")=="HBONE" for x in w) else 1)'; then
  ok "ztunnel-config: pod esign-service dùng HBONE (mTLS của ztunnel)"
else hong "esign-service không dùng HBONE"; fi
ES=http://esign-service.secure.svc:8080
[ "$(goi core mission-core "-X POST $ES/internal/v1/sign-requests")" = 200 ] && ok "mission-core POST /internal/v1/sign-requests: 200" || hong "mission-core không tạo được yêu cầu ký"
[ "$(goi core mission-core "-X POST $ES/internal/v1/sign-batches")" = 200 ] && ok "mission-core POST /internal/v1/sign-batches: 200" || hong "mission-core không tạo được lô ký"
[ "$(goi core mission-core "$ES/internal/v1/sign-requests")" = 403 ] && ok "mission-core GET cùng đường dẫn: 403 (sai phương thức)" || hong "GET lẽ ra bị từ chối"
[ "$(goi core mission-core "-X POST $ES/admin/keys")" = 403 ] && ok "mission-core POST đường dẫn quản trị: 403" || hong "đường dẫn quản trị lẽ ra bị từ chối"
[ "$(goi core thu-khac "-X POST $ES/internal/v1/sign-requests")" = 403 ] && ok "pod khác trong core (danh tính khác) bị từ chối: 403" || hong "pod khác vẫn gọi được esign"
IP_ES=$(kubectl -n secure get pod esign-service -o jsonpath='{.status.podIP}')
[ "$(goi core mission-core "-X POST http://${IP_ES}:8080/internal/v1/sign-requests")" = 000 ] \
  && ok "gọi thẳng IP pod bỏ qua waypoint bị ztunnel từ chối" || hong "gọi thẳng IP pod vẫn được"
[ "$(goi data-pg thu-ngoai-mesh "http://${IP_ES}:8080/")" = 000 ] \
  && ok "lưu lượng không mTLS từ namespace ngoài mesh bị từ chối (STRICT)" || hong "STRICT không có hiệu lực"

echo "== Cổng ra: egress gateway, toFQDNs, Hubble"
for d in "$IP_DUOC:duoc-phep" "$IP_CAM:bi-cam"; do
  docker rm -f "vt-dich-${d#*:}" >/dev/null 2>&1 || true
  docker run -d --name "vt-dich-${d#*:}" --network kind --ip "${d%%:*}" "$ECHO" -listen=:443 -text="${d#*:}" >/dev/null
done
# Tên miền giả lập trong CoreDNS (khối hosts trước forward), khôi phục khi kết thúc.
kubectl -n kube-system get cm coredns -o jsonpath='{.data.Corefile}' > /tmp/vt-corefile.goc
trap 'kubectl -n kube-system create cm coredns --from-file=Corefile=/tmp/vt-corefile.goc --dry-run=client -o yaml | kubectl apply -f - >/dev/null; docker rm -f vt-dich-duoc-phep vt-dich-bi-cam >/dev/null 2>&1 || true' EXIT
python3 - "$IP_DUOC" "$TEN_DUOC" "$IP_CAM" "$TEN_CAM" > /tmp/vt-corefile.moi <<'P'
import sys
c=open("/tmp/vt-corefile.goc").read()
khoi="    hosts {\n        %s %s\n        %s %s\n        fallthrough\n    }\n" % tuple(sys.argv[1:5])
print(c.replace("    forward .", khoi+"    forward .",1), end="")
P
kubectl -n kube-system create cm coredns --from-file=Corefile=/tmp/vt-corefile.moi --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n kube-system rollout restart deploy/coredns >/dev/null; kubectl -n kube-system rollout status deploy/coredns --timeout=120s >/dev/null
GW=http://egress-gateway.egress.svc:443
[ "$(goi core mission-core "-p -x $GW http://${TEN_DUOC}:443/")" = 200 ] && ok "core qua egress gateway tới đích trong danh sách: 200" || hong "không qua được egress gateway tới đích được phép"
[ "$(goi core mission-core "-p -x $GW http://${TEN_CAM}:443/")" = 000 ] && ok "core qua egress gateway tới đích ngoài danh sách: bị từ chối" || hong "egress gateway để lọt đích ngoài danh sách"
[ "$(goi core mission-core "http://${IP_DUOC}:443/")" = 000 ] && ok "core gọi thẳng ra ngoài (không qua gateway): bị chặn" || hong "core ra thẳng được Internet"
[ "$(goi default thu-ngoai "http://${IP_DUOC}:443/")" = 000 ] && ok "pod ở namespace default ra ngoài: bị chặn (chan-ra-ngoai-cum)" || hong "namespace ngoài cell vẫn ra được"
[ "$(goi egress thu-trong-egress "http://${TEN_DUOC}:443/")" = 000 ] && ok "pod khác trong namespace egress không ra được" || hong "pod lạ trong egress ra được"
# Lớp mạng: pod mang nhãn của gateway (được CiliumNetworkPolicy toFQDNs áp) chỉ tới được FQDN trong danh sách.
[ "$(NHAN= goi egress egress-gateway "http://${TEN_DUOC}:443/")" = 200 ] && ok "toFQDNs: tới ${TEN_DUOC} được" || hong "toFQDNs chặn nhầm đích được phép"
[ "$(goi egress egress-gateway "http://${TEN_CAM}:443/")" = 000 ] && ok "toFQDNs: tới ${TEN_CAM} bị chặn ở tầng mạng" || hong "toFQDNs không chặn đích ngoài danh sách"
sleep 3
so=0
for p in $(kubectl -n kube-system get pods -l k8s-app=cilium -o name); do
  n=$(kubectl -n kube-system exec "$p" -c cilium-agent -- hubble observe --verdict DROPPED --to-ip "$IP_DUOC" --to-ip "$IP_CAM" --last 200 -o compact 2>/dev/null | grep -c DROPPED || true)
  so=$((so+n))
done
[ "$so" -gt 0 ] && ok "Hubble ghi $so luồng ra ngoài bị chặn (SEC-32, SEC-36)" || hong "Hubble không ghi luồng bị chặn"

echo "== Lưu trữ"
helm template s "$CH/cell-storage" | kubeconform -strict -summary >/tmp/vt-kc.log 2>&1 && ok "StorageClass nvme-local, ceph-rbd: $(tail -1 /tmp/vt-kc.log)" || hong "schema StorageClass"
if helm template s "$CH/cell-storage" --set cephRbd.encrypted=false >/dev/null 2>&1; then hong "ceph-rbd tắt mã hoá lẽ ra bị từ chối"; else ok "ceph-rbd không mã hoá bị từ chối khi render"; fi
cat <<'Y' | kubectl apply -f - >/dev/null
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: thu-pvc, namespace: data-pg }
spec: { accessModes: [ReadWriteOnce], storageClassName: standard, resources: { requests: { storage: 64Mi } } }
---
apiVersion: v1
kind: Pod
metadata: { name: thu-pvc, namespace: data-pg }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 10001, fsGroup: 10001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: docker.io/curlimages/curl@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", "echo ok > /du-lieu/thu && cat /du-lieu/thu"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      volumeMounts: [{ name: d, mountPath: /du-lieu }]
  restartPolicy: Never
  volumes: [{ name: d, persistentVolumeClaim: { claimName: thu-pvc } }]
Y
kubectl -n data-pg wait --for=jsonpath='{.status.phase}'=Succeeded pod/thu-pvc --timeout=120s >/dev/null 2>&1 \
  && [ "$(kubectl -n data-pg logs thu-pvc)" = ok ] && ok "PVC mẫu trên local-path ghi đọc được (thay TopoLVM trên kind)" || hong "PVC mẫu"

echo "== Dọn"
kubectl -n data-pg delete pod,pvc thu-pvc --wait=false >/dev/null 2>&1 || true
kubectl -n secure delete pod,svc esign-service --wait=false >/dev/null 2>&1 || true
kubectl -n core delete pod,svc mission-core --wait=false >/dev/null 2>&1 || true
kubectl -n core delete networkpolicy thu-ra-secure-egress >/dev/null 2>&1 || true
kubectl -n data-pg delete networkpolicy thu-ra-secure >/dev/null 2>&1 || true
kubectl -n secure delete networkpolicy thu-vao-esign >/dev/null 2>&1 || true
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

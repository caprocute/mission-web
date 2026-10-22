#!/usr/bin/env bash
# Kiểm thử đầu cuối bộ chính sách Kyverno của INF-07 trên cụm kind (SEC-28, SEC-38, SEC-55).
# Dựng registry cục bộ thay Harbor, sinh khoá cosign tạm (không commit, xoá khi xong), ký và chứng thực SLSA một
# ảnh như stage "Ký và chứng thực" của INF-05, cài Kyverno 1.15 rồi áp bản sao của deploy/policies đã đổi
# harbor.vt.local thành registry cục bộ và khoá KMS thành khoá tạm. Khẳng định: ảnh đã ký chạy được; ảnh chưa ký,
# thẻ latest, pod chạy root, pod thiếu probe, chứng thực sai builder đều bị từ chối với thông báo rõ.
# Cuối cùng gỡ chính sách, Kyverno và registry để không ảnh hưởng kiểm thử kind khác.
# Cần: kind, kubectl, helm, docker, cosign, python3 + PyYAML. Dùng: deploy/environments/kind/kiem-thu-chinh-sach.sh
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
CLUSTER=${CLUSTER:-vt-cell-kind}
KYVERNO_CHART=${KYVERNO_CHART:-3.5.2}       # Kyverno v1.15.2, cùng bản CLI dùng trong CI
ANH_REG=${ANH_REG:-mirror.gcr.io/library/registry:2}
ANH_NEN=${ANH_NEN:-python:3.12-alpine}      # ảnh nền nhỏ chạy được không root với hệ tệp chỉ đọc
NODE=${NODE:-$CLUSTER-worker}
NS=vt-kiem-chinh-sach
TMP=$(mktemp -d)
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
don() {
  kubectl delete ns "$NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl delete clusterpolicy vt-require-signed-images vt-pod-hardening vt-probe-on-mgmt-port --ignore-not-found >/dev/null 2>&1 || true
  [ "${GIU_KYVERNO:-0}" = 1 ] || helm uninstall kyverno -n kyverno --wait >/dev/null 2>&1 || true
  [ "${GIU_KYVERNO:-0}" = 1 ] || kubectl delete ns kyverno --ignore-not-found --wait=false >/dev/null 2>&1 || true
  # Kyverno tự dựng webhook lúc chạy; gỡ chart không xoá chúng (đã tắt job dọn webhook vì cần ảnh kubectl),
  # để lại sẽ chặn mọi lệnh tạo pod của cụm do failurePolicy Fail.
  [ "${GIU_KYVERNO:-0}" = 1 ] || kubectl get validatingwebhookconfigurations,mutatingwebhookconfigurations -o name 2>/dev/null \
    | grep kyverno | xargs -r kubectl delete >/dev/null 2>&1 || true
  docker rm -f kind-registry >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap don EXIT

echo "== Registry cục bộ thay Harbor"
docker rm -f kind-registry >/dev/null 2>&1 || true
docker run -d --name kind-registry --network kind -p 127.0.0.1:5001:5000 "$ANH_REG" >/dev/null
IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "kind").IPAddress}}' kind-registry)
REG="$IP:5000"; NGOAI=localhost:5001
for _ in $(seq 30); do curl -fsS "http://$NGOAI/v2/" >/dev/null 2>&1 && break; sleep 1; done
docker image inspect "$ANH_NEN" >/dev/null 2>&1 || docker pull -q --platform linux/amd64 "$ANH_NEN" >/dev/null
for r in mau-ky chua-ky sai-builder bundle-moi; do docker tag "$ANH_NEN" "$NGOAI/okr/$r:1"; docker push -q "$NGOAI/okr/$r:1" >/dev/null; done
docker tag "$ANH_NEN" "$NGOAI/okr/mau-ky:latest"; docker push -q "$NGOAI/okr/mau-ky:latest" >/dev/null
DG=$(curl -fsS -o /dev/null -w '%header{docker-content-digest}' -H 'Accept: application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json' "http://$NGOAI/v2/okr/mau-ky/manifests/1")
echo "  registry $REG, digest $DG"

echo "== Ký và chứng thực bằng khoá cosign tạm (như INF-05)"
export COSIGN_PASSWORD=""
( cd "$TMP" && cosign generate-key-pair >/dev/null 2>&1 )
# Cờ như stage ký của vtJavaPipeline, cộng --new-bundle-format=false: Kyverno 1.15 chỉ đọc chữ ký dạng thẻ .sig/.att
# (cosign 3 mặc định dùng định dạng bundle mới qua referrers, Kyverno trả "no signatures found").
CO=(--key "$TMP/cosign.key" --tlog-upload=false --use-signing-config=false --new-bundle-format=false
    --allow-insecure-registry --allow-http-registry --yes)
ky() {  # ky <repo> <builder>
  cosign sign "${CO[@]}" "$NGOAI/okr/$1@$DG" > "$TMP/ky.log" 2>&1
  python3 "$GOC/ci/vt-pipeline/resources/vn/vt/pipeline/vt-provenance.py" --builder-id "$2" \
    --repo https://git.vt.local/okr/mau.git --commit 0123456789abcdef0123456789abcdef01234567 --digest "$DG" > "$TMP/prov-$1.json"
  cosign attest "${CO[@]}" --type slsaprovenance1 --predicate "$TMP/prov-$1.json" "$NGOAI/okr/$1@$DG" >> "$TMP/ky.log" 2>&1
}
ky mau-ky https://jenkins.vt.local/okr-builder && ok "ký và gắn chứng thực SLSA cho okr/mau-ky" || { hong "ký okr/mau-ky"; tail -3 "$TMP/ky.log"; }
ky sai-builder https://ci.ngoai.example/builder && ok "ký okr/sai-builder với builder lạ" || hong "ký okr/sai-builder"
# Đối chứng: ký đúng cờ hiện tại của vtJavaPipeline (cosign 3, định dạng bundle mới) để biết Kyverno có đọc được không.
CO=("${CO[@]/--new-bundle-format=false/}")
ky bundle-moi https://jenkins.vt.local/okr-builder || true

echo "== Nạp ảnh vào node $NODE (kubelet không kéo HTTP)"
docker save --platform linux/amd64 -o "$TMP/a.tar" "$ANH_NEN"
docker exec -i "$NODE" ctr --namespace=k8s.io images import --platform linux/amd64 --snapshotter=overlayfs - < "$TMP/a.tar" >/dev/null
GOC_ANH=$(docker exec "$NODE" ctr --namespace=k8s.io images ls -q | grep -E "(^|/)${ANH_NEN}$" | head -1)
for r in mau-ky chua-ky sai-builder bundle-moi; do
  docker exec "$NODE" ctr --namespace=k8s.io images rm "$REG/okr/$r@$DG" >/dev/null 2>&1 || true
  docker exec "$NODE" ctr --namespace=k8s.io images tag "$GOC_ANH" "$REG/okr/$r@$DG" >/dev/null
done
rm -f "$TMP/a.tar"

echo "== Cài Kyverno $KYVERNO_CHART"
for a in kyverno kyvernopre; do NAP_NODES="$NODE" ./nap-anh.sh "reg.kyverno.io/kyverno/$a:v1.15.2" >/dev/null; done
helm upgrade --install kyverno kyverno/kyverno --version "$KYVERNO_CHART" -n kyverno --create-namespace --wait --timeout 5m \
  --set admissionController.replicas=1,backgroundController.enabled=false,cleanupController.enabled=false \
  --set reportsController.enabled=false,webhooksCleanup.enabled=false,policyReportsCleanup.enabled=false \
  --set crds.migration.enabled=false,test.enabled=false \
  --set-string admissionController.container.extraArgs.allowInsecureRegistry=true \
  --set "admissionController.nodeSelector.kubernetes\.io/hostname=$NODE" \
  --set admissionController.initContainer.image.pullPolicy=IfNotPresent >/dev/null && ok "Kyverno sẵn sàng" || hong "cài Kyverno"

echo "== Áp bản sao chính sách đã đổi registry và khoá"
python3 - "$GOC/deploy/policies" "$REG" "$TMP/cosign.pub" > "$TMP/cs.yaml" <<'PY'
import sys, yaml
goc, reg, pub = sys.argv[1], sys.argv[2], open(sys.argv[3]).read()
def doi(o):
    if isinstance(o, dict):
        if "kms" in o:
            o = {k: v for k, v in o.items() if k != "kms"}; o["publicKeys"] = pub
        return {k: doi(v) for k, v in o.items()}
    if isinstance(o, list): return [doi(x) for x in o]
    if isinstance(o, str): return o.replace("harbor.vt.local", reg)
    return o
ra = []
for t in ("vt-require-signed-images.yaml", "vt-pod-hardening.yaml"):
    ra += [doi(d) for d in yaml.safe_load_all(open(f"{goc}/{t}")) if d]
yaml.safe_dump_all(ra, sys.stdout, allow_unicode=True)
PY
kubectl apply -f "$TMP/cs.yaml" >/dev/null
for p in vt-require-signed-images vt-pod-hardening; do
  kubectl wait --for=condition=Ready "clusterpolicy/$p" --timeout=120s >/dev/null && ok "chính sách $p Ready" || hong "chính sách $p chưa Ready"
done
kubectl delete ns "$NS" --ignore-not-found --timeout=120s >/dev/null 2>&1 || true
kubectl create ns "$NS" >/dev/null; kubectl label ns "$NS" vt.io/security-zone=app >/dev/null

pod() {  # pod <tên> <ảnh> [uid] [có probe 1/0]
  local uid=${3:-10001} probe=${4:-1}
  python3 - "$1" "$2" "$uid" "$probe" "$NS" "$NODE" <<'PY'
import sys, yaml
ten, anh, uid, probe, ns, node = sys.argv[1:7]
pr = {"httpGet": {"path": "/", "port": 8081}, "periodSeconds": 5}
c = {"name": "app", "image": anh, "imagePullPolicy": "IfNotPresent",
     "command": ["python", "-m", "http.server", "8081"], "ports": [{"name": "mgmt", "containerPort": 8081}],
     "resources": {"requests": {"cpu": "10m", "memory": "32Mi"}, "limits": {"memory": "64Mi"}},
     "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}}}
if probe == "1": c["readinessProbe"] = pr; c["livenessProbe"] = pr
sc = {"runAsNonRoot": uid != "0", "runAsUser": int(uid), "seccompProfile": {"type": "RuntimeDefault"}}
print(yaml.safe_dump({"apiVersion": "v1", "kind": "Pod", "metadata": {"name": ten, "namespace": ns},
  "spec": {"automountServiceAccountToken": False, "nodeName": node, "securityContext": sc, "containers": [c]}}))
PY
}
tu_choi() {  # tu_choi <mô tả> <mẫu thông báo> <tên> <ảnh> [uid] [probe]
  local mt=$1 mau=$2; shift 2
  if pod "$@" | kubectl apply -f - > "$TMP/o.log" 2>&1; then hong "$mt: lại được chấp nhận"; kubectl -n "$NS" delete pod "$1" --wait=false >/dev/null
  elif grep -qiE "$mau" "$TMP/o.log"; then ok "$mt bị từ chối ($(grep -oiE "$mau" "$TMP/o.log" | head -1))"
  else hong "$mt bị từ chối nhưng thông báo không như mong đợi"; head -c 600 "$TMP/o.log"; echo; fi
}

echo "== Khẳng định"
if pod da-ky "$REG/okr/mau-ky@$DG" | kubectl apply -f - > "$TMP/o.log" 2>&1 \
   && kubectl -n "$NS" wait --for=condition=Ready pod/da-ky --timeout=120s >/dev/null 2>&1; then
  ok "ảnh đã ký, có chứng thực SLSA, pod đúng chuẩn: được chạy và Ready"
else hong "ảnh đã ký không chạy được"; cat "$TMP/o.log"; kubectl -n "$NS" describe pod da-ky 2>/dev/null | tail -8 || true; fi
tu_choi "ảnh chưa ký" "no (matching )?signatures( found)?" chua-ky "$REG/okr/chua-ky@$DG"
tu_choi "ảnh dùng thẻ latest" "digest" the-latest "$REG/okr/mau-ky:latest"
tu_choi "pod chạy root" "readonly-rootfs-nonroot" chay-root "$REG/okr/mau-ky@$DG" 0
tu_choi "pod thiếu probe" "require-resources-and-probes" thieu-probe "$REG/okr/mau-ky@$DG" 10001 0
tu_choi "chứng thực SLSA sai builder" "attestations verification failed" sai-builder "$REG/okr/sai-builder@$DG"

if pod bundle-moi "$REG/okr/bundle-moi@$DG" | kubectl apply -f - > "$TMP/o.log" 2>&1; then
  echo "  [THÔNG TIN] chữ ký định dạng bundle mới của cosign 3 được Kyverno chấp nhận"
else
  echo "  [THÔNG TIN] chữ ký định dạng bundle mới của cosign 3 bị Kyverno từ chối: pipeline phải ký với --new-bundle-format=false"
fi
echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]

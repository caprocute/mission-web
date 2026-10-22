#!/usr/bin/env bash
# Kiểm thử GitOps và phát hành canary trên kind (INF-04; OPS-01, OPS-02, OPS-03, OPS-05, OPS-06):
#   - Argo CD 3.1 với chart deploy/argocd (values-kind-gitops.yaml): kho Git giả lập trong cụm, commit ký GPG bằng khoá
#     tạm, hai cell giả lập kind-a (đợt 1) và kind-b (đợt 2); phát hành một digest mới đi lần lượt theo đợt; trong
#     cửa sổ đóng băng đồng bộ tự động bị chặn; commit không ký bị từ chối.
#   - Argo Rollouts 1.8 với Rollout và AnalysisTemplate slo-api của thư viện vt-service: bản không lỗi được thăng cấp,
#     bản trả 5% lỗi bị phân tích đánh dấu thất bại và Rollout quay về bản ổn định.
# Cần cụm kind dựng bằng tao-cum.sh, docker, helm, kubectl, git, gpg. Khoá GPG chỉ sống trong thư mục tạm.
# Dùng: deploy/environments/kind/kiem-thu-gitops.sh
set -euo pipefail
cd "$(dirname "$0")"
GOC=$(cd ../../.. && pwd)
ACD_CHART=9.0.6      # Argo CD v3.1.9
RO_CHART=2.40.7      # Argo Rollouts v1.8.4
PROM=quay.io/prometheus/prometheus:v3.5.0
PY=mirror.gcr.io/library/python:3.13-alpine
# Máy chủ Git HTTP "smart" tối giản (gitops/kho-git): Argo CD dùng go-git, không đọc được HTTP "dumb".
GIT_ANH=vt-kind/kho-git:1
ANH=vt-kind/ung-dung-thu
WORKERS=$(kind get nodes --name "${CLUSTER:-vt-cell-kind}" | grep worker | tr '\n' ' ')
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
W=$(mktemp -d)
trap 'rm -rf "${W:?}"' EXIT
export GNUPGHOME="$W/gnupg"; mkdir -m 700 "$GNUPGHOME"
cho() { # <số lần> <lệnh...>: thử lại mỗi 5 giây
  local n=$1; shift
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 5; done; return 1
}
app() { kubectl -n argocd get application "ung-dung-thu-$1-hl" -o jsonpath="$2" 2>/dev/null; }
anh_ns() { kubectl -n "vt-thu-gitops-$1" get deploy ung-dung-thu -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }

echo "== Ảnh thử (bản 1, bản 2, bản lỗi 5%), Argo CD $ACD_CHART, Argo Rollouts $RO_CHART"
(cd gitops/kho-git && CGO_ENABLED=0 go build -o kho-git . && docker build -q -t $GIT_ANH . >/dev/null && rm -f kho-git)
docker save -o "$W/g.tar" $GIT_ANH
for n in $WORKERS; do docker exec -i "$n" ctr -n k8s.io images import --snapshotter=overlayfs - < "$W/g.tar" >/dev/null; done
docker build -q -t $ANH:1 --build-arg PHIEN_BAN=1 gitops/anh >/dev/null
docker build -q -t $ANH:2 --build-arg PHIEN_BAN=2 gitops/anh >/dev/null
docker build -q -t $ANH:loi --build-arg PHIEN_BAN=3 --build-arg TI_LE_LOI=0.05 gitops/anh >/dev/null
declare -A DG
for t in 1 2 loi; do
  DG[$t]=$(docker image inspect -f '{{.Id}}' $ANH:$t)
  docker save -o "$W/a.tar" $ANH:$t
  for n in $WORKERS; do
    docker exec -i "$n" ctr -n k8s.io images import --snapshotter=overlayfs - < "$W/a.tar" >/dev/null
    docker exec "$n" ctr -n k8s.io images tag --force "docker.io/$ANH:$t" "docker.io/$ANH@${DG[$t]}" >/dev/null
  done
done
rm -f "$W/a.tar"
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null
cat > "$W/acd.yaml" <<EOF
dex: { enabled: false }
notifications: { enabled: false }
# Bản sao mirror.gcr.io của ảnh redis chính thức (ECR Public hay giới hạn lượt kéo).
redis: { image: { repository: mirror.gcr.io/library/redis, tag: 7.2.11-alpine } }
configs:
  params:
    server.insecure: true
    applicationsetcontroller.enable.progressive.syncs: true
  cm:
    timeout.reconciliation: 20s
EOF
ANH_NEN=$( (helm template a argo/argo-cd --version $ACD_CHART -f "$W/acd.yaml" --kube-version 1.33.0;
           helm template r argo/argo-rollouts --version $RO_CHART) | sed -n 's/^[ -]*image: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' | sort -u)
NAP_NODES="$WORKERS" ./nap-anh.sh $ANH_NEN $PROM $PY >/dev/null
helm upgrade --install argo-rollouts argo/argo-rollouts --version $RO_CHART -n argo-rollouts --create-namespace \
  --wait --timeout 5m >/dev/null && ok "Argo Rollouts sẵn sàng"

echo "== Kho GitOps giả lập: commit ký bằng khoá GPG tạm, phục vụ qua HTTP trong cụm"
gpg --batch --pinentry-mode loopback --passphrase '' --quick-gen-key "VT GitOps thu <gitops-thu@vt.local>" ed25519 sign 1d 2>/dev/null
KHOA=$(gpg --list-keys --with-colons | awk -F: '/^pub/{print $5; exit}')
gpg --armor --export "$KHOA" > "$W/khoa.asc"
R="$W/kho"; mkdir -p "$R/deploy/environments/kind" "$R/deploy/charts" "$R/deploy/cells"
cp -r "$GOC/deploy/charts/vt-service" "$R/deploy/charts/"
cp -r gitops "$R/deploy/environments/kind/"
rm -rf "$R/deploy/environments/kind/gitops/ung-dung-thu/charts" "$R/deploy/environments/kind/gitops/ung-dung-thu/.gitignore"
helm dependency update "$R/deploy/environments/kind/gitops/ung-dung-thu" >/dev/null
for c in kind-a:1 kind-b:2; do
  mkdir -p "$R/deploy/cells/${c%:*}"
  cat > "$R/deploy/cells/${c%:*}/cell.yaml" <<EOF
cell: { name: ${c%:*}, displayName: CELL-${c%:*}, sizing: M, tenancy: silo, primarySite: hl, standbySite: sn,
        releaseWave: "${c#*:}", argocd: mgmt, silos: [] }
EOF
done
ghi_digest() { mkdir -p "$R/deploy/environments/kind-gitops"
  printf 'images:\n  ung-dung-thu:\n    repository: %s\n    digest: "%s"\n' "$ANH" "$1" > "$R/deploy/environments/kind-gitops/digests.yaml"; }
ghi_digest "${DG[1]}"
git -C "$R" init -q -b main
git -C "$R" config user.name "VT GitOps thu"; git -C "$R" config user.email gitops-thu@vt.local
git -C "$R" config user.signingkey "$KHOA"; git -C "$R" config gpg.program gpg
commit() { git -C "$R" add -A; git -C "$R" commit -q "$@"; }
commit -S -m "Khởi tạo kho GitOps thử"
kubectl create ns gitops-thu --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -n gitops-thu -f - >/dev/null <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: { name: git }
spec:
  selector: { matchLabels: { app: git } }
  template:
    metadata: { labels: { app: git } }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
      containers:
        - name: git
          image: docker.io/$GIT_ANH
          imagePullPolicy: Never
          env: [{ name: HOME, value: /tmp }]
          securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
          volumeMounts: [{ name: srv, mountPath: /srv }, { name: tmp, mountPath: /tmp }]
      volumes: [{ name: srv, emptyDir: {} }, { name: tmp, emptyDir: {} }]
---
apiVersion: v1
kind: Service
metadata: { name: git }
spec: { selector: { app: git }, ports: [{ port: 80, targetPort: 8080 }] }
EOF
kubectl -n gitops-thu rollout status deploy/git --timeout 3m >/dev/null
day() { # đẩy kho trần lên máy chủ Git giả lập
  rm -rf "$W/bare"; git clone -q --bare "$R" "$W/bare"; git -C "$W/bare" update-server-info
  local p; p=$(kubectl -n gitops-thu get pod -l app=git -o name | head -1)
  tar -C "$W" -cf - bare | kubectl -n gitops-thu exec -i "${p#pod/}" -- sh -c 'cd /srv && rm -rf gitops.git bare && tar xf - && mv bare gitops.git'
}
day

echo "== Argo CD và chart deploy/argocd (AppProject, ApplicationSet), hai cụm đích cho hai cell giả lập"
helm upgrade --install argocd argo/argo-cd --version $ACD_CHART -n argocd --create-namespace -f "$W/acd.yaml" \
  --set-file "configs.gpg.keys.$KHOA=$W/khoa.asc" --wait --timeout 8m >/dev/null && ok "Argo CD sẵn sàng"
for c in kind-a kind-b; do kubectl create ns "vt-thu-gitops-$c" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; done
TOKEN=$(kubectl -n argocd create token argocd-application-controller --duration 24h)
CA=$(kubectl -n argocd get cm kube-root-ca.crt -o jsonpath='{.data.ca\.crt}' | base64 -w0)
cum() { # <tên> <địa chỉ>: cùng API server của kind dưới hai địa chỉ, để Argo CD coi là hai cụm
  kubectl -n argocd apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Secret
metadata: { name: "$1", labels: { argocd.argoproj.io/secret-type: cluster } }
stringData:
  name: "$1"
  server: "$2"
  config: '{"bearerToken":"$TOKEN","tlsClientConfig":{"caData":"$CA"}}'
EOF
}
cum cell-kind-a-hl https://kubernetes.default.svc.cluster.local
cum cell-kind-b-hl https://kubernetes.default.svc.cluster.local:443
# Argo CD 3.1.9: bộ sinh git của ApplicationSet lỗi "permission denied" khi AppProject của template có signatureKeys
# (repo-server kiểm chữ ký trong GetGitFiles ngoài vùng khoá thư mục kho). Vì vậy kiểm chữ ký bằng một Application
# riêng ở cuối; ApplicationSet chạy với AppProject không signatureKeys (xem wiki 12).
ap_chart() { helm template vt-gitops "$GOC/deploy/argocd" -f values-kind-gitops.yaml "$@" | kubectl apply -f - >/dev/null; }
ap_chart
lam_moi() { kubectl -n argocd annotate applicationset okr-ung-dung-thu argocd.argoproj.io/application-set-refresh=true --overwrite >/dev/null
  for c in kind-a kind-b; do kubectl -n argocd annotate application "ung-dung-thu-$c-hl" argocd.argoproj.io/refresh=hard --overwrite >/dev/null 2>&1 || true; done; }
san_sang() { [ "$(app "$1" '{.status.sync.status}/{.status.health.status}')" = "Synced/Healthy" ]; }
if cho 60 san_sang kind-a && cho 60 san_sang kind-b; then
  ok "ApplicationSet sinh 2 Application (2 cell × 1 TTDL × 1 service), cả hai Synced/Healthy với bản 1"
else hong "Application ban đầu: $(app kind-a '{.status.sync.status}/{.status.health.status}') $(app kind-b '{.status.sync.status}/{.status.health.status}')"; fi
[ "$(kubectl -n argocd get application -l vt.io/service=ung-dung-thu --no-headers | wc -l)" = 2 ] && ok "đúng 2 Application" || hong "số Application"

echo "== Phát hành bản 2 theo đợt: kind-a (đợt 1) xong rồi mới tới kind-b (đợt 2)"
ghi_digest "${DG[2]}"; commit -S -m "Phát hành bản 2"; day; lam_moi
xong_a() { [[ "$(anh_ns kind-a)" == *"${DG[2]}" ]] && san_sang kind-a; }
if cho 60 xong_a; then
  ok "kind-a nhận bản 2 trước"
else hong "kind-a không nhận bản 2: $(anh_ns kind-a)"; fi
xong_b() { [[ "$(anh_ns kind-b)" == *"${DG[2]}" ]] && san_sang kind-b; }
cho 60 xong_b && ok "kind-b nhận bản 2 sau kind-a" || hong "kind-b không nhận bản 2"
TA=$(app kind-a '{.status.operationState.finishedAt}'); TB=$(app kind-b '{.status.operationState.startedAt}')
[[ "$TB" > "$TA" || "$TB" == "$TA" ]] && ok "thứ tự đồng bộ theo wave: kind-a xong $TA, kind-b bắt đầu $TB" || hong "kind-b bắt đầu $TB trước khi kind-a xong $TA"
kubectl -n argocd get applicationset okr-ung-dung-thu -o jsonpath='{range .status.applicationStatus[*]}{.application} bước {.step}: {.status}{"\n"}{end}' | sed 's/^/    /'

echo "== Cửa sổ đóng băng: đồng bộ tự động bị chặn, mở cửa sổ thì phát hành tiếp"
ap_chart --set-json 'syncWindowsBoSung=[{"kind":"deny","schedule":"* * * * *","duration":"1h","applications":["*"],"manualSync":true,"timeZone":"Asia/Ho_Chi_Minh"}]'
ghi_digest "${DG[1]}"; commit -S -m "Quay về bản 1 trong cửa sổ đóng băng"; day; lam_moi
sleep 60; lam_moi; sleep 30
if [[ "$(anh_ns kind-a)" == *"${DG[2]}" && "$(anh_ns kind-b)" == *"${DG[2]}" ]]; then
  ok "trong cửa sổ đóng băng, hai cell giữ bản 2 sau 90 giây (trạng thái $(app kind-a '{.status.sync.status}'))"
else hong "cửa sổ đóng băng không chặn: kind-a $(anh_ns kind-a)"; fi
ap_chart
lam_moi
ve_1() { [[ "$(anh_ns kind-a)" == *"${DG[1]}" && "$(anh_ns kind-b)" == *"${DG[1]}" ]]; }
cho 60 ve_1 && ok "bỏ cửa sổ đóng băng: hai cell đồng bộ bản 1" || hong "sau cửa sổ đóng băng không đồng bộ"

echo "== Chữ ký commit: AppProject có signatureKeys từ chối HEAD không ký, nhận HEAD có chữ ký"
kubectl apply -n argocd -f - >/dev/null <<EOF
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata: { name: thu-chu-ky }
spec:
  sourceRepos: ["http://git.gitops-thu.svc.cluster.local/gitops.git"]
  signatureKeys: [{ keyID: "$KHOA" }]
  destinations: [{ server: "*", namespace: default }]
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata: { name: kiem-chu-ky }
spec:
  project: thu-chu-ky
  source: { repoURL: "http://git.gitops-thu.svc.cluster.local/gitops.git", targetRevision: main, path: deploy/cells, directory: { recurse: true, include: "{}" } }
  destination: { server: https://kubernetes.default.svc, namespace: default }
EOF
ck() { kubectl -n argocd get application kiem-chu-ky -o jsonpath="$1" 2>/dev/null; }
ky_ok() { [ "$(ck '{.status.sync.status}')" = "Synced" ] && [ "$(ck '{.status.sync.revision}')" = "$(git -C "$R" rev-parse HEAD)" ]; }
cho 24 ky_ok && ok "HEAD có chữ ký của khoá trong signatureKeys: Synced" || hong "HEAD có chữ ký: $(ck '{.status.sync.status} {.status.conditions[*].message}')"
ghi_digest "${DG[2]}"; commit -m "Commit không ký"; day
kubectl -n argocd annotate application kiem-chu-ky argocd.argoproj.io/refresh=hard --overwrite >/dev/null
tu_choi() { ck '{.status.conditions[*].message}' | grep -qi 'signature\|signed'; }
cho 24 tu_choi && ok "HEAD không ký bị từ chối: $(ck '{.status.conditions[0].message}' | cut -c1-120)" || hong "commit không ký không bị từ chối"

echo "== Canary với phân tích SLI (Argo Rollouts, AnalysisTemplate slo-api của vt-service)"
kubectl create ns giam-sat-thu --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -n giam-sat-thu -f - >/dev/null <<EOF
apiVersion: v1
kind: ServiceAccount
metadata: { name: prometheus }
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata: { name: prometheus-thu }
rules: [{ apiGroups: [""], resources: [pods], verbs: [get, list, watch] }]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: { name: prometheus-thu }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: prometheus-thu }
subjects: [{ kind: ServiceAccount, name: prometheus, namespace: giam-sat-thu }]
---
apiVersion: v1
kind: ConfigMap
metadata: { name: prometheus }
data:
  prometheus.yml: |
    global: { scrape_interval: 5s }
    scrape_configs:
      - job_name: pod
        kubernetes_sd_configs: [{ role: pod, namespaces: { names: [canary-thu] } }]
        relabel_configs:
          - { source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape], regex: "true", action: keep }
          - { source_labels: [__meta_kubernetes_pod_ip], target_label: __address__, replacement: "\$1:8080" }
          - { source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_name], target_label: service }
          - { source_labels: [__meta_kubernetes_pod_label_role], target_label: role }
          - { source_labels: [__meta_kubernetes_pod_name], target_label: pod }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: prometheus }
spec:
  selector: { matchLabels: { app: prometheus } }
  template:
    metadata: { labels: { app: prometheus } }
    spec:
      serviceAccountName: prometheus
      containers:
        - name: prometheus
          image: $PROM
          imagePullPolicy: IfNotPresent
          args: [--config.file=/etc/prometheus/prometheus.yml, --storage.tsdb.path=/tmp/tsdb]
          volumeMounts: [{ name: cfg, mountPath: /etc/prometheus }]
      volumes: [{ name: cfg, configMap: { name: prometheus } }]
---
apiVersion: v1
kind: Service
metadata: { name: prometheus }
spec: { selector: { app: prometheus }, ports: [{ port: 9090 }] }
EOF
kubectl -n giam-sat-thu rollout status deploy/prometheus --timeout 3m >/dev/null
CN=(-n canary-thu --create-namespace "$GOC/deploy/environments/kind/gitops/ung-dung-thu" --set namespace=canary-thu
    --set workload.kind=Rollout --set workload.rollout.sloAnalysis.enabled=true
    --set workload.rollout.sloAnalysis.prometheus=http://prometheus.giam-sat-thu.svc:9090
    --set workload.rollout.sloAnalysis.interval=20s --set workload.rollout.sloAnalysis.count=6
    --set-json 'workload.rollout.canarySteps=[{"setWeight":50},{"analysis":"slo"},{"setWeight":100}]')
helm dependency update "$GOC/deploy/environments/kind/gitops/ung-dung-thu" >/dev/null
helm upgrade --install canary "${CN[@]}" --set image.digest="${DG[1]}" >/dev/null
kubectl apply -n canary-thu -f - >/dev/null <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: { name: sinh-tai }
spec:
  selector: { matchLabels: { app: sinh-tai } }
  template:
    metadata: { labels: { app: sinh-tai, vt.io/vai-tro: tai } }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
      containers:
        - name: tai
          image: $PY
          imagePullPolicy: IfNotPresent
          securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
          command: [python3, -c]
          args:
            - |
              import time, urllib.request
              while True:
                  for m in ("GET", "GET", "POST"):
                      try:
                          urllib.request.urlopen(urllib.request.Request("http://ung-dung-thu:8080/", method=m, data=b"x" if m == "POST" else None), timeout=2).read()
                      except Exception:
                          pass
                  time.sleep(0.05)
EOF
ro() { kubectl -n canary-thu get rollout ung-dung-thu -o jsonpath="$1" 2>/dev/null; }
on_dinh() { [ "$(ro '{.status.phase}')" = "Healthy" ] && [[ "$(ro '{.spec.template.spec.containers[0].image}')" == *"$1" ]]; }
cho 36 on_dinh "${DG[1]}" && ok "Rollout bản 1 Healthy, có tải thử" || hong "Rollout bản 1: $(ro '{.status.phase} {.status.message}')"
helm upgrade canary "${CN[@]}" --set image.digest="${DG[2]}" >/dev/null
thang_cap() { [ "$(ro '{.status.phase}')" = "Healthy" ] && [ "$(ro '{.status.stableRS}')" = "$(ro '{.status.currentPodHash}')" ]; }
if cho 60 thang_cap; then ok "bản 2 (không lỗi) qua phân tích SLI và được thăng cấp"
else hong "bản 2: $(ro '{.status.phase} {.status.message}')"; fi
helm upgrade canary "${CN[@]}" --set image.digest="${DG[loi]}" >/dev/null
huy() { [ "$(ro '{.status.abort}')" = "true" ]; }
if cho 60 huy; then
  AR=$(kubectl -n canary-thu get analysisrun --sort-by=.metadata.creationTimestamp -o name | tail -1)
  ok "bản lỗi 5%: Rollout bị huỷ ($(ro '{.status.phase}'): $(ro '{.status.message}' | cut -c1-90))"
  [ "$(kubectl -n canary-thu get "$AR" -o jsonpath='{.status.phase}')" = "Failed" ] && ok "AnalysisRun $(basename "$AR") Failed" || hong "AnalysisRun không Failed"
  kubectl -n canary-thu get "$AR" -o jsonpath='{range .status.metricResults[*]}{.name}: {.phase}, đo {.count} lần, sai {.failed}; giá trị cuối {.measurements[-1:].value}{"\n"}{end}' | sed 's/^/    /'
  ROLE_STABLE=$(kubectl -n canary-thu get pod -l app.kubernetes.io/name=ung-dung-thu,role=stable -o jsonpath='{.items[*].spec.containers[0].image}')
  [[ "$ROLE_STABLE" == *"${DG[2]}"* ]] && ok "lưu lượng ở lại bản ổn định (bản 2); pod canary giữ $(ro '{.spec.strategy.canary.abortScaleDownDelaySeconds}') giây để điều tra" \
    || hong "bản ổn định không phải bản 2: $ROLE_STABLE"
else hong "bản lỗi không bị huỷ: $(ro '{.status.phase} {.status.message}')"; fi

echo; echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" = 0 ]

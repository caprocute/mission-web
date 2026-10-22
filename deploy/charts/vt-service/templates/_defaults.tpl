{{/*
Giá trị mặc định của chart thư viện vt-service (TL-04 mục 5.1, 5.2, 6.1, 6.2; TL-05 Phụ lục A).
Chart con chỉ khai báo phần khác mặc định trong values.yaml của nó; "vt-service.config" trộn
values của chart con lên trên các giá trị dưới đây.
*/}}
{{- define "vt-service.defaults" -}}
global:
  # Mã cell (ví dụ hcm, g4); gắn vào nhãn vt.io/cell.
  cell: ""
  # Hồ sơ định cỡ: S, M, L, XL hoặc compact. compact chỉ có một vùng sự cố (TL-05 Phụ lục A).
  sizing: M
  partOf: vt-platform
  # Mô hình phân phối saas | private | airgap (TL-04 mục 2.1); rỗng khi chart chạy riêng lẻ.
  deploymentModel: ""
  crypto:
    # standard (AES-256-GCM, ECDSA P-256) hoặc bcy (thuật toán Ban Cơ yếu, ADR-06).
    provider: standard
  objectStore:
    # true khi kho đối tượng chạy trong cụm (Rook-Ceph, mô hình private và airgap): luật egressTo đánh dấu
    # objectStore: true trỏ về RGW trong cụm thay vì qua egress gateway tới Ceph RGW ngoài cụm (TL-04 mục 2.1, 3.8).
    inCluster: false
    rgw: { namespace: rook-ceph, podLabels: { app: rook-ceph-rgw }, ports: [{ port: 8080 }] }
  # Cờ tính năng theo mô hình phân phối, thành biến VT_FF_<TÊN> (ví dụ publicMobileApp → VT_FF_PUBLIC_MOBILE_APP).
  featureFlags: {}
  egress:
    # false ở bản cách ly: bỏ mọi luật egressTo đánh dấu internet: true.
    allowInternet: true
  topology:
    zones: [hl-a, hl-b, hl-c]
  # Mesh Istio ambient của cell (INF-09, khớp nhãn namespace của cell-base và waypoint của cell-mesh). Rỗng khi cụm
  # không chạy mesh: khi đó không sinh AuthorizationPolicy, PeerAuthentication và cổng HBONE.
  mesh:
    ambientNamespaces: [edge, core, identity, platform, workers, analytics, secure, egress]
    waypointNamespaces: [secure, egress]
    waypointName: waypoint
    # Service có AuthorizationPolicy L7 riêng ở cell-mesh (namespace/tên). Không sinh chính sách theo cổng tại waypoint
    # cho các service này, vì các ALLOW cộng dồn sẽ mở rộng quyền ngoài đường dẫn đã duyệt (esign-allow-callers).
    l7Services: ["secure/esign-service"]
# Tên service, mặc định lấy tên release.
name: ""
namespace: ""
labels: {}
podLabels: {}
podAnnotations: {}

workload:
  # Deployment hoặc Rollout (Argo Rollouts, cho service phát hành canary).
  kind: Deployment
  # Số replica khi không bật bộ co giãn.
  replicas: 2
  revisionHistoryLimit: 5
  strategy:
    # RollingUpdate hoặc Recreate (vLLM chiếm trọn GPU, TL-04 mục 5.2).
    type: RollingUpdate
    maxSurge: 25%
    maxUnavailable: 0
  rollout:
    canarySteps:
      - setWeight: 5
      - pause: { duration: 15m }
      - setWeight: 25
      - pause: { duration: 15m }
      - setWeight: 50
      - pause: { duration: 15m }
    analysisTemplates: []
  terminationGracePeriodSeconds: 40
  preStopSleepSeconds: 5
  priorityClassName: ""

image:
  # Ảnh bắt buộc tham chiếu theo digest (SEC-49, SEC-55): repository@sha256:<64 hex>.
  repository: ""
  digest: ""
  pullPolicy: IfNotPresent
imagePullSecrets: []

# java | go | python | nginx | custom: chọn preset runtime, probe và biến môi trường mặc định.
runtime: java
java:
  # zgc cho service API (độ trễ đuôi), g1 cho service xử lý lô.
  gc: zgc
  maxRamPercentage: 70
  otelAgent: true
  otelAgentPath: /otel/opentelemetry-javaagent.jar
  extraOpts: ""
go:
  # GOMEMLIMIT = tỷ lệ % của limits.memory (TL-04 mục 5.1).
  memLimitPercent: 80
python:
  # Số worker Uvicorn; 0 nghĩa là bằng số vCPU limit (làm tròn lên).
  workers: 0

ports:
  http: 8080
  mgmt: 8081
service:
  enabled: true
  type: ClusterIP
  # Cổng mgmt chỉ mở trên Service khi cần scrape qua ServiceMonitor.
  exposeMgmt: true

probes:
  # Đường dẫn và cổng để trống thì lấy theo preset runtime; tcp: true dùng tcpSocket thay httpGet.
  startup: { path: "", port: "", periodSeconds: 5, failureThreshold: 30, timeoutSeconds: 2 }
  liveness: { path: "", port: "", periodSeconds: 10, failureThreshold: 3, timeoutSeconds: 2 }
  readiness: { path: "", port: "", periodSeconds: 5, failureThreshold: 2, timeoutSeconds: 2 }

resources:
  requests: { cpu: 500m, memory: 512Mi }
  limits: { cpu: "1", memory: 512Mi }
  # Số GPU nvidia.com/gpu cho container app (vLLM); 0 là không xin GPU.
  gpu: 0

# Ghi đè command/args của container app (thường để trống, dùng ENTRYPOINT của ảnh).
command: []
args: []
env: []
envFrom: []
extraVolumes: []
extraVolumeMounts: []
tmpSizeLimit: 1Gi

serviceAccount:
  create: true
  name: ""
  annotations: {}

# Dữ liệu ConfigMap; rỗng thì không sinh ConfigMap.
config: {}

externalSecret:
  enabled: false
  refreshInterval: 1h
  secretStoreRef: { kind: ClusterSecretStore, name: openbao }
  targetName: ""
  data: []
  dataFrom: []

# Khoá node pool theo TL-04 mục 3.3: app-general | worker | ingress | secure | system | gpu | none.
nodePool: app-general

pdb:
  enabled: true
  minAvailable: ""
  maxUnavailable: 1

autoscaling:
  hpa:
    enabled: false
    minReplicas: 3
    maxReplicas: 6
    cpuUtilization: 75
    memoryUtilization: 85
    # Chỉ số RPS mỗi pod qua Prometheus Adapter; để trống thì không dùng.
    rpsPerPod: ""
    rpsMetricName: http_server_requests_per_second
    extraMetrics: []
    behavior:
      scaleUp:
        stabilizationWindowSeconds: 0
        selectPolicy: Max
        policies:
          - { type: Percent, value: 100, periodSeconds: 30 }
          - { type: Pods, value: 4, periodSeconds: 30 }
      scaleDown:
        stabilizationWindowSeconds: 600
        selectPolicy: Min
        policies:
          - { type: Percent, value: 20, periodSeconds: 120 }
  keda:
    enabled: false
    minReplicas: 3
    maxReplicas: 6
    pollingInterval: 15
    cooldownPeriod: 300
    triggers: []
    # Số pod tối thiểu trong từng cửa sổ deadline-default: cuoiQuy, dauQuy, hoSoNam; 0 hoặc thiếu là bỏ qua.
    preScale: {}

networkPolicy:
  enabled: true
  # Mỗi phần tử: { namespace, podLabels, ports: [{port, protocol}] }.
  ingressFrom: []
  # Mỗi phần tử: { namespace, podLabels, ipBlock, ports: [{port, protocol}] }.
  # Phần tử có internet: true (đi qua egress gateway) bị bỏ khi global.egress.allowInternet là false.
  egressTo: []

# AuthorizationPolicy sinh từ networkPolicy.ingressFrom (INF-09): cùng một danh sách nguồn cho NetworkPolicy và mesh.
# Nguồn trong mesh khớp theo danh tính SPIFFE (tài khoản dịch vụ trùng nhãn app.kubernetes.io/name, hoặc cả
# namespace khi không có podLabels); nguồn ngoài mesh (observability, dữ liệu) chỉ mở theo cổng, kèm
# PeerAuthentication PERMISSIVE riêng cổng đó vì lưu lượng không có mTLS.
authorizationPolicy:
  enabled: true

serviceMonitor:
  enabled: true
  interval: 15s
  path: ""
  port: mgmt

prometheusRule:
  enabled: false
  groups: []

opa:
  enabled: false
  image:
    repository: ""
    digest: ""
  args: ["run", "--server", "--addr=127.0.0.1:8181", "--disable-telemetry", "/policies"]
  resources:
    requests: { cpu: 100m, memory: 128Mi }
    limits: { cpu: 500m, memory: 256Mi }
{{- end -}}

{{/* Preset probe theo runtime. */}}
{{- define "vt-service.probePreset" -}}
{{- $rt := . -}}
{{- if eq $rt "java" -}}
startup: { path: /actuator/health/liveness, port: mgmt }
liveness: { path: /actuator/health/liveness, port: mgmt }
readiness: { path: /actuator/health/readiness, port: mgmt }
metricsPath: /actuator/prometheus
{{- else if eq $rt "go" -}}
startup: { path: /healthz, port: mgmt }
liveness: { path: /healthz, port: mgmt }
readiness: { path: /readyz, port: mgmt }
metricsPath: /metrics
{{- else if eq $rt "python" -}}
startup: { path: /healthz, port: http }
liveness: { path: /healthz, port: http }
readiness: { path: /readyz, port: http }
metricsPath: /metrics
{{- else -}}
startup: { path: /healthz, port: http }
liveness: { path: /healthz, port: http }
readiness: { path: /healthz, port: http }
metricsPath: /metrics
{{- end -}}
{{- end -}}

{{- define "vt-service.serviceAccount" -}}
{{- if .v.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "vt-service.serviceAccountName" . }}
  namespace: {{ .v.namespace }}
  labels:
    {{- include "vt-service.labels" . | nindent 4 }}
  {{- with .v.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: false
{{- end }}
{{- end -}}

{{- define "vt-service.configMap" -}}
{{- if .v.config }}
apiVersion: v1
kind: ConfigMap
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
data:
  {{- range $k, $val := .v.config }}
  {{ $k }}: {{ toString $val | quote }}
  {{- end }}
{{- end }}
{{- end -}}

{{/*
ExternalSecret nạp bí mật từ OpenBao (ADR-17) qua ClusterSecretStore openbao-cell (INF-13); không bao giờ chứa giá
trị bí mật. Làm mới 15 phút; Secret thuộc sở hữu ExternalSecret (Owner) nhưng giữ lại khi xoá ExternalSecret hay
khi OpenBao tạm mất (deletionPolicy Retain), để pod đang chạy không mất cấu hình (TL-04 mục 9.4, DR-15).
*/}}
{{- define "vt-service.externalSecret" -}}
{{- $es := .v.externalSecret -}}
{{- if $es.enabled }}
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  refreshInterval: {{ $es.refreshInterval }}
  secretStoreRef:
    kind: {{ $es.secretStoreRef.kind }}
    name: {{ $es.secretStoreRef.name }}
  target:
    name: {{ default (printf "%s-secrets" .v.name) $es.targetName }}
    creationPolicy: Owner
    deletionPolicy: Retain
  {{- with $es.data }}
  data:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with $es.dataFrom }}
  dataFrom:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
{{- end -}}

{{- define "vt-service.service" -}}
{{- if .v.service.enabled }}
apiVersion: v1
kind: Service
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  type: {{ .v.service.type }}
  selector:
    {{- include "vt-service.selectorLabels" . | nindent 4 }}
  ports:
    - { name: http, port: {{ .v.ports.http | int }}, targetPort: http, protocol: TCP }
    {{- if and .v.service.exposeMgmt (ne (int .v.ports.mgmt) (int .v.ports.http)) }}
    - { name: mgmt, port: {{ .v.ports.mgmt | int }}, targetPort: mgmt, protocol: TCP }
    {{- end }}
{{- end }}
{{- end -}}

{{/* Service stable và canary cho định tuyến lưu lượng của Argo Rollouts (OPS-03, INF-04). */}}
{{- define "vt-service.rolloutServices" -}}
{{- if and (eq .v.workload.kind "Rollout") .v.workload.rollout.trafficRouting .v.service.enabled }}
{{- $ctx := . }}
{{- range $i, $vai := list "stable" "canary" }}
{{- if $i }}
---
{{- end }}
apiVersion: v1
kind: Service
metadata:
  name: {{ $ctx.v.name }}-{{ $vai }}
  namespace: {{ $ctx.v.namespace }}
  labels:
    {{- include "vt-service.labels" $ctx | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    {{- include "vt-service.selectorLabels" $ctx | nindent 4 }}
  ports:
    - { name: http, port: {{ $ctx.v.ports.http | int }}, targetPort: http, protocol: TCP }
{{- end }}
{{- end }}
{{- end -}}

{{/*
AnalysisTemplate SLI của canary (TL-04 AnalysisTemplate slo-api, OPS-03, OPS-05): ngưỡng tuyệt đối trên Prometheus
của cell; vi phạm lặp lại thì Rollouts huỷ và trả lưu lượng về bản ổn định.
*/}}
{{- define "vt-service.analysisTemplate" -}}
{{- $a := .v.workload.rollout.sloAnalysis -}}
{{- if and (eq .v.workload.kind "Rollout") $a.enabled }}
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: {{ $a.name }}
  namespace: {{ .v.namespace }}
  labels:
    {{- include "vt-service.labels" . | nindent 4 }}
spec:
  args:
    - name: service
    - name: cell
  metrics:
    {{- range $a.metrics }}
    - name: {{ .name }}
      interval: {{ $a.interval }}
      count: {{ $a.count | int }}
      failureLimit: {{ .failureLimit | int }}
      successCondition: {{ .successCondition }}
      provider:
        prometheus:
          address: {{ $a.prometheus }}
          query: {{ .query | replace "@service@" "{{args.service}}" | toYaml | indent 10 | trim }}
    {{- end }}
{{- end }}
{{- end -}}

{{/* PDB đứng trước workload trong thứ tự render (TL-04 mục 5.5). */}}
{{- define "vt-service.pdb" -}}
{{- if .v.pdb.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  {{- if ne (toString .v.pdb.minAvailable) "" }}
  minAvailable: {{ .v.pdb.minAvailable }}
  {{- else }}
  maxUnavailable: {{ .v.pdb.maxUnavailable }}
  {{- end }}
  unhealthyPodEvictionPolicy: AlwaysAllow
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
{{- end }}
{{- end -}}

{{/* HPA autoscaling/v2 với behavior chuẩn TL-04 mục 6.2. */}}
{{- define "vt-service.hpa" -}}
{{- $h := .v.autoscaling.hpa -}}
{{- if $h.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  scaleTargetRef:
    apiVersion: {{ ternary "argoproj.io/v1alpha1" "apps/v1" (eq .v.workload.kind "Rollout") }}
    kind: {{ .v.workload.kind }}
    name: {{ .v.name }}
  minReplicas: {{ $h.minReplicas | int }}
  maxReplicas: {{ $h.maxReplicas | int }}
  metrics:
    {{- if $h.cpuUtilization }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ $h.cpuUtilization | int }}
    {{- end }}
    {{- if $h.memoryUtilization }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ $h.memoryUtilization | int }}
    {{- end }}
    {{- if ne (toString $h.rpsPerPod) "" }}
    - type: Pods
      pods:
        metric:
          name: {{ $h.rpsMetricName }}
        target:
          type: AverageValue
          averageValue: {{ toString $h.rpsPerPod | quote }}
    {{- end }}
    {{- with $h.extraMetrics }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  behavior:
    {{- toYaml $h.behavior | nindent 4 }}
{{- end }}
{{- end -}}

{{/*
ScaledObject KEDA (TL-04 mục 6.3, 6.4). Thứ tự trigger: độ trễ Kafka, trigger khai báo tay, CPU, rồi các cửa sổ tăng
trước: deadline-default (luôn là sàn, OPS-06) và cửa sổ bổ sung theo kỳ của cell (global.cuaSoTangTruoc).
HPA lấy giá trị lớn nhất giữa các trigger nên ngoài cửa sổ, trigger cron trả 0 và không ảnh hưởng.
*/}}
{{- define "vt-service.scaledObject" -}}
{{- $k := .v.autoscaling.keda -}}
{{- if $k.enabled }}
{{- $kf := $k.kafka | default dict }}
{{- $ps := $k.preScale | default dict }}
{{- $tenXacThuc := printf "%s-kafka-tls" .v.name }}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  scaleTargetRef:
    apiVersion: {{ ternary "argoproj.io/v1alpha1" "apps/v1" (eq .v.workload.kind "Rollout") }}
    kind: {{ .v.workload.kind }}
    name: {{ .v.name }}
  minReplicaCount: {{ $k.minReplicas | int }}
  maxReplicaCount: {{ $k.maxReplicas | int }}
  pollingInterval: {{ $k.pollingInterval | int }}
  cooldownPeriod: {{ $k.cooldownPeriod | int }}
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        {{- toYaml (default .v.autoscaling.hpa.behavior $k.behavior) | nindent 8 }}
  triggers:
    {{- range $t := $kf.topics }}
    - type: kafka
      name: {{ default (printf "lag-%s" (regexReplaceAll "[^a-z0-9]+" $t.topic "-")) $t.name | trunc 63 | trimSuffix "-" }}
      metadata:
        bootstrapServers: {{ $kf.bootstrapServers }}
        consumerGroup: {{ default $.v.name $kf.consumerGroup }}
        topic: {{ $t.topic }}
        lagThreshold: {{ $t.lagThreshold | int | quote }}
        activationLagThreshold: {{ default 1 $t.activationLagThreshold | int | quote }}
        offsetResetPolicy: {{ $kf.offsetResetPolicy }}
        allowIdleConsumers: "false"
        limitToPartitionsWithLag: "true"
        {{- if $kf.tls }}
        tls: enable
        {{- end }}
      {{- if $kf.tls }}
      authenticationRef:
        name: {{ $tenXacThuc }}
      {{- end }}
    {{- end }}
    {{- with $k.triggers }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- if or (gt (int $k.cpuUtilization) 0) (and (not $k.triggers) (not $kf.topics)) }}
    - type: cpu
      metricType: Utilization
      metadata:
        value: {{ default .v.autoscaling.hpa.cpuUtilization $k.cpuUtilization | int | quote }}
    {{- end }}
    {{- range $w := list (list "cuoi-quy" "24-31" "3,6,9,12" $ps.cuoiQuy) (list "truoc-ngay-10-dau-quy" "1-10" "1,4,7,10" $ps.dauQuy) (list "ho-so-nam-thang-1" "20-31" "1" $ps.hoSoNam) (list "ho-so-nam-ngay-1-2" "1" "2" $ps.hoSoNam) }}
    {{- if gt (int (index $w 3)) 0 }}
    - type: cron
      name: {{ index $w 0 }}
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: {{ printf "0 6 %s %s *" (index $w 1) (index $w 2) | quote }}
        end: {{ printf "0 23 %s %s *" (index $w 1) (index $w 2) | quote }}
        desiredReplicas: {{ index $w 3 | int | quote }}
    {{- end }}
    {{- end }}
    {{- range $cs := (.v.global.cuaSoTangTruoc | default list) }}
    {{- $so := int (index ($cs.soPod | default dict) $.v.name | default 0) }}
    {{- if gt $so 0 }}
    {{- range $i, $l := $cs.lich }}
    - type: cron
      name: {{ printf "%s-%d" $cs.ten $i }}
      metadata:
        timezone: Asia/Ho_Chi_Minh
        start: {{ printf "0 %d %s %s *" (int (default 6 $cs.gioBatDau)) $l.ngay (toString $l.thang) | quote }}
        end: {{ printf "0 %d %s %s *" (int (default 23 $cs.gioKetThuc)) $l.ngay (toString $l.thang) | quote }}
        desiredReplicas: {{ $so | quote }}
    {{- end }}
    {{- end }}
    {{- end }}
{{- if and $kf.topics $kf.tls }}
---
apiVersion: keda.sh/v1alpha1
kind: TriggerAuthentication
metadata:
  name: {{ $tenXacThuc }}
  namespace: {{ .v.namespace }}
  labels:
    {{- include "vt-service.labels" . | nindent 4 }}
spec:
  secretTargetRef:
    - { parameter: ca, name: {{ default (printf "kafka-%s-tls" .v.name) $kf.tlsSecret }}, key: ca.crt }
    - { parameter: cert, name: {{ default (printf "kafka-%s-tls" .v.name) $kf.tlsSecret }}, key: user.crt }
    - { parameter: key, name: {{ default (printf "kafka-%s-tls" .v.name) $kf.tlsSecret }}, key: user.key }
{{- end }}
{{- end }}
{{- end -}}

{{/*
NetworkPolicy: chỉ mở đúng các luồng khai báo và DNS tới kube-system/kube-dns cổng 53 (TL-04 mục 3.6).
Chính sách default-deny của namespace do chart cell-base (INF-08) tạo.
*/}}
{{- define "vt-service.peer" -}}
{{- $peer := dict -}}
{{- if .ipBlock -}}
{{- $_ := set $peer "ipBlock" .ipBlock -}}
{{- else -}}
{{- if .namespace -}}
{{- $_ := set $peer "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" .namespace)) -}}
{{- end -}}
{{- if .podLabels -}}
{{- $_ := set $peer "podSelector" (dict "matchLabels" .podLabels) -}}
{{- else if not .namespace -}}
{{- $_ := set $peer "podSelector" (dict) -}}
{{- end -}}
{{- end -}}
{{- toYaml (list $peer) -}}
{{- end -}}

{{- define "vt-service.ports" -}}
{{- range $i, $p := . }}
{{- if $i }}{{ "\n" }}{{ end -}}
- { protocol: {{ default "TCP" $p.protocol }}, port: {{ $p.port | int }} }
{{- end }}
{{- end -}}

{{- define "vt-service.networkPolicy" -}}
{{- $np := .v.networkPolicy -}}
{{- if $np.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  podSelector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  policyTypes: ["Ingress", "Egress"]
  ingress:
    {{- if .v.serviceMonitor.enabled }}
    # Prometheus của cell (observability-cell, INF-14) thu metric qua cổng quản trị.
    - from:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: observability } }
          podSelector: { matchLabels: { app.kubernetes.io/name: prometheus } }
      ports:
        - { protocol: TCP, port: {{ .v.ports.mgmt | int }} }
    {{- end }}
    {{- range $np.ingressFrom }}
    {{- $hb := include "vt-service.hbone" (dict "v" $.v "peer" .) }}
    - from:
        {{- include "vt-service.peer" . | nindent 8 }}
      {{- with .ports }}
      ports:
        {{- include "vt-service.ports" . | nindent 8 }}
        {{- if eq $hb "cong" }}
        - { protocol: TCP, port: 15008 }
        {{- end }}
      {{- end }}
    {{- end }}
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - { protocol: UDP, port: 53 }
        - { protocol: TCP, port: 53 }
    {{- if has .v.runtime (list "java" "go" "python") }}
    # Span OTLP gửi tới OTel agent cùng node (otel-collector, INF-14).
    - to:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: observability } }
          podSelector: { matchLabels: { app.kubernetes.io/name: otel-agent } }
      ports:
        - { protocol: TCP, port: 4317 }
        - { protocol: TCP, port: 4318 }
    {{- end }}
    {{- range $np.egressTo }}
    {{- $hb := include "vt-service.hbone" (dict "v" $.v "peer" .) }}
    - to:
        {{- include "vt-service.peer" . | nindent 8 }}
      {{- with .ports }}
      ports:
        {{- include "vt-service.ports" . | nindent 8 }}
        {{- if eq $hb "cong" }}
        - { protocol: TCP, port: 15008 }
        {{- end }}
      {{- end }}
    {{- if eq $hb "waypoint" }}
    # Đích ở namespace có waypoint: ztunnel gửi HBONE tới pod waypoint, waypoint chuyển tiếp (cell-mesh).
    - to:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: {{ .namespace }} } }
          podSelector: { matchLabels: { gateway.networking.k8s.io/gateway-name: {{ $.v.global.mesh.waypointName }} } }
      ports:
        - { protocol: TCP, port: 15008 }
    {{- end }}
    {{- end }}
{{- end }}
{{- end -}}

{{/*
Cách lưu lượng mesh ambient đi tới hoặc từ một nguồn/đích của networkPolicy (INF-09). Trả về:
"cong": hai đầu trong mesh, thêm cổng HBONE 15008; "waypoint": đích ở namespace có waypoint (chỉ dùng cho egressTo,
ingress trong namespace có waypoint đến từ pod waypoint, do cell-mesh mở); rỗng: không liên quan mesh.
*/}}
{{- define "vt-service.hbone" -}}
{{- $m := .v.global.mesh -}}
{{- $ns := default .v.namespace .peer.namespace -}}
{{- if and (has .v.namespace $m.ambientNamespaces) (has $ns $m.ambientNamespaces) (not .peer.ipBlock) -}}
{{- if has $ns $m.waypointNamespaces -}}
{{- if ne $ns .v.namespace -}}waypoint{{- end -}}
{{- else -}}cong{{- end -}}
{{- end -}}
{{- end -}}

{{/*
AuthorizationPolicy và PeerAuthentication sinh từ networkPolicy.ingressFrom (INF-09, TL-04 mục 3.7). Namespace có
waypoint: chính sách theo danh tính đặt ở waypoint (targetRefs tới Service); ztunnel chỉ cho waypoint và các cổng
mở cho nguồn ngoài mesh. Namespace không có waypoint: ztunnel thực thi theo selector của workload.
*/}}
{{- define "vt-service.mesh" -}}
{{- $v := .v -}}
{{- $m := $v.global.mesh -}}
{{- if and $v.authorizationPolicy.enabled (has $v.namespace $m.ambientNamespaces) -}}
{{- $coWaypoint := has $v.namespace $m.waypointNamespaces -}}
{{- $trongMesh := list -}}
{{- $ngoaiMesh := list -}}
{{- if and $v.serviceMonitor.enabled $v.service.enabled -}}
{{- $ngoaiMesh = append $ngoaiMesh (int $v.ports.mgmt) -}}
{{- end -}}
{{- range $v.networkPolicy.ingressFrom -}}
{{- $ns := default $v.namespace .namespace -}}
{{- $cong := list -}}
{{- range .ports -}}{{- $cong = append $cong (int .port) -}}{{- end -}}
{{- if and (has $ns $m.ambientNamespaces) (not .ipBlock) -}}
{{- $nguon := dict -}}
{{- $ten := "" -}}
{{- if .podLabels -}}{{- $ten = index .podLabels "app.kubernetes.io/name" | default "" -}}{{- end -}}
{{- if $ten -}}
{{- $_ := set $nguon "principals" (list (printf "cluster.local/ns/%s/sa/%s" $ns $ten)) -}}
{{- else if .podLabels -}}
{{- fail (printf "vt-service[%s]: ingressFrom từ namespace %s trong mesh cần nhãn app.kubernetes.io/name để suy ra danh tính SPIFFE" $v.name $ns) -}}
{{- else -}}
{{- $_ := set $nguon "namespaces" (list $ns) -}}
{{- end -}}
{{- $trongMesh = append $trongMesh (dict "source" $nguon "ports" $cong) -}}
{{- else -}}
{{- if not $cong -}}{{- fail (printf "vt-service[%s]: ingressFrom từ %s ngoài mesh phải khai ports" $v.name $ns) -}}{{- end -}}
{{- $ngoaiMesh = concat $ngoaiMesh $cong -}}
{{- end -}}
{{- end -}}
{{- $ngoaiMesh = $ngoaiMesh | uniq | sortAlpha -}}
{{- $l7 := has (printf "%s/%s" $v.namespace $v.name) $m.l7Services -}}
{{- if and $trongMesh (not $l7) }}
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  {{- if $coWaypoint }}
  targetRefs:
    - kind: Service
      group: ""
      name: {{ $v.name }}
  {{- else }}
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  {{- end }}
  action: ALLOW
  rules:
    {{- range $trongMesh }}
    - from:
        - source:
            {{- toYaml .source | nindent 12 }}
      {{- if .ports }}
      to:
        - operation:
            ports: [{{ range $i, $p := .ports }}{{ if $i }}, {{ end }}"{{ $p }}"{{ end }}]
      {{- end }}
    {{- end }}
{{- end }}
{{- if $ngoaiMesh }}
---
# Nguồn ngoài mesh (Prometheus, namespace dữ liệu) tới cổng {{ join ", " $ngoaiMesh }}: lưu lượng không mTLS, chỉ NetworkPolicy
# giới hạn nguồn. Cổng khác của pod vẫn STRICT.
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: {{ $v.name }}-ngoai-mesh
  namespace: {{ $v.namespace }}
  labels:
    {{- include "vt-service.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  action: ALLOW
  rules:
    - to:
        - operation:
            ports: [{{ range $i, $p := $ngoaiMesh }}{{ if $i }}, {{ end }}"{{ $p }}"{{ end }}]
---
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  mtls:
    mode: STRICT
  portLevelMtls:
    {{- range $ngoaiMesh }}
    {{ . }}:
      mode: PERMISSIVE
    {{- end }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "vt-service.serviceMonitor" -}}
{{- $sm := .v.serviceMonitor -}}
{{- if and $sm.enabled .v.service.enabled }}
{{- $preset := fromYaml (include "vt-service.probePreset" .v.runtime) }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  namespaceSelector:
    matchNames: [{{ .v.namespace }}]
  endpoints:
    - port: {{ ternary "http" $sm.port (eq (int .v.ports.mgmt) (int .v.ports.http)) }}
      path: {{ default $preset.metricsPath $sm.path }}
      interval: {{ $sm.interval }}
      scrapeTimeout: 10s
{{- end }}
{{- end -}}

{{- define "vt-service.prometheusRule" -}}
{{- if and .v.prometheusRule.enabled .v.prometheusRule.groups }}
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  groups:
    {{- toYaml .v.prometheusRule.groups | nindent 4 }}
{{- end }}
{{- end -}}

{{/*
Điểm vào duy nhất cho chart con:  {{ include "vt-service.all" . }}
Thứ tự: ServiceAccount, ConfigMap, ExternalSecret, PDB, Service, workload, bộ co giãn,
NetworkPolicy, ServiceMonitor, PrometheusRule.
*/}}
{{- define "vt-service.all" -}}
{{- $v := fromYaml (include "vt-service.context" .) -}}
{{- if hasKey $v "Error" -}}
{{- fail (printf "vt-service: lỗi dựng cấu hình: %v" $v.Error) -}}
{{- end -}}
{{- $ctx := dict "root" . "v" $v -}}
{{- range $t := list "serviceAccount" "configMap" "externalSecret" "pdb" "service" "rolloutServices" "analysisTemplate" "workload" "hpa" "scaledObject" "networkPolicy" "mesh" "serviceMonitor" "prometheusRule" }}
{{- $out := include (printf "vt-service.%s" $t) $ctx | trim }}
{{- if $out }}
---
{{ $out }}
{{- end }}
{{- end }}
{{- end -}}

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

{{/* ExternalSecret nạp bí mật từ OpenBao (ADR-17); không bao giờ chứa giá trị bí mật. */}}
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

{{/* ScaledObject KEDA; dùng chung behavior HPA chuẩn qua advanced.horizontalPodAutoscalerConfig. */}}
{{- define "vt-service.scaledObject" -}}
{{- $k := .v.autoscaling.keda -}}
{{- if $k.enabled }}
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
        {{- toYaml .v.autoscaling.hpa.behavior | nindent 8 }}
  triggers:
    {{- if $k.triggers }}
    {{- toYaml $k.triggers | nindent 4 }}
    {{- else }}
    - type: cpu
      metricType: Utilization
      metadata:
        value: {{ .v.autoscaling.hpa.cpuUtilization | int | quote }}
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
    {{- range $np.ingressFrom }}
    - from:
        {{- include "vt-service.peer" . | nindent 8 }}
      {{- with .ports }}
      ports:
        {{- include "vt-service.ports" . | nindent 8 }}
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
    {{- range $np.egressTo }}
    - to:
        {{- include "vt-service.peer" . | nindent 8 }}
      {{- with .ports }}
      ports:
        {{- include "vt-service.ports" . | nindent 8 }}
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
{{- range $t := list "serviceAccount" "configMap" "externalSecret" "pdb" "service" "workload" "hpa" "scaledObject" "networkPolicy" "serviceMonitor" "prometheusRule" }}
{{- $out := include (printf "vt-service.%s" $t) $ctx | trim }}
{{- if $out }}
---
{{ $out }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
Deployment hoặc Rollout theo TL-04 mục 5.1 (quy ước Java 1–7) và manifest mẫu mục 5.5.
*/}}
{{- define "vt-service.workload" -}}
{{- $v := .v -}}
{{- $auto := or $v.autoscaling.hpa.enabled $v.autoscaling.keda.enabled -}}
{{- if eq $v.workload.kind "Rollout" }}
apiVersion: argoproj.io/v1alpha1
kind: Rollout
{{- else }}
apiVersion: apps/v1
kind: Deployment
{{- end }}
metadata:
  {{- include "vt-service.metadata" . | nindent 2 }}
spec:
  {{- if not $auto }}
  replicas: {{ $v.workload.replicas | int }}
  {{- end }}
  revisionHistoryLimit: {{ $v.workload.revisionHistoryLimit | int }}
  {{- if eq $v.workload.kind "Rollout" }}
  strategy:
    canary:
      maxSurge: {{ $v.workload.strategy.maxSurge | quote }}
      maxUnavailable: {{ $v.workload.strategy.maxUnavailable }}
      {{- $ro := $v.workload.rollout }}
      {{- if $ro.roleLabels }}
      canaryMetadata: { labels: { role: canary } }
      stableMetadata: { labels: { role: stable } }
      {{- end }}
      {{- with $ro.trafficRouting }}
      canaryService: {{ $v.name }}-canary
      stableService: {{ $v.name }}-stable
      trafficRouting:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      abortScaleDownDelaySeconds: {{ $ro.abortScaleDownDelaySeconds | int }}
      steps:
        {{- range $ro.canarySteps }}
        {{- if eq (toString (get . "analysis")) "slo" }}
        {{- if not $ro.sloAnalysis.enabled }}
        {{- fail (printf "vt-service[%s]: bước analysis: slo cần workload.rollout.sloAnalysis.enabled" $v.name) }}
        {{- end }}
        - analysis:
            templates: [{ templateName: {{ $ro.sloAnalysis.name }} }]
            args:
              - { name: service, value: {{ $v.name | quote }} }
              - { name: cell, value: {{ $v.global.cell | default "khong-ro" | quote }} }
        {{- else }}
        - {{ toYaml . | nindent 10 | trim }}
        {{- end }}
        {{- end }}
      {{- with $ro.analysisTemplates }}
      analysis:
        templates:
          {{- range . }}
          - templateName: {{ . }}
          {{- end }}
      {{- end }}
  {{- else if eq $v.workload.strategy.type "Recreate" }}
  strategy:
    type: Recreate
  {{- else }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: {{ $v.workload.strategy.maxSurge }}
      maxUnavailable: {{ $v.workload.strategy.maxUnavailable }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "vt-service.selectorLabels" . | nindent 6 }}
  template:
    {{- include "vt-service.podTemplate" . | nindent 4 }}
{{- end -}}

{{- define "vt-service.podTemplate" -}}
{{- $v := .v -}}
{{- $preset := fromYaml (include "vt-service.probePreset" $v.runtime) -}}
metadata:
  labels:
    {{- include "vt-service.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/part-of: {{ $v.global.partOf }}
    {{- with $v.global.cell }}
    vt.io/cell: {{ . | quote }}
    {{- end }}
    {{- with $v.podLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  annotations:
    {{- if $v.serviceMonitor.enabled }}
    prometheus.io/scrape: "true"
    prometheus.io/port: {{ $v.ports.mgmt | int | quote }}
    prometheus.io/path: {{ default $preset.metricsPath $v.serviceMonitor.path }}
    {{- end }}
    {{- if $v.config }}
    checksum/config: {{ toYaml $v.config | sha256sum }}
    {{- end }}
    {{- with $v.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  serviceAccountName: {{ include "vt-service.serviceAccountName" . }}
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: {{ $v.workload.terminationGracePeriodSeconds | int }}
  {{- with $v.workload.priorityClassName }}
  priorityClassName: {{ . }}
  {{- end }}
  {{- with $v.imagePullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    fsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  {{- include "vt-service.scheduling" . | nindent 2 }}
  containers:
    - name: app
      image: "{{ $v.image.repository }}@{{ $v.image.digest }}"
      imagePullPolicy: {{ $v.image.pullPolicy }}
      {{- with $v.command }}
      command:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $v.args }}
      args:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      ports:
        - { name: http, containerPort: {{ $v.ports.http | int }} }
        {{- if ne (int $v.ports.mgmt) (int $v.ports.http) }}
        - { name: mgmt, containerPort: {{ $v.ports.mgmt | int }} }
        {{- end }}
      {{- $env := include "vt-service.runtimeEnv" . | fromYamlArray }}
      {{- with $v.global.deploymentModel }}
      {{- $env = concat $env (list (dict "name" "VT_DEPLOYMENT_MODEL" "value" .) (dict "name" "VT_CRYPTO_PROVIDER" "value" $v.global.crypto.provider) (dict "name" "VT_CELL_ID" "value" $v.global.cell)) }}
      {{- range $k, $b := $v.global.featureFlags }}
      {{- $env = append $env (dict "name" (printf "VT_FF_%s" (regexReplaceAll "([a-z])([A-Z])" $k "${1}_${2}" | upper)) "value" (toString $b)) }}
      {{- end }}
      {{- end }}
      {{- $env = concat $env $v.env }}
      {{- with $env }}
      env:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- $envFrom := $v.envFrom }}
      {{- if $v.config }}
      {{- $envFrom = prepend $envFrom (dict "configMapRef" (dict "name" $v.name)) }}
      {{- end }}
      {{- with $envFrom }}
      envFrom:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      resources:
        {{- $gpu := "" }}{{ if gt (int $v.resources.gpu) 0 }}{{ $gpu = printf ", nvidia.com/gpu: %d" (int $v.resources.gpu) }}{{ end }}
        requests: { cpu: {{ $v.resources.requests.cpu | quote }}, memory: {{ $v.resources.requests.memory }}{{ $gpu }} }
        limits:   { cpu: {{ $v.resources.limits.cpu | quote }}, memory: {{ $v.resources.limits.memory }}{{ $gpu }} }
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        runAsNonRoot: true
        capabilities:
          drop: ["ALL"]
      {{- range $kind := list "startup" "liveness" "readiness" }}
      {{- $p := index $v.probes $kind }}
      {{- $pp := index $preset $kind }}
      {{- /* Khi cổng mgmt trùng http, container chỉ mở cổng tên http nên probe "mgmt" phải đổi sang "http". */}}
      {{- $cong := default $pp.port $p.port }}
      {{- if and (eq (toString $cong) "mgmt") (eq (int $v.ports.mgmt) (int $v.ports.http)) }}{{ $cong = "http" }}{{ end }}
      {{ $kind }}Probe:
        {{- if $p.tcp }}
        tcpSocket: { port: {{ $cong }} }
        {{- else }}
        httpGet: { path: {{ default $pp.path $p.path | quote }}, port: {{ $cong }} }
        {{- end }}
        periodSeconds: {{ $p.periodSeconds | int }}
        {{- if ne $kind "startup" }}
        timeoutSeconds: {{ $p.timeoutSeconds | int }}
        {{- end }}
        failureThreshold: {{ $p.failureThreshold | int }}
      {{- end }}
      lifecycle:
        preStop:
          sleep:
            seconds: {{ $v.workload.preStopSleepSeconds | int }}
      volumeMounts:
        - { name: tmp, mountPath: /tmp }
        {{- with $v.extraVolumeMounts }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    {{- if $v.opa.enabled }}
    - name: opa
      image: "{{ $v.opa.image.repository }}@{{ $v.opa.image.digest }}"
      imagePullPolicy: IfNotPresent
      args:
        {{- toYaml $v.opa.args | nindent 8 }}
      ports:
        - { name: opa, containerPort: 8181 }
      resources:
        {{- toYaml $v.opa.resources | nindent 8 }}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        runAsNonRoot: true
        capabilities:
          drop: ["ALL"]
      readinessProbe:
        httpGet: { path: "/health?bundles", port: 8181 }
        periodSeconds: 5
      livenessProbe:
        httpGet: { path: /health, port: 8181 }
        periodSeconds: 10
    {{- end }}
  volumes:
    - name: tmp
      emptyDir:
        sizeLimit: {{ $v.tmpSizeLimit }}
    {{- with $v.extraVolumes }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end -}}

{{/* Biến môi trường preset theo runtime (TL-04 mục 5.1). */}}
{{- define "vt-service.runtimeEnv" -}}
{{- $v := .v -}}
{{- if eq $v.runtime "java" }}
{{- $gc := ternary "-XX:+UseZGC -XX:+ZGenerational" "-XX:+UseG1GC -XX:MaxGCPauseMillis=200" (eq $v.java.gc "zgc") }}
{{- $opts := printf "-XX:MaxRAMPercentage=%v %s -XX:+ExitOnOutOfMemoryError" $v.java.maxRamPercentage $gc }}
{{- if $v.java.otelAgent }}{{ $opts = printf "%s -javaagent:%s" $opts $v.java.otelAgentPath }}{{ end }}
{{- with $v.java.extraOpts }}{{ $opts = printf "%s %s" $opts . }}{{ end }}
- name: JAVA_TOOL_OPTIONS
  value: {{ $opts | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ $v.name }}
{{- else if eq $v.runtime "go" }}
- name: GOMEMLIMIT
  value: {{ printf "%dMiB" (div (mul (include "vt-service.memMiB" $v.resources.limits.memory | atoi) (int $v.go.memLimitPercent)) 100) | quote }}
- name: GOMAXPROCS
  value: {{ include "vt-service.cpuCeil" $v.resources.limits.cpu | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ $v.name }}
{{- else if eq $v.runtime "python" }}
- name: UVICORN_WORKERS
  value: {{ (ternary (include "vt-service.cpuCeil" $v.resources.limits.cpu) (toString (int $v.python.workers)) (eq (int $v.python.workers) 0)) | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ $v.name }}
{{- else }}
[]
{{- end }}
{{- end -}}

{{/* nodeSelector, toleration và topologySpreadConstraints (TL-04 mục 3.3–3.4, TL-05 Phụ lục A). */}}
{{- define "vt-service.scheduling" -}}
{{- $v := .v -}}
{{- $pool := $v.nodePool -}}
{{- if ne $pool "none" }}
{{- if eq $pool "secure" }}
nodeSelector:
  vt.io/zone: secure
tolerations:
  - { key: vt.io/zone, operator: Equal, value: secure, effect: NoSchedule }
{{- else if eq $pool "system" }}
tolerations:
  - { key: CriticalAddonsOnly, operator: Exists, effect: NoSchedule }
{{- else if eq $pool "gpu" }}
nodeSelector:
  vt.io/pool: gpu
tolerations:
  - { key: nvidia.com/gpu, operator: Equal, value: present, effect: NoSchedule }
{{- else }}
nodeSelector:
  vt.io/pool: {{ $pool }}
{{- if ne $pool "app-general" }}
tolerations:
  - { key: vt.io/pool, operator: Equal, value: {{ $pool }}, effect: NoSchedule }
{{- end }}
{{- end }}
{{- end }}
{{- $min := ternary $v.autoscaling.hpa.minReplicas (ternary $v.autoscaling.keda.minReplicas $v.workload.replicas $v.autoscaling.keda.enabled) $v.autoscaling.hpa.enabled | int }}
{{- if ge $min 2 }}
{{- $compact := or (eq (toString $v.global.sizing) "compact") (le (len $v.global.topology.zones) 1) }}
topologySpreadConstraints:
  {{- if not $compact }}
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        {{- include "vt-service.selectorLabels" . | nindent 8 }}
    matchLabelKeys: ["pod-template-hash"]
  {{- end }}
  - maxSkew: {{ ternary 1 2 $compact }}
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: {{ ternary "DoNotSchedule" "ScheduleAnyway" $compact }}
    labelSelector:
      matchLabels:
        {{- include "vt-service.selectorLabels" . | nindent 8 }}
    matchLabelKeys: ["pod-template-hash"]
{{- end }}
{{- end -}}

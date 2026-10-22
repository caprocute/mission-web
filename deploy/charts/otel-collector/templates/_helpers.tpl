{{- define "otel.image" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString .Values.image.digest)) -}}
{{- fail "otel-collector: image.digest phải là sha256 (SEC-55)" -}}
{{- end -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- end -}}

{{- define "otel.labels" -}}
app.kubernetes.io/part-of: vt-observability
vt.io/cell: {{ .Values.cell | quote }}
{{- end -}}

{{- define "otel.container" -}}
image: {{ include "otel.image" .root }}
imagePullPolicy: IfNotPresent
args: ["--config=/conf/config.yaml"]
env:
  - name: GOMEMLIMIT
    value: {{ printf "%dMiB" (div (mul (include "otel.mib" .res.limits.memory | atoi) 80) 100) | quote }}
  - name: K8S_NODE_NAME
    valueFrom: { fieldRef: { fieldPath: spec.nodeName } }
  - name: K8S_POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }
resources:
  {{- toYaml .res | nindent 2 }}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  runAsNonRoot: true
  capabilities: { drop: ["ALL"] }
livenessProbe:
  httpGet: { path: /, port: 13133 }
readinessProbe:
  httpGet: { path: /, port: 13133 }
{{- end -}}

{{- define "otel.mib" -}}
{{- $m := toString . -}}
{{- if hasSuffix "Gi" $m }}{{ mul (trimSuffix "Gi" $m | atoi) 1024 }}{{ else }}{{ trimSuffix "Mi" $m | atoi }}{{ end -}}
{{- end -}}

{{- define "otel.podSecurity" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile: { type: RuntimeDefault }
{{- end -}}

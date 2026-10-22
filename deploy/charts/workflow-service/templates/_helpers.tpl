{{- define "workflow-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "workflow-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "workflow-service.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
mission.vn/svc: SVC-06
{{- end -}}

{{- define "workflow-service.apiSelectorLabels" -}}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end -}}

{{- define "workflow-service.executorSelectorLabels" -}}
app.kubernetes.io/name: {{ include "workflow-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: executor
{{- end -}}

{{- define "workflow-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "workflow-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "workflow-service.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "workflow-service.securityContext" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
readOnlyRootFilesystem: true
allowPrivilegeEscalation: false
capabilities:
  drop:
    - ALL
{{- end -}}

{{/* Namespace đích (INF-02): khoá namespace trong values, mặc định là namespace của release. */}}
{{- define "workflow-service.namespace" -}}
{{- default .Release.Namespace .Values.namespace -}}
{{- end -}}

{{/*
Trigger cron tăng trước mốc hạn (TL-04 mục 6.4): cửa sổ deadline-default theo tt (cuoiQuy, dauQuy, hoSoNam) và cửa
sổ bổ sung theo kỳ của cell ở global.cuaSoTangTruoc (soPod theo tên Deployment). Tham số: dict tt, ten, global.
*/}}
{{- define "workflow-service.cron" -}}
{{- $tt := .tt -}}
{{- $ten := .ten -}}
{{- $ds := list -}}
{{- range $w := list (list "cuoi-quy" "24-31" "3,6,9,12" $tt.cuoiQuy) (list "truoc-ngay-10-dau-quy" "1-10" "1,4,7,10" $tt.dauQuy) (list "ho-so-nam-thang-1" "20-31" "1" $tt.hoSoNam) (list "ho-so-nam-ngay-1-2" "1" "2" $tt.hoSoNam) -}}
{{- if gt (int (index $w 3)) 0 -}}
{{- $ds = append $ds (dict "type" "cron" "name" (index $w 0) "metadata" (dict "timezone" "Asia/Ho_Chi_Minh" "start" (printf "0 6 %s %s *" (index $w 1) (index $w 2)) "end" (printf "0 23 %s %s *" (index $w 1) (index $w 2)) "desiredReplicas" (toString (int (index $w 3))))) -}}
{{- end -}}
{{- end -}}
{{- range $cs := ((.global | default dict).cuaSoTangTruoc | default list) -}}
{{- $so := int (index ($cs.soPod | default dict) $ten | default 0) -}}
{{- if gt $so 0 -}}
{{- range $i, $l := $cs.lich -}}
{{- $ds = append $ds (dict "type" "cron" "name" (printf "%s-%d" $cs.ten $i) "metadata" (dict "timezone" "Asia/Ho_Chi_Minh" "start" (printf "0 %d %s %s *" (int (default 6 $cs.gioBatDau)) $l.ngay (toString $l.thang)) "end" (printf "0 %d %s %s *" (int (default 23 $cs.gioKetThuc)) $l.ngay (toString $l.thang)) "desiredReplicas" (toString $so))) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if $ds -}}{{- toYaml $ds -}}{{- end -}}
{{- end -}}

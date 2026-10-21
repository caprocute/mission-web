{{- define "iam.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "iam.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
mission/svc: SVC-02
{{- end -}}

{{- define "iam.realmImport.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: realm-import
{{- end -}}

{{- define "iam.image" -}}
{{- if .Values.image.digest -}}
{{ .Values.image.repository }}@{{ .Values.image.digest }}
{{- else -}}
{{ .Values.image.repository }}:{{ .Values.image.tag }}
{{- end -}}
{{- end -}}

{{/* Redirect URI, web origin, URI sau đăng xuất của client mission-web sinh từ realm.web.hosts */}}
{{- define "iam.web.redirectUris" -}}
{{- $out := list -}}
{{- range required "realm.web.hosts phải có ít nhất một tên miền" .Values.realm.web.hosts -}}
{{- $out = append $out (printf "https://%s/*" .) -}}
{{- end -}}
{{- toJson $out -}}
{{- end -}}

{{- define "iam.web.origins" -}}
{{- $out := list -}}
{{- range .Values.realm.web.hosts -}}
{{- $out = append $out (printf "https://%s" .) -}}
{{- end -}}
{{- toJson $out -}}
{{- end -}}

{{- define "iam.web.postLogoutUris" -}}
{{- $out := list -}}
{{- range .Values.realm.web.hosts -}}
{{- $out = append $out (printf "https://%s/*" .) -}}
{{- end -}}
{{- join "##" $out -}}
{{- end -}}

{{/* Namespace đích (INF-02): khoá namespace trong values, mặc định là namespace của release. */}}
{{- define "iam.namespace" -}}
{{- default .Release.Namespace .Values.namespace -}}
{{- end -}}

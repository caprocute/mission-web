{{/*
Trộn sâu src vào dst tại chỗ. Khác mergeOverwrite của sprig: giá trị false, 0, "" của chart con
vẫn ghi đè mặc định (ví dụ pdb.enabled: false), danh sách được thay nguyên.
*/}}
{{- define "vt-service.mergeInto" -}}
{{- $dst := index . 0 -}}
{{- $src := index . 1 -}}
{{- range $k, $v := $src -}}
{{- if and (kindIs "map" $v) (kindIs "map" (index $dst $k)) -}}
{{- include "vt-service.mergeInto" (list (index $dst $k) $v) -}}
{{- else -}}
{{- $_ := set $dst $k $v -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Dựng cấu hình hiệu lực: mặc định của thư viện, chồng values của chart con, rồi kiểm tra.
Kết quả: dict { root: <ngữ cảnh gốc>, v: <values đã trộn>, name, namespace, probes }.
*/}}
{{- define "vt-service.context" -}}
{{- $root := . -}}
{{- $v := fromYaml (include "vt-service.defaults" $root) -}}
{{- include "vt-service.mergeInto" (list $v (deepCopy $root.Values)) -}}
{{- $name := default $root.Release.Name $v.name | trunc 63 | trimSuffix "-" -}}
{{- $_ := set $v "name" $name -}}
{{- $_ := set $v "namespace" (default $root.Release.Namespace $v.namespace) -}}
{{- include "vt-service.validate" $v -}}
{{- toYaml $v -}}
{{- end -}}

{{/* Kiểm tra values; dừng render với thông báo rõ khi vi phạm quy tắc TL-04. */}}
{{- define "vt-service.validate" -}}
{{- $v := . -}}
{{- if and $v.autoscaling.hpa.enabled $v.autoscaling.keda.enabled -}}
{{- fail (printf "vt-service[%s]: không được bật đồng thời autoscaling.hpa và autoscaling.keda cho cùng workload (TL-04 mục 6.1: mỗi Deployment chỉ có một bộ co giãn)" $v.name) -}}
{{- end -}}
{{- if contains "@" (toString $v.image.repository) -}}
{{- fail (printf "vt-service[%s]: image.repository không được chứa digest; khai báo digest ở image.digest" $v.name) -}}
{{- end -}}
{{- if not (regexMatch "^[a-z0-9]([a-z0-9._/-]*[a-z0-9])?(:[0-9]+)?(/[a-z0-9._-]+)*$" (toString $v.image.repository)) -}}
{{- fail (printf "vt-service[%s]: image.repository không hợp lệ hoặc bị bỏ trống (không được kèm tag)" $v.name) -}}
{{- end -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $v.image.digest)) -}}
{{- fail (printf "vt-service[%s]: ảnh phải tham chiếu theo digest dạng sha256:<64 ký tự hex> ở image.digest, không dùng tag (SEC-49, SEC-55); nhận được %q" $v.name (toString $v.image.digest)) -}}
{{- end -}}
{{- if and $v.opa.enabled (not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $v.opa.image.digest))) -}}
{{- fail (printf "vt-service[%s]: ảnh opa phải tham chiếu theo digest sha256 (SEC-55)" $v.name) -}}
{{- end -}}
{{- if not (has $v.workload.kind (list "Deployment" "Rollout")) -}}
{{- fail (printf "vt-service[%s]: workload.kind chỉ nhận Deployment hoặc Rollout" $v.name) -}}
{{- end -}}
{{- if not (has $v.runtime (list "java" "go" "python" "nginx" "custom")) -}}
{{- fail (printf "vt-service[%s]: runtime chỉ nhận java, go, python, nginx, custom" $v.name) -}}
{{- end -}}
{{- if not (has $v.nodePool (list "app-general" "worker" "ingress" "secure" "system" "gpu" "none")) -}}
{{- fail (printf "vt-service[%s]: nodePool %q không có trong bảng node pool TL-04 mục 3.3" $v.name $v.nodePool) -}}
{{- end -}}
{{- if and $v.pdb.enabled (ne (toString $v.pdb.minAvailable) "") (ne (toString $v.pdb.maxUnavailable) "") -}}
{{- fail (printf "vt-service[%s]: pdb chỉ được khai minAvailable hoặc maxUnavailable, không cả hai" $v.name) -}}
{{- end -}}
{{- if ne (toString $v.resources.requests.memory) (toString $v.resources.limits.memory) -}}
{{- fail (printf "vt-service[%s]: resources.requests.memory phải bằng resources.limits.memory (TL-04 mục 5.1 quy ước 4)" $v.name) -}}
{{- end -}}
{{- end -}}

{{- define "vt-service.selectorLabels" -}}
app.kubernetes.io/name: {{ .v.name }}
{{- end -}}

{{- define "vt-service.labels" -}}
{{- $v := .v -}}
app.kubernetes.io/name: {{ $v.name }}
app.kubernetes.io/part-of: {{ $v.global.partOf }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- with $v.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- with $v.labels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "vt-service.metadata" -}}
name: {{ .v.name }}
namespace: {{ .v.namespace }}
labels:
  {{- include "vt-service.labels" . | nindent 2 }}
{{- end -}}

{{- define "vt-service.serviceAccountName" -}}
{{- default .v.name .v.serviceAccount.name -}}
{{- end -}}

{{/* Số vCPU limit làm tròn lên từ chuỗi CPU Kubernetes ("2", "1500m", 0.5). */}}
{{- define "vt-service.cpuCeil" -}}
{{- $c := toString . -}}
{{- if hasSuffix "m" $c -}}
{{- $m := trimSuffix "m" $c | atoi -}}
{{- div (add $m 999) 1000 | max 1 -}}
{{- else -}}
{{- $f := float64 $c -}}
{{- ceil $f | int | max 1 -}}
{{- end -}}
{{- end -}}

{{/* Đổi chuỗi bộ nhớ Kubernetes sang MiB (Gi, Mi, G, M). */}}
{{- define "vt-service.memMiB" -}}
{{- $m := toString . -}}
{{- if hasSuffix "Gi" $m -}}{{- mulf (float64 (trimSuffix "Gi" $m)) 1024 | int -}}
{{- else if hasSuffix "Mi" $m -}}{{- trimSuffix "Mi" $m | float64 | int -}}
{{- else if hasSuffix "G" $m -}}{{- mulf (float64 (trimSuffix "G" $m)) 953.674 | int -}}
{{- else if hasSuffix "M" $m -}}{{- mulf (float64 (trimSuffix "M" $m)) 0.953674 | int -}}
{{- else -}}{{- fail (printf "vt-service: không đổi được bộ nhớ %q sang MiB" $m) -}}
{{- end -}}
{{- end -}}

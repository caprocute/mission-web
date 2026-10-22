{{- define "cell-cluster.req" -}}
{{- $v := index . 0 -}}{{- $path := index . 1 -}}
{{- if not $v -}}{{- fail (printf "cell-cluster: thiếu giá trị %s (thông số riêng của môi trường)" $path) -}}{{- end -}}
{{- $v -}}
{{- end -}}

{{/* nhãn node: kubelet tự gắn qua --node-labels (CAPI không đồng bộ nhãn ngoài miền node.cluster.x-k8s.io). */}}
{{- define "cell-cluster.nodeLabels" -}}
{{- $p := index . 0 -}}{{- $zone := index . 1 -}}{{- $root := index . 2 -}}
{{- $l := dict "topology.kubernetes.io/zone" $zone "vt.io/rack" (index $root.Values.racks $zone) "vt.io/cell" $root.Values.cell -}}
{{- range $k, $val := $p.labels }}{{ $_ := set $l $k $val }}{{ end -}}
{{- $out := list -}}
{{- range $k := keys $l | sortAlpha }}{{ $out = append $out (printf "%s=%s" $k (index $l $k)) }}{{ end -}}
{{- join "," $out -}}
{{- end -}}

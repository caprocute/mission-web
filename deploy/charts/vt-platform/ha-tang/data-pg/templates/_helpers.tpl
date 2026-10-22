{{/* Hồ sơ định cỡ hiệu lực và cờ gộp. */}}
{{- define "data-pg.sizing" -}}{{ .Values.global.sizing | default "L" }}{{- end -}}
{{- define "data-pg.consolidated" -}}
{{- if eq (toString .Values.consolidated) "" -}}{{ eq (include "data-pg.sizing" .) "compact" }}{{- else -}}{{ .Values.consolidated }}{{- end -}}
{{- end -}}

{{/*
Danh sách cụm hiệu lực: dict tên cụm → { zone, dbs: {csdl: [schema]}, ext: {csdl: [extension]} }. Ở chế độ gộp, cụm
đích nhận hợp CSDL và schema của các cụm nguồn (CSDL tenant trùng tên thì gộp schema, đúng bố trí td_<mã> TL-03).
*/}}
{{- define "data-pg.clusters" -}}
{{- $v := .Values -}}
{{- $don := dict -}}
{{- range $ten, $c := $v.layout -}}
{{- $dbs := dict -}}
{{- range $v.tenantDatabases -}}{{- if $c.tenantSchemas -}}{{- $_ := set $dbs . (deepCopy $c.tenantSchemas) -}}{{- end -}}{{- end -}}
{{- range $db, $sc := ($c.databases | default dict) -}}{{- $_ := set $dbs $db (deepCopy $sc) -}}{{- end -}}
{{- $cl := index ($v.clients | default dict) $ten | default dict -}}
{{- $_ := set $don $ten (dict "zone" $c.zone "dbs" $dbs "ext" ($c.extensions | default dict) "rw" ($cl.rw | default list) "ro" ($cl.ro | default list)) -}}
{{- end -}}
{{- if eq (include "data-pg.consolidated" .) "true" -}}
{{- $gop := dict -}}
{{- range $dich, $nguon := $v.consolidation -}}
{{- $dbs := dict -}}{{- $ext := dict -}}{{- $rw := list -}}{{- $ro := list -}}
{{- range $nguon -}}
{{- $c := index $don . -}}
{{- $rw = concat $rw $c.rw | uniq -}}{{- $ro = concat $ro $c.ro | uniq -}}
{{- range $db, $sc := $c.dbs -}}{{- $_ := set $dbs $db (concat (index $dbs $db | default list) $sc | uniq) -}}{{- end -}}
{{- range $db, $e := $c.ext -}}{{- $_ := set $ext $db $e -}}{{- end -}}
{{- end -}}
{{- $_ := set $gop $dich (dict "zone" (index $don $dich).zone "dbs" $dbs "ext" $ext "rw" $rw "ro" $ro) -}}
{{- end -}}
{{- toYaml $gop -}}
{{- else -}}
{{- toYaml $don -}}
{{- end -}}
{{- end -}}

{{/* Kích thước của một cụm: (dict "root" . "ten" <cụm>). */}}
{{- define "data-pg.size" -}}
{{- $hs := index .root.Values.hoSo (include "data-pg.sizing" .root) -}}
{{- if not $hs -}}{{- fail (printf "data-pg: không có hồ sơ định cỡ %s" (include "data-pg.sizing" .root)) -}}{{- end -}}
{{- $k := index $hs .ten -}}
{{- if not $k -}}{{- fail (printf "data-pg: hồ sơ %s thiếu kích thước của %s" (include "data-pg.sizing" .root) .ten) -}}{{- end -}}
{{- toYaml $k -}}
{{- end -}}

{{/* Pool PgBouncer mỗi instance của vai trò svc_<schema> trên một cụm. */}}
{{- define "data-pg.pool" -}}
{{- $v := .root.Values -}}
{{- $core := index $v.pgbouncer.poolCore (include "data-pg.sizing" .root) -}}
{{- if hasKey $core .schema -}}{{ index $core .schema }}{{- else -}}{{ index (index $v.pgbouncer.poolKhac (include "data-pg.sizing" .root)) .nguon | default 4 }}{{- end -}}
{{- end -}}

{{/* Cụm nguồn (bố trí chưa gộp) chứa schema, để lấy pool mặc định. */}}
{{- define "data-pg.nguonCuaSchema" -}}
{{- $s := .schema -}}{{- $kq := "pg-core" -}}
{{- range $ten, $c := .root.Values.layout -}}
{{- if has $s ($c.tenantSchemas | default list) -}}{{- $kq = $ten -}}{{- end -}}
{{- range $db, $sc := ($c.databases | default dict) -}}{{- if has $s $sc -}}{{- $kq = $ten -}}{{- end -}}{{- end -}}
{{- end -}}{{ $kq }}
{{- end -}}

{{- define "data-pg.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/component: postgresql
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}

{{/* Namespace của cụm theo vùng: (dict "root" . "zone" <app|secure>). */}}
{{- define "data-pg.ns" -}}{{ index .root.Values.namespaces .zone }}{{- end -}}

{{/* Danh sách schema (đã sắp, không trùng) của một cụm hiệu lực, dạng YAML list. */}}
{{- define "data-pg.schemas" -}}
{{- $l := list -}}{{- range $db, $sc := .dbs -}}{{- $l = concat $l $sc -}}{{- end -}}
{{- toYaml ($l | uniq | sortAlpha) -}}
{{- end -}}

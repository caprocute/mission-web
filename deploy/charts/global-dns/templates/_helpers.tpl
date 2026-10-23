{{- define "gdns.digest" -}}
{{- if not (regexMatch "@sha256:[a-f0-9]{64}$" (index . 0)) }}{{ fail (printf "global-dns: %s phải ghim digest (SEC-55)" (index . 1)) }}{{ end -}}
{{- end -}}
{{- define "gdns.ifurlup" -}}
{{- $vips := list }}
{{- range .vips }}{{ $vips = append $vips (printf "{%s}" (join ", " (regexReplaceAll "(\\S+)" (join " " .) "'$1'" | splitList " "))) }}{{ end -}}
A "ifurlup('{{ .url }}', {{ printf "{%s}" (join ", " $vips) }}, {stringmatch='{{ .stringmatch }}', timeout={{ .timeout }}, interval={{ .interval }}, minimumFailures={{ .minimumFailures }}})"
{{- end -}}

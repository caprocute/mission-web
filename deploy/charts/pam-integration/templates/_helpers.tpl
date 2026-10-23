{{- /* Ảnh theo digest (SEC-55); dừng render khi digest không phải sha256. */ -}}
{{- define "pam.image" -}}
{{- $i := index .root.Values.images .name -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString $i.digest)) -}}
{{- fail (printf "pam-integration: images.%s.digest phải là sha256 (SEC-55)" .name) -}}
{{- end -}}
{{- printf "%s@%s" $i.repository $i.digest -}}
{{- end -}}

{{- define "pam.labels" -}}
app.kubernetes.io/part-of: vt-pam
vt.io/cell: {{ .Values.cell | quote }}
{{- end -}}

{{- define "pam.restrictedContainer" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities: { drop: ["ALL"] }
{{- end -}}

{{- define "pam.restrictedPod" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile: { type: RuntimeDefault }
{{- end -}}

{{- /* Probe rỗng cho container của Job ngắn (vt-pod-hardening bắt buộc readiness và liveness). */ -}}
{{- define "pam.probeRong" -}}
readinessProbe:
  exec: { command: ["true"] }
livenessProbe:
  exec: { command: ["true"] }
{{- end -}}

{{- /* Hợp đồng binding và cấu hình quét RBAC thành JSON cho script; tự thêm binding của chính chart vào choPhep. */ -}}
{{- define "pam.cauHinhRbac" -}}
{{- $v := .Values -}}
{{- $ct := list -}}
{{- range $v.pam.broker.subjects }}{{ $ct = append $ct (printf "%s:%s/%s" .kind (default "" .namespace) .name | regexQuoteMeta) }}{{ end -}}
{{- $ct = append $ct (printf "ServiceAccount:%s/pam-quet-rbac" $v.namespace | regexQuoteMeta) -}}
{{- $choPhep := append $v.quetRbac.choPhep (dict "ten" "^vt-pam-(broker|quet-rbac)$" "chuThe" (printf "^(%s)$" (join "|" $ct))) -}}
{{- $q := merge (dict "choPhep" $choPhep) (omit $v.quetRbac "resources" "schedule" "enabled") -}}
{{- dict "hopDong" $v.hopDong "quetRbac" $q | toPrettyJson -}}
{{- end -}}

{{- /* Kiểm thời hạn tối đa: hợp đồng TL-04 là 4 giờ, không cho values nới ra. */ -}}
{{- define "pam.kiemHopDong" -}}
{{- if ne (toString .Values.hopDong.thoiHanToiDa) "4h" -}}
{{- fail "pam-integration: hopDong.thoiHanToiDa phải là 4h (TL-04 mục 9.3, SEC-29)" -}}
{{- end -}}
{{- if lt (int (index .Values.hopDong.soNguoiDuyet "platform-admin")) 2 -}}
{{- fail "pam-integration: platform-admin cần ít nhất hai người duyệt (TL-04 mục 9.3)" -}}
{{- end -}}
{{- if ne (toString .Values.vaiTroCsdl.thoiHanPhaKinh) "2h" -}}
{{- fail "pam-integration: vaiTroCsdl.thoiHanPhaKinh phải là 2h (TL-04 SEC-58)" -}}
{{- end -}}
{{- if ne (join "," (sortAlpha .Values.hopDong.vaiTroPam)) "debug-app,operate-data,platform-admin" -}}
{{- fail "pam-integration: hopDong.vaiTroPam phải đúng ba vai trò debug-app, operate-data, platform-admin (INF-19)" -}}
{{- end -}}
{{- end -}}

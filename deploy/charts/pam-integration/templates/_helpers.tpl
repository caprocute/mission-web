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
{{- dict "hopDong" (merge (dict "taiKhoanMay" (include "pam.taiKhoanMay" . | fromJson)) $v.hopDong) "quetRbac" $q | toPrettyJson -}}
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

{{- /* ServiceAccount của mục tiêu kubectl Warpgate (một cho mỗi vai trò PAM): {"namespace": "...", "tienTo": "warpgate-"};
       namespace rỗng khi tắt Warpgate (binding PAM khi đó chỉ được gán cho người dùng OIDC). */ -}}
{{- define "pam.taiKhoanMay" -}}
{{- dict "namespace" (ternary .Values.warpgate.namespace "" .Values.warpgate.enabled) "tienTo" "warpgate-" | toJson -}}
{{- end -}}

{{- define "pam.tenTaiKhoanMay" -}}
{{- $l := list -}}
{{- range .Values.hopDong.vaiTroPam }}{{ $l = append $l (printf "warpgate-%s" .) }}{{ end -}}
{{- toJson $l -}}
{{- end -}}

{{- /* Toàn bộ mục tiêu Warpgate: warpgate.mucTieu cộng mục tiêu kubectl theo vai trò PAM và psql theo cụm. */ -}}
{{- define "pam.mucTieuWarpgate" -}}
{{- $w := .Values.warpgate -}}
{{- $kho := $w.khoBiMat.name -}}
{{- $l := $w.mucTieu -}}
{{- range .Values.hopDong.vaiTroPam -}}
{{- $l = append $l (dict "name" (printf "%s-%s" $w.kubectl.tenMucTieu .) "description" (printf "API server, vai trò %s (cấp qua PAM, có thời hạn)" .)
     "vaiTro" (list (printf "pam-kubectl-%s" .))
     "options" (dict "kind" "Kubernetes" "cluster_url" $w.kubectl.clusterUrl "tls" (dict "mode" "Required" "verify" true)
                     "auth" (dict "kind" "Token" "token" (printf "secret://%s/kv/warpgate/%s-%s#token" $kho $w.kubectl.tenMucTieu .)))) -}}
{{- end -}}
{{- range $w.psql -}}
{{- $l = append $l (dict "name" (printf "psql-%s" .cum) "description" (printf "%s (dba_ops, người dùng động 4 giờ, không đọc bảng nghiệp vụ)" .cum)
     "vaiTro" (list "pam-psql")
     "options" (dict "kind" "Postgres" "host" (printf "%s-rw.%s.svc" .cum .namespace) "port" 5432
                     "username" "chua-cap" "default_database_name" .csdl "protocol_version" "3.0"
                     "tls" (dict "mode" "Required" "verify" true)
                     "auth" (dict "kind" "Password" "password" (printf "secret://%s/kv/warpgate/psql-%s#password" $kho .cum)))) -}}
{{- end -}}
{{- toJson $l -}}
{{- end -}}

{{- /* Cấu hình của warpgate_lam_moi.py: token kubectl và người dùng psql cần làm mới. */ -}}
{{- define "pam.lamMoiWarpgate" -}}
{{- $v := .Values -}}
{{- $w := $v.warpgate -}}
{{- $k := list -}}
{{- range $v.hopDong.vaiTroPam -}}
{{- $k = append $k (dict "serviceAccount" (printf "warpgate-%s" .) "kv" (printf "warpgate/%s-%s" $w.kubectl.tenMucTieu .)) -}}
{{- end -}}
{{- $p := list -}}
{{- range $w.psql -}}
{{- $p = append $p (dict "cum" .cum "creds" (printf "database/creds/%s-dba-ops" .cum) "kv" (printf "warpgate/psql-%s" .cum) "mucTieu" (printf "psql-%s" .cum)) -}}
{{- end -}}
{{- dict "kho" $w.khoBiMat.name "namespace" $w.namespace "tokenTtlGiay" (int $w.kubectl.tokenTtlGiay) "nguongGiay" (int $w.lamMoi.nguongGiay)
     "openbao" (dict "address" $w.khoBiMat.address "authMount" $w.khoBiMat.authMount "role" "warpgate-lam-moi" "kvMount" "kv")
     "warpgate" (printf "https://warpgate.%s.svc:%d" $w.namespace (int $w.cong.http))
     "kubectl" $k "psql" $p | toJson -}}
{{- end -}}

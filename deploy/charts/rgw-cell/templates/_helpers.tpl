{{- define "rgw.labels" -}}
app.kubernetes.io/part-of: vt-platform
app.kubernetes.io/name: rgw-cell
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- with .Values.global.cell }}
vt.io/cell: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "rgw.anh" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" (toString .digest)) }}{{ fail "rgw-cell: ảnh phải ghim digest sha256 (SEC-55)" }}{{ end -}}
{{ .repository }}:{{ .tag }}@{{ .digest }}
{{- end -}}
{{- define "rgw.ma" -}}{{ required "rgw-cell: cần cell (mã cell)" .Values.cell | lower }}{{- end -}}
{{- define "rgw.endpoint" -}}{{ .Values.endpoint | default (printf "https://s3.cell-%s.hl.vt.local" (include "rgw.ma" .)) }}{{- end -}}
{{- define "rgw.region" -}}{{ .Values.region | default (printf "cell-%s-zg" (include "rgw.ma" .)) }}{{- end -}}
{{- define "rgw.quanTri" -}}{{ .Values.quanTri.uid | default (printf "kho-quan-tri-cell-%s" (include "rgw.ma" .)) }}{{- end -}}
{{/* Tên bucket đầy đủ: <ma>-cell-<mã cell> (TL-04 mục 8.4: ev-cell-hcm). Đầu vào: dict root, b. */}}
{{- define "rgw.tenBucket" -}}
{{- $b := index .root.Values.buckets .b -}}
{{- if not $b }}{{ fail (printf "rgw-cell: bucket %q không có trong buckets" .b) }}{{ end -}}
{{ $b.ma }}-cell-{{ include "rgw.ma" .root }}
{{- end -}}
{{/* Chính sách bucket: mỗi người dùng có quyền trên bucket nhận thao tác theo nhóm quyền, giới hạn theo tiền tố tenant khi
bucket theoTenant. Không cấp DeleteObject (trừ xoa-tam ở bucket không khoá), đổi Object Lock, vòng đời hay chính sách:
những thao tác này chỉ chủ bucket (tài khoản quản trị kho) làm được. Đầu vào: dict root, b. */}}
{{- define "rgw.chinhSach" -}}
{{- $root := .root -}}
{{- $b := index $root.Values.buckets .b -}}
{{- $ten := include "rgw.tenBucket" . -}}
{{- $cau := list -}}
{{- range $uid, $u := $root.Values.nguoiDung -}}
{{- with index $u.quyen $.b -}}
{{- $q := . -}}
{{- $tienTo := ternary (printf "t/%s/" ($u.tenant | default "")) "t/" (not (empty $u.tenant)) -}}
{{- if not $b.theoTenant }}{{ $tienTo = "" }}{{ end -}}
{{- $nguoi := dict "AWS" (list (printf "arn:aws:iam:::user/%s" $uid)) -}}
{{- $doiTuong := list -}}
{{- range $q -}}
{{- if eq . "doc" }}{{ $doiTuong = concat $doiTuong (list "s3:GetObject" "s3:GetObjectVersion" "s3:GetObjectRetention" "s3:GetObjectLegalHold") }}
{{- else if eq . "ghi" }}{{ $doiTuong = concat $doiTuong (list "s3:PutObject" "s3:AbortMultipartUpload" "s3:ListMultipartUploadParts") }}
{{- if $b.khoa }}{{ $doiTuong = concat $doiTuong (list "s3:PutObjectRetention" "s3:PutObjectLegalHold") }}{{ end }}
{{- else if eq . "xoa-tam" }}
{{- if $b.khoa }}{{ fail (printf "rgw-cell: quyền xoa-tam không dùng được trên bucket có Object Lock %s" $.b) }}{{ end }}
{{- $doiTuong = concat $doiTuong (list "s3:DeleteObject") }}
{{- else if ne . "liet-ke" }}{{ fail (printf "rgw-cell: quyền %q không hợp lệ (doc, ghi, liet-ke, xoa-tam)" .) }}
{{- end -}}
{{- end -}}
{{- if $doiTuong -}}
{{- $cau = append $cau (dict "Sid" (printf "%s-doi-tuong" $uid | replace "-" "") "Effect" "Allow" "Principal" $nguoi "Action" $doiTuong "Resource" (list (printf "arn:aws:s3:::%s/%s*" $ten $tienTo))) -}}
{{- end -}}
{{- if has "liet-ke" $q -}}
{{- $c := dict "Sid" (printf "%s-liet-ke" $uid | replace "-" "") "Effect" "Allow" "Principal" $nguoi "Action" (list "s3:ListBucket" "s3:ListBucketVersions" "s3:ListBucketMultipartUploads") "Resource" (list (printf "arn:aws:s3:::%s" $ten)) -}}
{{- if $tienTo }}{{ $_ := set $c "Condition" (dict "StringLike" (dict "s3:prefix" (list (printf "%s*" $tienTo)))) }}{{ end -}}
{{- $cau = append $cau $c -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if $cau }}{{ dict "Version" "2012-10-17" "Statement" $cau | toJson }}{{ end -}}
{{- end -}}
{{/* Vòng đời của bucket theo vongDoi. Đầu vào: dict root, b. */}}
{{- define "rgw.vongDoi" -}}
{{- $b := index .root.Values.buckets .b -}}
{{- $v := $b.vongDoi | default dict -}}
{{- $luat := list -}}
{{- with $v.huyMultipartSauNgay }}{{ $luat = append $luat (dict "ID" "huy-multipart-do-dang" "Status" "Enabled" "Filter" (dict "Prefix" "") "AbortIncompleteMultipartUpload" (dict "DaysAfterInitiation" .)) }}{{ end -}}
{{- with $v.hetHanSauNgay }}
{{- if $b.khoa }}{{ fail (printf "rgw-cell: bucket %s có Object Lock không đặt hetHanSauNgay" $.b) }}{{ end -}}
{{- $luat = append $luat (dict "ID" "het-han" "Status" "Enabled" "Filter" (dict "Prefix" "") "Expiration" (dict "Days" .)) }}{{ end -}}
{{- with $v.chuyenHddSauNgay }}{{ $luat = append $luat (dict "ID" "chuyen-hdd" "Status" "Enabled" "Filter" (dict "Prefix" "") "Transitions" (list (dict "Days" . "StorageClass" $.root.Values.lopLuuTruHdd))) }}{{ end -}}
{{- if $luat }}{{ dict "Rules" $luat | toJson }}{{ end -}}
{{- end -}}

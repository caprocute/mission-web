#!/usr/bin/env bash
# INF-18 · Kéo thử ảnh theo digest từ harbor-sn hằng ngày (TL-04 bảng lịch sao lưu, dòng Harbor): lấy digest của các ảnh
# đang chạy trong cụm thuộc các project được nhân bản (DU_AN, mặc định okr platform k8s), chọn tối đa TOI_DA digest theo
# thứ tự băm của ngày, kéo manifest từ harbor-sn theo đúng digest và kiểm SHA-256 của nội dung nhận được bằng digest.
# Ảnh thiếu ở harbor-sn nghĩa là nhân bản theo sự kiện đẩy ảnh đã hỏng: khi chuyển vùng, cụm không kéo được ảnh đó.
# Biến: REGISTRY_GOC (harbor.vt.local), HARBOR_SN (https://harbor-sn.vt.local), HARBOR_USER, HARBOR_PASSWORD_FILE,
# HARBOR_CA, DU_AN, TOI_DA, CELL, PUSHGATEWAY.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${REGISTRY_GOC:?cần REGISTRY_GOC}" "${HARBOR_SN:?cần HARBOR_SN}"
TOI_DA=${TOI_DA:-50} ngay=$(ngay_hien_tai)
read -r -a du_an <<< "${DU_AN:-okr platform k8s}"
loc=$(printf '%s|' "${du_an[@]}"); loc=${loc%|}

# imageID của container đang chạy có dạng [docker-pullable://]<registry>/<repo>@sha256:<băm>.
mapfile -t ds < <(kubectl get pods -A -o json \
  | jq -r '.items[].status | ((.containerStatuses // []) + (.initContainerStatuses // []))[] | .imageID' \
  | sed 's#^docker-pullable://##' | grep -E "^${REGISTRY_GOC//./\\.}/($loc)/[^@]+@sha256:[a-f0-9]{64}\$" | sort -u \
  | while read -r a; do printf '%s %s\n' "$(printf '%s%s' "$a" "$ngay" | sha256sum | cut -c1-16)" "$a"; done \
  | sort | head -n "$TOI_DA" | cut -d' ' -f2)

nhan="Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json"
da=0 thieu=0
for a in "${ds[@]}"; do
  r=${a#"$REGISTRY_GOC"/}; repo=${r%@*}; dg=${r#*@}
  da=$((da + 1))
  if curl -sS --fail --max-time 60 ${HARBOR_CA:+--cacert "$HARBOR_CA"} -u "$HARBOR_USER:$(cat "$HARBOR_PASSWORD_FILE")" \
       -H "$nhan" -o /tmp/manifest "$HARBOR_SN/v2/$repo/manifests/$dg" \
     && [ "sha256:$(sha256sum /tmp/manifest | cut -d' ' -f1)" = "$dg" ]; then
    :
  else
    echo "THIẾU: $repo@$dg không kéo được từ harbor-sn hoặc sai digest" >&2; thieu=$((thieu + 1))
  fi
  rm -f /tmp/manifest
done
echo "Đã kéo thử $da ảnh từ harbor-sn, $thieu ảnh hỏng"
{
  echo "# TYPE backup_harbor_pull_check_checked gauge"
  echo "backup_harbor_pull_check_checked $da"
  echo "backup_harbor_pull_check_failed $thieu"
  [ "$thieu" -eq 0 ] && [ "$da" -gt 0 ] && echo "backup_harbor_pull_check_last_success_timestamp_seconds $(bay_gio)"
  true
} | day_chi_so kiem-keo-harbor
[ "$thieu" -eq 0 ] && [ "$da" -gt 0 ]

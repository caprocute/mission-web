#!/usr/bin/env bash
# INF-18 · Khôi phục thử OpenSearch hằng tuần (TL-04 bảng lịch sao lưu): lấy snapshot SUCCESS mới nhất của repo bk-sn,
# khôi phục một nhóm chỉ mục luân phiên theo tuần (MAU, mặc định "org-* okr-* mission-* evidence-*"; một nhóm mỗi tuần để
# không nhân đôi dung lượng cụm) vào khoi-phuc-thu-<tên> không bản sao, so số tài liệu với chỉ mục gốc (gốc có ít nhất
# NGUONG_TAI_LIEU tài liệu phải còn ít nhất TI_LE × số đó), rồi luôn xoá chỉ mục tạm.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
REPO=${OS_REPO:-bk-sn} NGUONG=${NGUONG_TAI_LIEU:-1000} TI_LE=${TI_LE:-0.5} TIEN_TO=khoi-phuc-thu-
read -r -a nhom <<< "${MAU:-org-* okr-* mission-* evidence-*}"
mau=${nhom[$(( ($(so_ngay "$(ngay_hien_tai)") / 7 + ${LECH_TUAN:-0}) % ${#nhom[@]} ))]}

ket_qua=0 tao=()
xoa_tam() { [ ${#tao[@]} -eq 0 ] || os_api DELETE "/$(IFS=,; echo "${tao[*]}")?ignore_unavailable=true" > /dev/null || true; }
trap xoa_tam EXIT
snap=$(os_api GET "/_snapshot/$REPO/_all" | jq -r '[.snapshots[] | select(.state == "SUCCESS")] | sort_by(.start_time_in_millis) | last | .snapshot // empty')
if [ -z "$snap" ]; then
  echo "LỖI: repo $REPO chưa có snapshot SUCCESS" >&2
else
  re="^$(printf '%s' "$mau" | sed 's/[.]/\\./g; s/[*]/.*/g')\$"
  mapfile -t chi_muc < <(os_api GET "/_snapshot/$REPO/$snap" | jq -r --arg re "$re" '.snapshots[0].indices[] | select(test($re))' | sort)
  echo "== Khôi phục thử $snap, nhóm $mau: ${#chi_muc[@]} chỉ mục"
  if [ ${#chi_muc[@]} -eq 0 ]; then
    echo "LỖI: snapshot $snap không có chỉ mục nào thuộc nhóm $mau" >&2
  else
    for i in "${chi_muc[@]}"; do tao+=("$TIEN_TO$i"); done
    xoa_tam
    than=$(jq -nc --arg ds "$(IFS=,; echo "${chi_muc[*]}")" --arg tt "$TIEN_TO" '{indices: $ds, rename_pattern: "(.+)",
      rename_replacement: ($tt + "$1"), include_global_state: false, include_aliases: false,
      index_settings: {"index.number_of_replicas": 0}}')
    kq=$(os_api POST "/_snapshot/$REPO/$snap/_restore?wait_for_completion=true" "$than" || echo '{}')
    if [ "$(jq -r '.snapshot.shards.failed // "x"' <<<"$kq")" != 0 ]; then
      echo "LỖI: khôi phục $snap không trọn: $kq" >&2
    else
      sai=0
      for i in "${chi_muc[@]}"; do
        g=$(os_api GET "/_cat/count/$i?format=json" | jq -r '.[0].count')
        t=$(os_api GET "/_cat/count/$TIEN_TO$i?format=json" | jq -r '.[0].count')
        if [ "$g" -ge "$NGUONG" ] && awk -v t="$t" -v g="$g" -v r="$TI_LE" 'BEGIN { exit !(t < r * g) }'; then
          echo "LỖI: $i có $t tài liệu sau khôi phục, gốc $g" >&2; sai=1
        fi
      done
      [ "$sai" = 0 ] && { ket_qua=1; echo "Khôi phục thử $snap ($mau) đạt"; }
    fi
  fi
fi
{
  echo "# TYPE backup_opensearch_restore_check_success gauge"
  echo "backup_opensearch_restore_check_success $ket_qua"
  [ "$ket_qua" = 1 ] && echo "backup_opensearch_restore_check_last_success_timestamp_seconds $(bay_gio)"
  true
} | day_chi_so khoi-phuc-thu-opensearch
[ "$ket_qua" = 1 ]

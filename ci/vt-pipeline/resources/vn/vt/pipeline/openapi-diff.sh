#!/usr/bin/env bash
# Kiểm tương thích hợp đồng OpenAPI của nhánh so với mốc (ADR-32, TL-01 ch.18 "Đường ống CI/CD").
# Dùng trong stage "Hợp đồng API" của vtJavaPipeline (INF-05).
#
#   openapi-diff.sh <mốc git> <thư mục hợp đồng> [thư mục kết quả]
#
# Hợp đồng của repo chia theo module (contracts/openapi/modules/*.yaml, $ref sang common/), nên so từng
# tệp module của mốc với tệp cùng tên ở nhánh. Chặn (mã thoát 1) khi:
#   - một tệp module có ở mốc bị xoá hoặc đổi tên ở nhánh;
#   - openapi-diff báo thay đổi phá vỡ tương thích (--fail-on-incompatible), ví dụ xoá thao tác,
#     xoá trường phản hồi, thêm tham số bắt buộc.
# Thêm giá trị enum ở phản hồi được coi là tương thích theo quy ước API-first của dự án (client phải
# chịu được giá trị lạ, wiki 14), nên tắt luật incompatible.response.enum.increased của openapi-diff.
# Lệnh openapi-diff lấy từ biến OPENAPI_DIFF (mặc định `openapi-diff`), nhận hai đường dẫn tương đối
# so với thư mục làm việc hiện tại.
set -euo pipefail

MOC="${1:?Thiếu mốc git để so sánh, ví dụ origin/main}"
THU_MUC="${2:-contracts/openapi}"
KET_QUA="${3:-openapi-diff}"
OPENAPI_DIFF="${OPENAPI_DIFF:-openapi-diff}"

if ! git rev-parse --verify --quiet "${MOC}^{commit}" >/dev/null; then
  echo "openapi-diff: không có mốc ${MOC} trong bản sao. Cấu hình refspec để lấy nhánh đích (xem ci/vt-pipeline/README.md)." >&2
  exit 2
fi

if git diff --quiet "${MOC}" HEAD -- "${THU_MUC}"; then
  echo "openapi-diff: ${THU_MUC} không đổi so với ${MOC}, bỏ qua."
  exit 0
fi

if ! git cat-file -e "${MOC}:${THU_MUC}" 2>/dev/null; then
  echo "openapi-diff: ${MOC} chưa có ${THU_MUC}, không có gì để so."
  exit 0
fi

rm -rf "${KET_QUA}"
mkdir -p "${KET_QUA}/moc"
# Lấy cây hợp đồng của mốc (giữ đường dẫn tương đối để $ref ../common/... còn đúng)
git archive "${MOC}" "${THU_MUC}" | tar -x -C "${KET_QUA}/moc"

LOI=0
TONG=0
shopt -s nullglob
for tep_moc in "${KET_QUA}/moc/${THU_MUC}"/modules/*.yaml "${KET_QUA}/moc/${THU_MUC}"/*.yaml; do
  ten="${tep_moc#"${KET_QUA}/moc/${THU_MUC}/"}"
  tep_nhanh="${THU_MUC}/${ten}"
  TONG=$((TONG + 1))
  if [ ! -f "${tep_nhanh}" ]; then
    echo "openapi-diff: PHÁ VỠ TƯƠNG THÍCH: tệp hợp đồng ${tep_nhanh} có ở ${MOC} nhưng đã bị xoá." >&2
    LOI=1
    continue
  fi
  bao_cao="${KET_QUA}/${ten//\//_}.md"
  set +e
  ${OPENAPI_DIFF} "${tep_moc}" "${tep_nhanh}" --fail-on-incompatible \
    --config-prop incompatible.response.enum.increased:false --markdown "${bao_cao}" > "${bao_cao%.md}.log" 2>&1
  ma=$?
  set -e
  if [ "${ma}" -ne 0 ]; then
    echo "openapi-diff: PHÁ VỠ TƯƠNG THÍCH ở ${tep_nhanh} (mã ${ma}):" >&2
    if [ -s "${bao_cao}" ]; then cat "${bao_cao}" >&2; else cat "${bao_cao%.md}.log" >&2; fi
    LOI=1
  else
    echo "openapi-diff: ${tep_nhanh} tương thích với ${MOC}."
  fi
done

echo "openapi-diff: đã so ${TONG} tệp hợp đồng."
exit "${LOI}"

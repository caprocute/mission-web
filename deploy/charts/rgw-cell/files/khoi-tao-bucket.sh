#!/usr/bin/env bash
# Khai báo bucket của cell trên Ceph RGW qua API S3 (INF-12, TL-03 "Tổ chức bucket", TL-04 mục 8.4). Chạy trong Job
# của chart rgw-cell bằng tài khoản quản trị kho; chạy lại nhiều lần cho cùng kết quả. Mỗi dòng của /cfg/danh-sach là
# "<khoá values> <tên bucket> <versioning true|false> <có Object Lock true|false>"; /cfg/<khoá>.khoa.json,
# .vong-doi.json, .chinh-sach.json là cấu hình đã dựng sẵn từ values (thiếu tệp nghĩa là không đặt).
# Object Lock chỉ bật được lúc tạo bucket: bucket đã có mà thiếu Object Lock thì báo lỗi, không tạo lại.
set -euo pipefail
CFG=${CFG:-/cfg}
s3() { aws --endpoint-url "$S3_ENDPOINT" --region "$S3_REGION" ${AWS_CA_BUNDLE:+--ca-bundle "$AWS_CA_BUNDLE"} s3api "$@"; }
loi=0
while read -r khoa ten versioning co_khoa; do
  [ -n "$khoa" ] || continue
  if s3 head-bucket --bucket "$ten" >/dev/null 2>&1; then
    if [ "$co_khoa" = true ] && ! s3 get-object-lock-configuration --bucket "$ten" >/dev/null 2>&1; then
      echo "LỖI $ten: bucket đã có nhưng không bật Object Lock; phải tạo bucket mới và chuyển dữ liệu" >&2
      loi=$((loi + 1)); continue
    fi
    echo "$ten: đã có"
  else
    if [ "$co_khoa" = true ]; then s3 create-bucket --bucket "$ten" --object-lock-enabled-for-bucket >/dev/null
    else s3 create-bucket --bucket "$ten" >/dev/null; fi
    echo "$ten: đã tạo"
  fi
  if [ "$versioning" = true ]; then
    s3 put-bucket-versioning --bucket "$ten" --versioning-configuration Status=Enabled
  fi
  if [ -f "$CFG/$khoa.khoa.json" ]; then
    s3 put-object-lock-configuration --bucket "$ten" --object-lock-configuration "file://$CFG/$khoa.khoa.json"
  fi
  if [ -f "$CFG/$khoa.vong-doi.json" ]; then
    s3 put-bucket-lifecycle-configuration --bucket "$ten" --lifecycle-configuration "file://$CFG/$khoa.vong-doi.json"
  else
    s3 delete-bucket-lifecycle --bucket "$ten" >/dev/null 2>&1 || true
  fi
  if [ -f "$CFG/$khoa.chinh-sach.json" ]; then
    s3 put-bucket-policy --bucket "$ten" --policy "file://$CFG/$khoa.chinh-sach.json"
  else
    s3 delete-bucket-policy --bucket "$ten" >/dev/null 2>&1 || true
  fi
  echo "$ten: versioning=$versioning, Object Lock=$co_khoa, vòng đời và chính sách đã áp"
done < "$CFG/danh-sach"
echo "xong, lỗi: $loi"
[ "$loi" -eq 0 ]

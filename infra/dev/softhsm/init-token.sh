#!/bin/sh
# DV-10-03: khởi tạo token SoftHSM2 "mission-dev" một lần; PIN lấy từ biến môi trường (infra/dev/.env),
# không in ra nhật ký. Khoá ký tổ chức và khoá niêm phong do esign-service tự tạo khi
# ESIGN_HSM_PROVISION_DEV_KEYS=true (CKA_EXTRACTABLE=false), không tạo ở đây.
set -eu
: "${ESIGN_HSM_PIN:?Thiếu ESIGN_HSM_PIN}"
: "${ESIGN_HSM_SO_PIN:?Thiếu ESIGN_HSM_SO_PIN}"
LABEL="${ESIGN_HSM_TOKEN_LABEL:-mission-dev}"
mkdir -p /var/lib/softhsm/tokens
if softhsm2-util --show-slots | grep -q "Label:[[:space:]]*${LABEL}\$"; then
  echo "Token ${LABEL} đã có, bỏ qua."
else
  softhsm2-util --init-token --free --label "${LABEL}" \
    --so-pin "${ESIGN_HSM_SO_PIN}" --pin "${ESIGN_HSM_PIN}" | sed 's/[0-9]\{6,\}/<slot>/'
  echo "Đã khởi tạo token ${LABEL}."
fi
softhsm2-util --show-slots | grep -E "Slot [0-9]+|Label:" || true

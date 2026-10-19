#!/bin/sh
# Tạo bucket dev. Bucket minh chứng bật Object Lock (ADR-13), chế độ GOVERNANCE ở dev
# để còn xoá được dữ liệu thử; production dùng COMPLIANCE trên Ceph RGW.
set -e
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing --with-lock local/mission-evidence
mc retention set --default GOVERNANCE 30d local/mission-evidence || true
mc mb --ignore-existing local/mission-docgen
mc mb --ignore-existing local/mission-ui-packs
mc anonymous set download local/mission-ui-packs
echo "minio-init ok"

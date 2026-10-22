#!/usr/bin/env bash
# DV-15-05 · Khởi tạo Superset nhúng: CSDL siêu dữ liệu, tài khoản quản trị kỹ thuật, quyền mặc định, rồi khoi_tao.py.
set -euo pipefail
superset db upgrade
superset fab create-admin --username "${SUPERSET_ADMIN_USER:?}" --firstname Quan --lastname Tri \
  --email "${SUPERSET_ADMIN_USER}@analytics.invalid" --password "${SUPERSET_ADMIN_PASSWORD:?}" || true
superset init
python /app/mission/khoi_tao.py

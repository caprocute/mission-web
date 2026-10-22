# NEN-020 · TL-03 "Chiến lược phân vùng bảng lớn", "Tài khoản kết nối CSDL".
# Ảnh PostgreSQL 17 kèm tiện ích mở rộng pg_partman và pgvector (schema ai của SVC-14, DV-14-01) cho dev.
FROM postgres:17

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-partman postgresql-17-pgvector \
    && rm -rf /var/lib/apt/lists/*

COPY migrate-roles.sh /usr/local/bin/migrate-roles.sh
RUN chmod +x /usr/local/bin/migrate-roles.sh

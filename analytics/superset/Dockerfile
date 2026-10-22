# DV-15-05 · Ảnh Superset 4.x của SVC-15 (Apache-2.0): ảnh gốc chính thức cộng trình điều khiển Postgres wire để nối
# Cube SQL API, cấu hình và tệp khởi tạo của nền tảng. Không cài trình điều khiển ClickHouse.
# Bản dựng không có mạng ra ngoài: đặt bánh xe psycopg2-binary vào wheels/ (pip dùng bản có sẵn trước).
ARG SUPERSET_IMAGE=apache/superset:4.1.2
FROM ${SUPERSET_IMAGE}
USER root
COPY wheels/ /tmp/wheels/
RUN pip install --no-cache-dir --find-links /tmp/wheels psycopg2-binary==2.9.10 && rm -rf /tmp/wheels
COPY superset_config.py /app/pythonpath/superset_config.py
COPY khoi_tao.py khoi_tao.sh /app/mission/
COPY dashboards /app/mission/dashboards
USER superset
CMD ["bash", "-c", "/app/mission/khoi_tao.sh && /usr/bin/run-server.sh"]

# SVC-15 job phát hiện bất thường (DV-15-07) và dịch vụ dự báo trễ hạn (DV-15-10, chạy với lệnh `python -m app.delay_risk.main`). Chạy từ gốc repo: docker build -f services-py/anomaly-job/Dockerfile -t anomaly-job .
FROM python:3.12-slim-bookworm AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1
WORKDIR /src
COPY services-py/anomaly-job/pyproject.toml services-py/anomaly-job/uv.lock ./
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export PIP_CERT=/run/secrets/ca_bundle; fi; \
    pip install --prefix=/install "uv==0.11.*" && PYTHONPATH=$(echo /install/lib/python3.12/site-packages) \
    /install/bin/uv export --frozen --no-dev --no-emit-project --no-hashes -o requirements.txt && \
    pip install --prefix=/runtime -r requirements.txt

FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/app \
    EVT20_SCHEMA_PATH=/app/contracts/DauHieuBatThuong.avsc EVT05_SCHEMA_PATH=/app/contracts/TienDoDaCapNhat.avsc
COPY --from=build /runtime /usr/local
WORKDIR /app
COPY services-py/anomaly-job/app /app/app
COPY contracts/events/avro/analytics/DauHieuBatThuong.avsc contracts/events/avro/task/TienDoDaCapNhat.avsc /app/contracts/
USER 65532:65532
ENTRYPOINT ["python", "-m", "app.main"]

# SVC-14 ai-gateway. Chạy từ thư mục services-py: docker build -f ai-gateway/Dockerfile -t ai-gateway .
FROM python:3.12-slim-bookworm AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1
WORKDIR /src
COPY ai-gateway/pyproject.toml ai-gateway/uv.lock ./
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export PIP_CERT=/run/secrets/ca_bundle; fi; \
    pip install --prefix=/install "uv==0.11.*" && PYTHONPATH=$(echo /install/lib/python3.12/site-packages) \
    /install/bin/uv export --frozen --no-dev --no-emit-project --no-hashes -o requirements.txt && \
    pip install --prefix=/runtime -r requirements.txt

FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/app:/app/vtplatform
COPY --from=build /runtime /usr/local
WORKDIR /app
COPY vtplatform/vtplatform /app/vtplatform/vtplatform
COPY ai-gateway/app /app/app
COPY ai-gateway/db /app/db
USER 65532:65532
EXPOSE 8080
CMD ["python", "-c", "from app.main import run; run()"]

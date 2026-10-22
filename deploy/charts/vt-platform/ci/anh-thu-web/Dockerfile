# Ảnh thử SVC-17 cho kiem-thu-kind.sh (INF-02): cùng nginx.conf và 40-runtime-config.sh của deploy/web,
# nhưng thay bản build Angular bằng một trang tĩnh để không phải build frontend khi thử chart.
# Build từ gốc repo: docker build -f deploy/charts/vt-platform/ci/anh-thu-web/Dockerfile .
ARG BASE=nginxinc/nginx-unprivileged:1.27-alpine
FROM ${BASE}
COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 deploy/web/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
USER root
RUN mkdir -p /etc/mission-web && printf 'task\tFE_TASK_URL\tfe-task\n' > /etc/mission-web/remotes.tsv \
 && printf '<!doctype html><title>Mission</title>\n' > /usr/share/nginx/html/index.html \
 && chown -R 101:101 /usr/share/nginx/html
USER 101
EXPOSE 8080

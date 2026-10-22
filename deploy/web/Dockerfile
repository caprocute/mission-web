# Build fe-shell + mọi remote một lần, phục vụ tĩnh bằng nginx không root (SVC-17).
# config.json và federation.manifest.json sinh lúc khởi động từ biến môi trường để một image
# dùng cho mọi cell (K6, C9). Danh mục remote: frontend/tools/remotes/remotes.json (WEB-015).
# Build context: thư mục gốc repo.  docker build -f deploy/web/Dockerfile .
FROM node:22-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
# Ghép shell ở gốc, mỗi remote ở remotes/<project>; remotes.tsv liệt kê khoá, biến URL, project
RUN npm run build:all && node tools/remotes/workspace.mjs assemble /out/html /out/remotes.tsv
# WEB-013: gói nhúng <vt-okr-app> cho cổng văn phòng số (build:all đã build), phục vụ dưới /embed/
RUN cp -r dist/fe-embed/browser /out/html/embed

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=build /out/html /usr/share/nginx/html
COPY --from=build /out/remotes.tsv /etc/mission-web/remotes.tsv
COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/web/security-headers.conf /etc/nginx/mission/security-headers.conf
COPY --chmod=755 deploy/web/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
USER root
RUN chown -R 101:101 /usr/share/nginx/html
USER 101
EXPOSE 8080

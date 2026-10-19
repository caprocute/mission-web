# Build fe-shell + fe-task một lần, phục vụ tĩnh bằng nginx không root (SVC-17).
# config.json sinh lúc khởi động từ biến môi trường để một image dùng cho mọi cell (K6, C9).
# Build context: thư mục gốc repo.  docker build -f deploy/web/Dockerfile .
FROM node:22-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npx ng build fe-task && npx ng build fe-shell

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=build /src/dist/fe-shell/browser /usr/share/nginx/html
COPY --from=build /src/dist/fe-task/browser /usr/share/nginx/html/remotes/fe-task
COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 deploy/web/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
USER root
RUN chown -R 101:101 /usr/share/nginx/html
USER 101
EXPOSE 8080

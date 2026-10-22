# Bộ chạy cel-js (CORE-05-07). Ngữ cảnh build là gốc repo để lấy hàm Mission của @vt/form-engine:
#   docker build -f backend/services/cel-js-runner/Dockerfile -t mission/cel-js-runner .
FROM node:22-alpine AS build
WORKDIR /src
COPY frontend/projects/form-engine/src/lib/mission-cel.ts frontend/projects/form-engine/src/lib/
COPY frontend/projects/form-engine/src/lib/cel-rule-evaluator.ts frontend/projects/form-engine/src/lib/
COPY frontend/projects/form-engine/src/lib/form-rule.ts frontend/projects/form-engine/src/lib/
COPY backend/services/cel-js-runner/package.json backend/services/cel-js-runner/package-lock.json backend/services/cel-js-runner/
WORKDIR /src/backend/services/cel-js-runner
RUN npm ci
COPY backend/services/cel-js-runner/src src
COPY backend/services/cel-js-runner/test test
RUN npm test && npm prune --omit=dev

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080
COPY --from=build /src/backend/services/cel-js-runner/package.json ./
COPY --from=build /src/backend/services/cel-js-runner/node_modules node_modules
COPY --from=build /src/backend/services/cel-js-runner/dist dist
USER node
EXPOSE 8080
HEALTHCHECK CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
CMD ["node", "dist/server.mjs"]

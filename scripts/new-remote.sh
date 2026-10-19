#!/usr/bin/env bash
# Tạo remote Angular + Native Federation mới theo mẫu fe-task (WEB-015).
# Cách dùng (từ gốc repo):
#   ./scripts/new-remote.sh <project> <cổng> <tuyến> "<tiêu đề>" "<QT>" "<SCR>"
#   ./scripts/new-remote.sh fe-okr 4202 okr "Mục tiêu và OKR" "QT-02, QT-03" "SCR-0201, SCR-0202"
# Script sinh projects/<project> (federation.config.js, routes, widgets, styles, test mẫu),
# thêm project vào angular.json và tsconfig.json, đăng ký cổng dev và khoá manifest trong
# frontend/tools/remotes/remotes.json và fe-shell/public/federation.manifest.json.
# build:all, test:all, perf:prepare và deploy/web/Dockerfile đọc remotes.json nên tự có remote mới.
# Việc làm tay sau đó: đổi tuyến trong fe-shell/src/app/app.routes.ts sang remoteRoutes('<khoá>').
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/frontend"
node tools/remotes/workspace.mjs new "$@"

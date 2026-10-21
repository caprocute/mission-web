#!/usr/bin/env bash
# Đóng gói lại phụ thuộc file:// của mọi chart con trước khi đóng gói vt-platform (INF-02). helm dependency
# update của chart cha chép nguyên thư mục chart con, kể cả bản vt-service-*.tgz cũ trong charts/ của nó, nên
# phải cập nhật từ dưới lên, nếu không thì sửa vt-service sẽ không tới được bản render.
set -euo pipefail
cd "$(dirname "$0")/../.."
for c in mission-core scoring-service evidence-service ledger-service esign-service document-service \
         notification-service integration-gateway ai-gateway analytics search web-frontend iam observability-cell; do
  helm dependency update "$c" >/dev/null
done
helm dependency update vt-platform >/dev/null

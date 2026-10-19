#!/bin/bash
# In một prompt hoàn chỉnh cho AI agent: quy định chung + nội dung task.
# Dùng:  ./scripts/agent-task.sh WEB-012 | claude -p      (hoặc dán vào agent bất kỳ)
set -euo pipefail
cd "$(dirname "$0")/.."
ID="${1:?Cần mã task, ví dụ WEB-012}"
TASK=$(ls docs/tasks/${ID}-*.md 2>/dev/null | head -1)
[ -n "$TASK" ] || { echo "Không tìm thấy task $ID trong docs/tasks/" >&2; exit 1; }
echo "Bạn là AI agent thực hiện task $ID của dự án Mission (phân hệ web)."
echo "BẮT BUỘC: đọc và tuân thủ toàn bộ quy định chung dưới đây trước khi làm."
echo
cat AGENTS.md
echo
echo "=================================== TASK ==================================="
cat "$TASK"
echo
echo "Khi xong, trả lời bằng báo cáo theo mẫu ở AGENTS.md mục 6."

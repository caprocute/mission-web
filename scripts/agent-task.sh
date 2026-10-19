#!/bin/bash
# In một prompt hoàn chỉnh cho AI agent: quy định chung + quy định riêng của phân hệ + nội dung task.
# Dùng:  ./scripts/agent-task.sh WEB-012 | claude -p      (hoặc dán vào agent bất kỳ)
#        ./scripts/agent-task.sh CORE-02-14
set -euo pipefail
cd "$(dirname "$0")/.."
ID="${1:?Cần mã task, ví dụ WEB-012 hoặc CORE-02-14}"
TASK=$(ls docs/tasks/${ID}-*.md 2>/dev/null | head -1)
[ -n "$TASK" ] || { echo "Không tìm thấy task $ID trong docs/tasks/" >&2; exit 1; }
case "$ID" in
  NEN-*|CORE-*|DV-*|DL-*) RIENG=docs/quy-dinh/BACKEND.md ;;
  INF-*|VH-*)             RIENG=docs/quy-dinh/HA-TANG.md ;;
  MOB-*)                  RIENG=docs/quy-dinh/DI-DONG.md ;;
  *)                      RIENG="" ;;
esac
echo "Bạn là AI agent thực hiện task $ID của dự án Mission."
echo "BẮT BUỘC: đọc và tuân thủ toàn bộ quy định chung dưới đây trước khi làm."
echo
cat AGENTS.md
if [ -n "$RIENG" ]; then
  echo
  echo "============================ QUY ĐỊNH RIÊNG CỦA PHÂN HỆ ============================"
  cat "$RIENG"
fi
echo
echo "=================================== TASK ==================================="
cat "$TASK"
echo
echo "Khi xong, trả lời bằng báo cáo theo mẫu ở AGENTS.md mục 6."

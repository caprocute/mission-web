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
echo "BẮT BUỘC CẬP NHẬT TIẾN ĐỘ (YC-PT-17): cập nhật trạng thái task $ID vào tệp duy nhất TIEN-DO-HE-THONG.md bằng lệnh:"
echo "  - Khi bắt đầu: python3 scripts/cap-nhat-tien-do.py set $ID --status \"Đang làm\" --note \"Nhánh ...: đang thực hiện\" --agent \"<Tên luồng>\""
echo "  - Khi hoàn thành trên nhánh: python3 scripts/cap-nhat-tien-do.py set $ID --status \"Xong, chờ gộp\" --note \"Nhánh ...: đã qua build & test\" --agent \"<Tên luồng>\""
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
echo "Khi xong, BẮT BUỘC cập nhật TIEN-DO-HE-THONG.md (python3 scripts/cap-nhat-tien-do.py set $ID ...) và trả lời bằng báo cáo theo mẫu ở AGENTS.md mục 6."

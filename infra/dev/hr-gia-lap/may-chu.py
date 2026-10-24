"""Nguồn nhân sự giả lập cho môi trường dev (YC-PT-13, HT-07): CSDL CBCCVC và CSDL đảng viên trả theo trang với con
trỏ, đúng tên trường của hồ sơ kết nối trong integration-gateway application-dev.yaml. Chỉ dùng thư viện chuẩn.

  GET /api/cbccvc/v1/ho-so?capNhatTu=&gioiHan=      {"duLieu": [...], "conTroTiep": "..."}
  GET /api/dang-vien/v1/dang-vien?capNhatTu=&gioiHan=
  GET /health

Con trỏ "trang:N" là vị trí trong danh sách, con trỏ rỗng đọc từ đầu; trang sau phần tử cuối trả danh sách rỗng. Cổng
tích hợp lưu con trỏ giữa các lần chạy, nên lần "Đồng bộ ngay" sau chỉ nhận phần thêm vào du-lieu.json (khởi động lại
container để nạp lại tệp); muốn kéo lại từ đầu thì xoá dòng integ.sync_cursor của kết nối.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "du-lieu.json"), encoding="utf-8") as f:
    DATA = json.load(f)
ROUTES = {"/api/cbccvc/v1/ho-so": "cbccvc", "/api/dang-vien/v1/dang-vien": "dangVien"}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.send(200, {"status": "UP"})
        key = ROUTES.get(url.path)
        if key is None:
            return self.send(404, {"title": "Không có tài nguyên"})
        q = parse_qs(url.query)
        cursor = (q.get("capNhatTu") or [""])[0]
        limit = max(1, min(int((q.get("gioiHan") or ["200"])[0] or 200), 500))
        start = int(cursor[6:]) if cursor.startswith("trang:") and cursor[6:].isdigit() else 0
        items = DATA[key][start:start + limit]
        return self.send(200, {"duLieu": items, "conTroTiep": f"trang:{start + len(items)}" if items else None})

    def send(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt, *args):
        print("hr-gia-lap", self.command, urlparse(self.path).path, args[1] if len(args) > 1 else "", flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()

"""Máy chủ HTTP RFC 3161 tối giản cho dev (DV-10-03): chuyển mỗi yêu cầu cho `openssl ts -reply`."""
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG = sys.argv[1]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 65536:
            self.send_error(400)
            return
        query = self.rfile.read(length)
        with tempfile.NamedTemporaryFile(suffix=".tsq") as q:
            q.write(query)
            q.flush()
            result = subprocess.run(
                ["openssl", "ts", "-reply", "-config", CONFIG, "-queryfile", q.name],
                capture_output=True,
                check=False,
            )
        if result.returncode != 0:
            self.send_error(500)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/timestamp-reply")
        self.send_header("Content-Length", str(len(result.stdout)))
        self.end_headers()
        self.wfile.write(result.stdout)

    def do_GET(self):  # noqa: N802
        self.send_response(200 if self.path == "/health" else 404)
        self.end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("tsa-mock " + (fmt % args) + "\n")


ThreadingHTTPServer(("0.0.0.0", 8318), Handler).serve_forever()

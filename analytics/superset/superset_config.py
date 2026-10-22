"""DV-15-05 · SVC-15 Superset 4.x nhúng (ADR-14, TL-01 mục SVC-15, TL-03 "Phân quyền phía đọc").

Superset chỉ là công cụ dựng dashboard nâng cao nhúng trong giao diện web:
- nguồn dữ liệu duy nhất là Cube SQL API (cổng Postgres wire của Cube) bằng tài khoản kỹ thuật; không khai báo kết nối
  ClickHouse hay PostgreSQL nghiệp vụ (CSDL siêu dữ liệu của chính Superset là kho riêng của nó);
- người xem không đăng nhập Superset: analytics-api (createAnalyticsEmbedToken) xin guest token ngắn hạn mang điều kiện
  RLS `__user = '<JWT Cube>'`; Cube đổi ngữ cảnh an ninh theo JWT đó (tenant, org_path) và áp cùng bộ lọc như REST;
- vai trò khách chỉ xem dashboard nhúng; SQL Lab, tải tệp lên, API dataset không mở cho khách;
- CSP và Talisman theo TL-01 mục CSP: chỉ origin web của nền tảng được nhúng khung.
Mọi bí mật đọc từ biến môi trường, không có giá trị mặc định.
"""

from __future__ import annotations

import os


def _env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None or value == "":
        raise RuntimeError(f"Thiếu biến môi trường {name}")
    return value


SECRET_KEY = _env("SUPERSET_SECRET_KEY")
SQLALCHEMY_DATABASE_URI = _env("SUPERSET_METADATA_URI")
WEB_ORIGINS = [o.strip() for o in _env("SUPERSET_WEB_ORIGINS").split(",") if o.strip()]

FEATURE_FLAGS = {
    "EMBEDDED_SUPERSET": True,
    "DASHBOARD_RBAC": True,
    "ENABLE_TEMPLATE_PROCESSING": False,
    "ALERT_REPORTS": False,
    "ENABLE_JAVASCRIPT_CONTROLS": False,
}

# Khách nhúng: vai trò riêng, chỉ xem dashboard được cấp trong guest token (khoi_tao.py dựng quyền).
GUEST_ROLE_NAME = "Nhung"
GUEST_TOKEN_JWT_SECRET = _env("SUPERSET_GUEST_TOKEN_SECRET")
GUEST_TOKEN_JWT_ALGO = "HS256"
GUEST_TOKEN_HEADER_NAME = "X-GuestToken"
GUEST_TOKEN_JWT_EXP_SECONDS = int(os.environ.get("SUPERSET_GUEST_TOKEN_TTL", "300"))
GUEST_TOKEN_JWT_AUDIENCE = os.environ.get("SUPERSET_GUEST_TOKEN_AUDIENCE", "mission-superset")

# Không có người dùng ẩn danh, không tự đăng ký, không tải tệp lên.
PUBLIC_ROLE_LIKE = None
AUTH_USER_REGISTRATION = False
CSV_EXTENSIONS: set[str] = set()
EXCEL_EXTENSIONS: set[str] = set()
COLUMNAR_EXTENSIONS: set[str] = set()
ALLOWED_EXTENSIONS: set[str] = set()
PREVENT_UNSAFE_DB_CONNECTIONS = True
SQLLAB_CTAS_NO_LIMIT = False
FAB_ADD_SECURITY_API = False

# analytics-api gọi guest_token bằng token truy cập (Bearer), không có phiên trình duyệt nên miễn CSRF cho đúng
# điểm đó; mọi điểm khác giữ CSRF.
WTF_CSRF_ENABLED = True
WTF_CSRF_EXEMPT_LIST = [
    "superset.views.core.log",
    "superset.charts.data.api.data",
    "superset.security.api.guest_token",
]

# TL-01 mục CSP: không inline script ngoài nonce của Superset, chỉ origin web của nền tảng được nhúng khung.
TALISMAN_ENABLED = True
TALISMAN_CONFIG = {
    "content_security_policy": {
        "base-uri": ["'self'"],
        "default-src": ["'self'"],
        "img-src": ["'self'", "blob:", "data:"],
        "worker-src": ["'self'", "blob:"],
        "connect-src": ["'self'"],
        "object-src": "'none'",
        "style-src": ["'self'", "'unsafe-inline'"],
        "script-src": ["'self'", "'strict-dynamic'"],
        "frame-ancestors": WEB_ORIGINS,
    },
    "content_security_policy_nonce_in": ["script-src"],
    "force_https": False,
    "session_cookie_secure": os.environ.get("SUPERSET_COOKIE_SECURE", "true") == "true",
    "frame_options": None,
}
SESSION_COOKIE_SAMESITE = "None" if TALISMAN_CONFIG["session_cookie_secure"] else "Lax"
SESSION_COOKIE_HTTPONLY = True
# Không bật CORS: khung nhúng tải từ chính Superset, SDK nhúng chỉ trao guest token qua postMessage.
ENABLE_CORS = False
HTTP_HEADERS = {"X-Content-Type-Options": "nosniff", "Referrer-Policy": "same-origin"}

# Giao diện tiếng Việt, không gửi số liệu sử dụng ra ngoài.
BABEL_DEFAULT_LOCALE = "vi"
LANGUAGES = {"vi": {"flag": "vn", "name": "Tiếng Việt"}, "en": {"flag": "us", "name": "English"}}
SUPERSET_WEBSERVER_TIMEOUT = 60
ROW_LIMIT = 5000

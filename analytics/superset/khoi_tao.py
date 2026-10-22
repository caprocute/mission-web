"""DV-15-05 · Khởi tạo Superset nhúng sau `superset db upgrade` và `superset init` (chạy trong container Superset).

1. Vai trò khách `Nhung` (GUEST_ROLE_NAME): chỉ đọc dashboard và biểu đồ, lấy số liệu biểu đồ; không có SQL Lab, API
   dataset, tải tệp hay quyền truy cập CSDL.
2. Tài khoản dịch vụ của analytics-api: chỉ có quyền cấp guest token.
3. Nạp các dashboard mẫu trong dashboards/ (nguồn YAML, thế người dùng và địa chỉ Cube SQL API từ biến môi trường,
   mật khẩu truyền riêng), bật nhúng với uuid cố định và chỉ cho phép origin web của nền tảng.
Chạy lặp lại được: mọi bước đều ghi đè hoặc bỏ qua khi đã có.
"""

from __future__ import annotations

import json
import os
import pathlib
import string
import sys
import uuid

HERE = pathlib.Path(__file__).resolve().parent
DASHBOARDS = HERE / "dashboards"

GUEST_PERMISSIONS = [
    ("can_read", "Dashboard"),
    ("can_read", "Chart"),
    ("can_dashboard", "Superset"),
    ("can_explore_json", "Superset"),
    ("can_read", "DashboardFilterStateRestApi"),
    ("can_write", "DashboardFilterStateRestApi"),
    ("can_read", "DashboardPermalinkRestApi"),
    ("can_read", "EmbeddedDashboard"),
]
SERVICE_ROLE = "CapTokenNhung"
SERVICE_PERMISSIONS = [("can_grant_guest_token", "SecurityRestApi")]


def env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        sys.exit(f"Thiếu biến môi trường {name}")
    return value


def bundle(source: pathlib.Path) -> dict[str, str]:
    """Nội dung gói nhập của một dashboard, theo cấu trúc của tệp ZIP Superset xuất ra, đã bỏ thư mục gốc như API nhập."""
    values = {"CUBE_SQL_USER": env("CUBE_SQL_USER"), "CUBE_SQL_HOST": env("CUBE_SQL_HOST")}
    out = {}
    for f in sorted(source.rglob("*.yaml")):
        out[f.relative_to(source).as_posix()] = string.Template(f.read_text(encoding="utf-8")).substitute(
            values)
    return out


def ensure_role(sm, name: str, permissions: list[tuple[str, str]]) -> None:
    role = sm.find_role(name) or sm.add_role(name)
    wanted = []
    for perm, view in permissions:
        pv = sm.find_permission_view_menu(perm, view)
        if pv is None:
            sys.exit(f"Không thấy quyền {perm} trên {view}")
        wanted.append(pv)
    role.permissions = wanted


def main() -> None:
    from superset.app import create_app

    app = create_app()
    with app.app_context():
        from superset import db, security_manager as sm
        from superset.commands.dashboard.importers.dispatcher import ImportDashboardsCommand
        from superset.models.dashboard import Dashboard
        from superset.models.embedded_dashboard import EmbeddedDashboard
        from superset.utils.core import override_user

        ensure_role(sm, app.config["GUEST_ROLE_NAME"], GUEST_PERMISSIONS)
        ensure_role(sm, SERVICE_ROLE, SERVICE_PERMISSIONS)
        user = env("SUPERSET_SERVICE_USER")
        svc = sm.find_user(username=user)
        if svc is None:
            sm.add_user(user, "analytics-api", "SVC-15", f"{user}@analytics.invalid", [sm.find_role(SERVICE_ROLE)],
                        password=env("SUPERSET_SERVICE_PASSWORD"))
        else:
            svc.roles = [sm.find_role(SERVICE_ROLE)]
            sm.reset_password(svc.id, env("SUPERSET_SERVICE_PASSWORD"))
        db.session.commit()

        catalog = json.loads((DASHBOARDS / "nhung.json").read_text(encoding="utf-8"))
        origins = [o.strip() for o in env("SUPERSET_WEB_ORIGINS").split(",") if o.strip()]
        for item in catalog["dashboards"]:
            contents = bundle(DASHBOARDS / item["source"])
            # Lệnh nhập ghi người tạo theo người dùng hiện tại: chạy dưới tài khoản quản trị kỹ thuật.
            with override_user(sm.find_user(username=env("SUPERSET_ADMIN_USER"))):
                ImportDashboardsCommand(contents, passwords={"databases/Cube.yaml": env("CUBE_SQL_PASSWORD")},
                                        overwrite=True).run()
            dash = db.session.query(Dashboard).filter_by(uuid=uuid.UUID(item["dashboard_uuid"])).one()
            emb = db.session.query(EmbeddedDashboard).filter_by(dashboard_id=dash.id).one_or_none()
            if emb is None:
                emb = EmbeddedDashboard(uuid=uuid.UUID(item["embedded_uuid"]), dashboard_id=dash.id)
                db.session.add(emb)
            emb.allow_domain_list = ",".join(origins)
            db.session.commit()
            print(json.dumps({"code": item["code"], "dashboard_id": dash.id, "embedded_uuid": str(emb.uuid)}))


if __name__ == "__main__":
    main()

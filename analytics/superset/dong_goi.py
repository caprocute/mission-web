"""DV-15-05 · Đóng gói dashboard mẫu thành tệp ZIP nhập được bằng Superset (Dashboards → Import), từ nguồn YAML trong
dashboards/<thư mục>. Người dùng và địa chỉ Cube SQL API lấy từ biến môi trường CUBE_SQL_USER, CUBE_SQL_HOST; mật khẩu
nhập ở hộp thoại của Superset.

    CUBE_SQL_USER=superset_ky_thuat CUBE_SQL_HOST=cube:15432 python dong_goi.py bang-dieu-hanh-don-vi ra.zip
"""

from __future__ import annotations

import os
import pathlib
import string
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent


def main(source: str, target: str) -> None:
    root = HERE / "dashboards" / source
    values = {"CUBE_SQL_USER": os.environ["CUBE_SQL_USER"], "CUBE_SQL_HOST": os.environ["CUBE_SQL_HOST"]}
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(root.rglob("*.yaml")):
            text = string.Template(f.read_text(encoding="utf-8")).substitute(values)
            z.writestr(f"{source}/{f.relative_to(root).as_posix()}", text)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

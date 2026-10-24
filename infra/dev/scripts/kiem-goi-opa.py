#!/usr/bin/env python3
"""Kiểm thư mục mà dịch vụ opa của docker-compose.yml gắn vào /policies/okrkpi có tệp .rego.

Compose gắn `${OPA_POLICY_DIR:-../../backend/opa/okrkpi}`, đường tương đối tính từ thư mục project (infra/dev). Khi
infra/dev được chép ra thư mục riêng ngoài repo, đường đó trỏ vào chỗ không có, Docker tự tạo thư mục rỗng và mọi
API trả 403. Script tự giải đường như compose (biến môi trường, rồi tệp .env, rồi giá trị mặc định) thay vì đọc
`docker compose config`, vì lệnh đó nội suy mọi dịch vụ kể cả profile không bật nên hỏng khi .env thiếu một biến bắt
buộc của dịch vụ khác (ví dụ SUPERSET_*). Mã thoát 1 kèm hướng dẫn khi thư mục không có chính sách. Python 3.6, chỉ
thư viện chuẩn.

    cd infra/dev && python3 scripts/kiem-goi-opa.py [.env]
"""
import glob
import os
import sys

MAC_DINH = "../../backend/opa/okrkpi"


def doc_env(tep):
    """Giá trị OPA_POLICY_DIR trong tệp .env (dạng KEY=VALUE của compose), None khi không có."""
    if not os.path.isfile(tep):
        return None
    gia_tri = None
    with open(tep, encoding="utf-8") as f:
        for dong in f:
            dong = dong.strip()
            if dong.startswith("#") or "=" not in dong:
                continue
            k, v = dong.split("=", 1)
            if k.strip() == "OPA_POLICY_DIR":
                gia_tri = v.strip().strip('"').strip("'")
    return gia_tri


def main():
    tep_env = sys.argv[1] if len(sys.argv) > 1 else ".env"
    thu_muc = os.environ.get("OPA_POLICY_DIR") or doc_env(tep_env) or MAC_DINH
    duong = os.path.normpath(os.path.join(os.getcwd(), thu_muc))
    if not glob.glob(os.path.join(duong, "*.rego")):
        print("Lỗi: thư mục chính sách OPA {} không có tệp .rego; đặt OPA_POLICY_DIR (đường tuyệt đối tới "
              "backend/opa/okrkpi của bản checkout) trong .env của máy chủ".format(duong), file=sys.stderr)
        return 1
    print("  Gói chính sách OPA: {}".format(duong))
    return 0


if __name__ == "__main__":
    sys.exit(main())

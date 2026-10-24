#!/usr/bin/env python3
"""Đọc `docker compose config --format json` từ stdin, kiểm thư mục gắn vào /policies/okrkpi của dịch vụ opa có tệp
.rego. Đường ../../backend/opa/okrkpi của compose tính từ thư mục project, nên khi infra/dev được chép ra thư mục
riêng ngoài repo (sync-server.sh với DEV_SERVER_INFRA_DIR cũ), Docker gắn một thư mục rỗng tự tạo và mọi API trả
403. Mã thoát 1 kèm hướng dẫn khi thư mục không có chính sách. Python 3.6, chỉ thư viện chuẩn.

    docker compose -p mission-dev --env-file .env --profile security config --format json | python3 scripts/kiem-goi-opa.py
"""
import glob
import json
import sys


def main():
    try:
        cfg = json.load(sys.stdin)
    except ValueError:
        print("Lỗi: không đọc được cấu hình compose (lỗi của docker compose config ở trên)", file=sys.stderr)
        return 1
    opa = cfg.get("services", {}).get("opa")
    if opa is None:
        print("  Không có dịch vụ opa trong profile đang áp; bỏ qua kiểm gói chính sách.")
        return 0
    nguon = [v.get("source", "") for v in opa.get("volumes", []) if v.get("target") == "/policies/okrkpi"]
    if not nguon:
        print("Lỗi: dịch vụ opa không gắn thư mục nào vào /policies/okrkpi", file=sys.stderr)
        return 1
    if not glob.glob(nguon[0] + "/*.rego"):
        print("Lỗi: thư mục chính sách OPA {} không có tệp .rego; đặt OPA_POLICY_DIR (đường tuyệt đối tới "
              "backend/opa/okrkpi của bản checkout) trong .env của máy chủ".format(nguon[0]), file=sys.stderr)
        return 1
    print("  Gói chính sách OPA: {}".format(nguon[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

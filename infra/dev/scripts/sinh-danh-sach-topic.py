#!/usr/bin/env python3
"""Sinh infra/dev/kafka/topics.txt từ danh mục topic của production.

Nguồn duy nhất là deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml (sinh từ contracts/events), để Kafka
dev có đúng các topic mà KafkaTopic của Strimzi tạo ở cụm thật, gồm cặp .retry/.dlq của từng bên tiêu thụ. Tệp
sinh ra nằm trong infra/dev vì sync-server.sh chỉ chép thư mục này lên máy chủ dev.

Mỗi dòng: <topic> <cleanup.policy hoặc ->. Số phân vùng không lấy theo production: dev dùng KAFKA_PARTITIONS.

Dùng: python3 -I infra/dev/scripts/sinh-danh-sach-topic.py [--kiem]   (--kiem: mã thoát 1 khi tệp lệch danh mục)
"""
import pathlib
import sys

import yaml

GOC = pathlib.Path(__file__).resolve().parents[3]
DANH_MUC = GOC / "deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml"
DICH = GOC / "infra/dev/kafka/topics.txt"

# Topic có trong mã nhưng chưa có trong contracts/events (cần bổ sung hợp đồng); giữ ở dev để luồng chạy được.
NGOAI_DANH_MUC: dict[str, str] = {}


def sinh() -> str:
    dm = yaml.safe_load(DANH_MUC.read_text(encoding="utf-8"))
    dong: dict[str, str] = {}
    for t in dm["topics"]:
        if t.get("nenTheoKhoa"):
            cs = "compact,delete"
        elif t.get("luuGiu") == "compact":
            cs = "compact"
        else:
            cs = "-"
        dong[t["ten"]] = cs
    for c in dm.get("capLoi", []):
        for loai in ("retry", "dlq"):
            dong[f"{c['topic']}.{loai}.{c['consumer']}"] = "-"
    for t, cs in NGOAI_DANH_MUC.items():
        dong.setdefault(t, cs)
    dau = (
        "# Sinh tự động bằng infra/dev/scripts/sinh-danh-sach-topic.py từ\n"
        "# deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml; không sửa tay.\n"
        "# <topic> <cleanup.policy hoặc ->\n"
    )
    return dau + "".join(f"{t} {dong[t]}\n" for t in sorted(dong))


def main() -> int:
    moi = sinh()
    if "--kiem" in sys.argv[1:]:
        cu = DICH.read_text(encoding="utf-8") if DICH.exists() else ""
        if cu != moi:
            print(f"{DICH.relative_to(GOC)} lệch danh mục; chạy lại sinh-danh-sach-topic.py", file=sys.stderr)
            return 1
        print(f"{DICH.relative_to(GOC)} khớp danh mục ({moi.count(chr(10)) - 3} topic)")
        return 0
    DICH.write_text(moi, encoding="utf-8")
    print(f"Đã ghi {DICH.relative_to(GOC)} ({moi.count(chr(10)) - 3} topic)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

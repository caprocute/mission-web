#!/usr/bin/env python3
"""Nạp OKR mẫu đã phê duyệt cho dev qua API thật (APISIX → mission-core), để hành trình HT-08, HT-09 chạy được.

Chạy sau `seed-org.sh`, `dong-bo-to-chuc.sh` và khi đã có kỳ đang mở bao trùm hôm nay (`seed-ky-hien-hanh.sh` hoặc
`seed-ky-nhiem-vu.py`):
  1. `phogiamdocso` soạn mục tiêu tập thể của Sở Thử nghiệm (đơn vị cấp cao nhất), trình; `giamdocso` phê duyệt.
     Mục tiêu phòng ban soạn ở HT-08 chọn được mục tiêu cấp trên đã duyệt (BR-02-02).
  2. `canbo` soạn mục tiêu cá nhân ở Phòng Tổng hợp phân rã từ mục tiêu của Sở, trình; `lanhdao` phê duyệt.
     `canbo` có KR đã duyệt để check-in ở HT-09 (SCR-0301).
  3. `canbo` soạn thêm một mục tiêu cá nhân và trình, để ở chờ duyệt cho ca phủ định HT-08.
Chạy lại không sinh bản ghi trùng: mục tiêu mẫu (tên bắt đầu bằng "[Mẫu dev]") đã duyệt thì bỏ qua, còn nháp hay chờ
duyệt thì đi tiếp từ bước dở. Gọi API như người dùng nên kiểm tra nghiệp vụ, kiểm toán và sự kiện chạy như trên màn
hình. Chỉ dùng thư viện chuẩn, không in token.

    python3 -I infra/dev/scripts/seed-okr-mau.py
"""

import datetime as dt
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_ky_nhiem_vu", HERE / "seed-ky-nhiem-vu.py")
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
nap_env, token, claims, goi = _base.nap_env, _base.token, _base.claims, _base.goi

TIEN_TO = "[Mẫu dev]"
SO = "d0000000-0000-4000-8000-000000000100"
PHONG_TH = "d0000000-0000-4000-8000-000000000101"


def etag(o: dict, e) -> str:
    return e or f'"{o["rowVersion"]}"'


def mau(tok: str, ky: dict, unit: str, ten: str):
    _, ds, _ = goi(tok, "GET", f"/okr/objectives?period={ky['id']}&unit={unit}&view=table&limit=200")
    return next((o for o in ds["items"] if o["title"] == ten), None)


def lap(tac_gia: str, duyet: str, ky: dict, can_cu: str, ten: str, than: dict) -> dict:
    """Soạn (nếu chưa có), trình và phê duyệt một mục tiêu mẫu; trả mục tiêu đã duyệt."""
    unit = than["ownerUnitId"]
    o = mau(tac_gia, ky, unit, ten)
    if o and o["status"] == "DA_DUYET":
        print(f"Đã có mục tiêu mẫu đã duyệt: {ten}")
        return o
    if not o:
        _, o, _ = goi(tac_gia, "POST", "/okr/objectives",
                      dict(than, periodId=ky["id"], title=ten, basisIds=[can_cu]))
    if o["status"] in ("NHAP", "TRA_LAI"):
        _, o, e = goi(tac_gia, "GET", f"/okr/objectives/{o['id']}")
        _, o, _ = goi(tac_gia, "POST", f"/okr/objectives/{o['id']}/submit", if_match=etag(o, e))
    _, o, e = goi(duyet, "GET", f"/okr/objectives/{o['id']}")
    _, o, _ = goi(duyet, "POST", f"/okr/objectives/{o['id']}/approve",
                  {"comment": "Dữ liệu thử của môi trường dev", "notifyPeers": False}, if_match=etag(o, e))
    print(f"Đã phê duyệt mục tiêu mẫu: {ten}")
    return o


def trinh(tac_gia: str, ky: dict, can_cu: str, ten: str, than: dict) -> dict:
    """Soạn (nếu chưa có) và trình một mục tiêu mẫu, để ở trạng thái chờ duyệt (ca phủ định HT-08)."""
    o = mau(tac_gia, ky, than["ownerUnitId"], ten)
    if not o:
        _, o, _ = goi(tac_gia, "POST", "/okr/objectives",
                      dict(than, periodId=ky["id"], title=ten, basisIds=[can_cu]))
    if o["status"] in ("NHAP", "TRA_LAI"):
        _, o, e = goi(tac_gia, "GET", f"/okr/objectives/{o['id']}")
        _, o, _ = goi(tac_gia, "POST", f"/okr/objectives/{o['id']}/submit", if_match=etag(o, e))
    print(f"Mục tiêu mẫu chờ duyệt: {ten} ({o['status']})")
    return o


def kr(ten: str, nguoi_xac_nhan: str) -> dict:
    return {"title": ten, "metricType": "TY_LE", "unit": "%", "baselineValue": 0, "targetValue": 100,
            "direction": "TANG", "weight": 1, "verifierPersonId": nguoi_xac_nhan, "checkinFrequency": "TUAN"}


def main() -> int:
    env = nap_env()
    hom = dt.date.today().isoformat()
    t_pgd, t_gd = token("phogiamdocso", env), token("giamdocso", env)
    t_cb, t_ld, t_ql = token("canbo", env), token("lanhdao", env), token("quanly", env)
    _, ds, _ = goi(t_pgd, "GET", "/periods?status=MO&limit=200")
    ky = next((k for k in ds["items"] if k["startDate"] <= hom <= k["endDate"]), None)
    if not ky:
        sys.exit("Chưa có kỳ đang mở bao trùm hôm nay: chạy seed-ky-hien-hanh.sh trước")
    _, cc, _ = goi(t_pgd, "GET", "/okr/bases?limit=20")
    if not cc["items"]:
        sys.exit("Chưa có căn cứ OKR: chạy seed-org.sh (okr-can-cu-thu.sql) trước")
    can_cu = cc["items"][0]["id"]
    so = lap(t_pgd, t_gd, ky, can_cu, f"{TIEN_TO} Nâng cao chất lượng tham mưu của Sở",
             {"ownerType": "TAP_THE", "ownerUnitId": SO,
              "keyResults": [kr("Tỷ lệ văn bản tham mưu đúng hạn", claims(t_gd)["sub"])]})
    lap(t_cb, t_ld, ky, can_cu, f"{TIEN_TO} Hoàn thành đúng hạn báo cáo tổng hợp",
        {"ownerType": "CA_NHAN", "ownerUnitId": PHONG_TH, "ownerPersonId": claims(t_cb)["sub"],
         "parentObjectiveId": so["id"],
         "keyResults": [kr("Tỷ lệ báo cáo tổng hợp nộp đúng hạn", claims(t_ql)["sub"])]})
    # 3. Một mục tiêu cá nhân để ở chờ duyệt: ca phủ định HT-08 thử phê duyệt trái quyền bằng `quanly` (403)
    trinh(t_cb, ky, can_cu, f"{TIEN_TO} Chuẩn hoá mẫu biểu báo cáo của phòng",
          {"ownerType": "CA_NHAN", "ownerUnitId": PHONG_TH, "ownerPersonId": claims(t_cb)["sub"],
           "parentObjectiveId": so["id"],
           "keyResults": [kr("Tỷ lệ mẫu biểu được chuẩn hoá", claims(t_ql)["sub"])]})
    return 0


if __name__ == "__main__":
    sys.exit(main())

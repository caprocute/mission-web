"""Sinh migration lặp Flyway nạp lịch nghỉ quốc gia cho tenant hệ thống (DL-07).

`holiday_calendar_<năm>.csv` trong thư mục này là nguồn; tệp SQL là sản phẩm sinh, không sửa tay.
Chạy:
    python3 -I data/seed/config/sinh_migration.py            # ghi lại tệp SQL
    python3 -I data/seed/config/sinh_migration.py --kiem-tra # chỉ so, khác thì thoát mã 1
"""

import csv
import hashlib
import sys
import uuid
from pathlib import Path

GOC = Path(__file__).resolve().parents[3]
NGUON = GOC / "data/seed/config"
DICH = GOC / "backend/mission-core/src/main/resources/db/migration/config/R__config_lich_nghi_quoc_gia.sql"

# Cùng quy ước với data/seed/org/sinh_migration.py: tenant hệ thống là UUID rỗng (suy luận, wiki 14).
TENANT_HE_THONG = "00000000-0000-0000-0000-000000000000"
TAI_KHOAN_NAP = "00000000-0000-0000-0000-000000000000"


def ma_dong(ngay: str) -> str:
    """Mã dòng tất định, trùng `NationalHolidays.idOf` (UUID.nameUUIDFromBytes của Java, MD5 bản 3)."""
    return str(uuid.UUID(bytes=hashlib.md5(f"QUOC_GIA:{ngay}".encode("utf-8")).digest(), version=3))


def doc() -> list[dict]:
    dong = []
    for tep in sorted(NGUON.glob("holiday_calendar_*.csv")):
        with tep.open(encoding="utf-8", newline="") as f:
            dong += list(csv.DictReader(f))
    return sorted(dong, key=lambda r: r["calendar_date"])


def q(v):
    return "NULL" if v is None or v == "" else "'" + str(v).replace("'", "''") + "'"


def sinh() -> str:
    dong = doc()
    t = f"'{TENANT_HE_THONG}'::uuid"
    a = f"'{TAI_KHOAN_NAP}'::uuid"
    s = "${flyway:defaultSchema}"
    out = [
        "-- DL-07: lịch nghỉ quốc gia (scope QUOC_GIA) của tenant hệ thống trong config.holiday_calendar.",
        "-- SINH TỰ ĐỘNG từ data/seed/config/holiday_calendar_*.csv bằng data/seed/config/sinh_migration.py; không sửa tay.",
        "-- Mã dòng trùng NationalHolidays.idOf để lịch dựng sẵn của CORE-08-04 và dòng trong bảng là một.",
        "-- Chạy bằng mig_config (chủ bảng). FORCE RLS chặn cả chủ bảng nên tạm tắt trong giao dịch của migration.",
        f"ALTER TABLE {s}.holiday_calendar NO FORCE ROW LEVEL SECURITY;",
        "",
        f"INSERT INTO {s}.holiday_calendar (id, tenant_id, calendar_date, day_kind, name, scope, created_by, updated_by)",
        "VALUES",
        ",\n".join(
            f"  ('{ma_dong(r['calendar_date'])}', {t}, {q(r['calendar_date'])}, {q(r['day_kind'])}, "
            f"{q(r['name'])}, 'QUOC_GIA', {a}, {a})"
            for r in dong
        ),
        "ON CONFLICT (tenant_id, calendar_date) DO UPDATE SET day_kind = EXCLUDED.day_kind, name = EXCLUDED.name,",
        "  scope = EXCLUDED.scope, updated_at = now(), updated_by = EXCLUDED.updated_by,",
        f"  row_version = {s}.holiday_calendar.row_version + 1",
        f"WHERE ({s}.holiday_calendar.day_kind, {s}.holiday_calendar.name, {s}.holiday_calendar.scope)",
        "  IS DISTINCT FROM (EXCLUDED.day_kind, EXCLUDED.name, EXCLUDED.scope);",
        "",
        "-- Ngày đã bỏ khỏi tệp nguồn (đính chính theo văn bản chính thức): xoá, vì lịch không bị bảng khác tham chiếu.",
        f"DELETE FROM {s}.holiday_calendar WHERE tenant_id = {t} AND scope = 'QUOC_GIA'",
        "  AND calendar_date NOT IN (" + ", ".join(q(r["calendar_date"]) for r in dong) + ");",
        "",
        f"ALTER TABLE {s}.holiday_calendar FORCE ROW LEVEL SECURITY;",
        "",
    ]
    return "\n".join(out)


def main() -> int:
    noi_dung = sinh()
    if "--kiem-tra" in sys.argv:
        if not DICH.is_file() or DICH.read_text(encoding="utf-8") != noi_dung:
            print(f"{DICH.name} lệch nguồn CSV; chạy lại sinh_migration.py", file=sys.stderr)
            return 1
        print(f"{DICH.name} khớp nguồn CSV.")
        return 0
    DICH.write_text(noi_dung, encoding="utf-8")
    print(f"Đã ghi {DICH.relative_to(GOC)}: {len(doc())} ngày.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

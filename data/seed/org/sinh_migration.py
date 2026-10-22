"""Sinh migration lặp Flyway nạp danh mục khối đối tượng và chức danh quốc gia (DL-07).

CSV trong thư mục này là nguồn; tệp SQL là sản phẩm sinh, không sửa tay. Chạy:
    python3 -I data/seed/org/sinh_migration.py            # ghi lại tệp SQL
    python3 -I data/seed/org/sinh_migration.py --kiem-tra # chỉ so, khác thì thoát mã 1
"""

import csv
import sys
import uuid
from pathlib import Path

GOC = Path(__file__).resolve().parents[3]
NGUON = GOC / "data/seed/org"
DICH = GOC / "backend/services/org-service/src/main/resources/db/migration/org/R__org_danh_muc_quoc_gia.sql"

# Tenant hệ thống giữ danh mục quốc gia (TL-03 bảng org.job_title). Chưa có sổ đăng ký tenant nên
# dùng UUID rỗng làm mã quy ước (suy luận, ghi ở wiki trang 14); tài khoản ghi là tài khoản nạp dữ liệu nền.
TENANT_HE_THONG = "00000000-0000-0000-0000-000000000000"
TAI_KHOAN_NAP = "00000000-0000-0000-0000-000000000000"
# Mã bản ghi cố định theo mã danh mục để chạy lại không đổi id và khoá ngoại.
KHONG_GIAN = uuid.UUID("6f1d2c4e-8b0a-5e3c-9d7f-0a1b2c3d4e5f")


def doc(ten):
    with open(NGUON / ten, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def q(v):
    return "NULL" if v is None or v == "" else "'" + str(v).replace("'", "''") + "'"


def ma(loai, code):
    return str(uuid.uuid5(KHONG_GIAN, f"{loai}:{code}"))


def sinh():
    khoi = doc("subject_group.csv")
    chuc_danh = doc("job_title.csv")
    loi = [r["code"] for r in khoi if not r["name"] or not r["flow"] or not r["valid_from"]]
    loi += [r["code"] for r in chuc_danh if not r["name"] or not r["level"] or not r["valid_from"]]
    if loi:
        sys.exit("Dòng thiếu name, flow, level hoặc valid_from (cột NOT NULL): " + ", ".join(loi))
    ma_khoi = {r["code"] for r in khoi}
    sai = [r["code"] for r in chuc_danh if r["subject_group_code"] and r["subject_group_code"] not in ma_khoi]
    if sai:
        sys.exit("Chức danh trỏ tới khối không có: " + ", ".join(sai))

    t = f"'{TENANT_HE_THONG}'::uuid"
    a = f"'{TAI_KHOAN_NAP}'::uuid"
    out = [
        "-- DL-07: danh mục quốc gia của tenant hệ thống: 9 khối đối tượng (PL 2A) và chức danh (PL 2B).",
        "-- SINH TỰ ĐỘNG từ data/seed/org/*.csv bằng data/seed/org/sinh_migration.py; không sửa tay.",
        "-- Dữ liệu hiện là GIẢ LẬP (YC-PT-14, người dùng chấp nhận 22/10/2026); thay CSV khi có PL 2A, PL 2B.",
        "-- Chạy bằng mig_org (chủ bảng). FORCE RLS chặn cả chủ bảng nên tạm tắt trong giao dịch của migration.",
        "ALTER TABLE org.subject_group NO FORCE ROW LEVEL SECURITY;",
        "ALTER TABLE org.job_title NO FORCE ROW LEVEL SECURITY;",
        "",
        "INSERT INTO org.subject_group (id, tenant_id, code, name, flow, description, valid_from, valid_to, "
        "created_by, updated_by)",
        "VALUES",
    ]
    out.append(",\n".join(
        f"  ('{ma('subject_group', r['code'])}', {t}, {q(r['code'])}, {q(r['name'])}, {q(r['flow'])}, "
        f"{q(r['description'])}, {q(r['valid_from'])}, {q(r['valid_to'] or 'infinity')}, {a}, {a})"
        for r in khoi))
    out += [
        "ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, flow = EXCLUDED.flow,",
        "  description = EXCLUDED.description, valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,",
        "  updated_at = now(), updated_by = EXCLUDED.updated_by, row_version = org.subject_group.row_version + 1",
        "WHERE (org.subject_group.name, org.subject_group.flow, org.subject_group.description,",
        "       org.subject_group.valid_from, org.subject_group.valid_to)",
        "  IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.flow, EXCLUDED.description, EXCLUDED.valid_from, EXCLUDED.valid_to);",
        "",
        "INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, subject_group_id, "
        "valid_from, valid_to, created_by, updated_by)",
        "VALUES",
    ]
    out.append(",\n".join(
        f"  ('{ma('job_title', r['code'])}', {t}, {q(r['code'])}, {q(r['name'])}, {int(r['level'])}, "
        f"{'true' if r['is_central_managed'] == 'true' else 'false'}, "
        f"{q(ma('subject_group', r['subject_group_code']) if r['subject_group_code'] else None)}, "
        f"{q(r['valid_from'])}, {q(r['valid_to'] or 'infinity')}, {a}, {a})"
        for r in chuc_danh))
    out += [
        "ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, level = EXCLUDED.level,",
        "  is_central_managed = EXCLUDED.is_central_managed, subject_group_id = EXCLUDED.subject_group_id,",
        "  valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,",
        "  updated_at = now(), updated_by = EXCLUDED.updated_by, row_version = org.job_title.row_version + 1",
        "WHERE (org.job_title.name, org.job_title.level, org.job_title.is_central_managed, org.job_title.subject_group_id,",
        "       org.job_title.valid_from, org.job_title.valid_to)",
        "  IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.level, EXCLUDED.is_central_managed, EXCLUDED.subject_group_id,",
        "                    EXCLUDED.valid_from, EXCLUDED.valid_to);",
        "",
        "-- Mục đã bỏ khỏi tệp nguồn: khép hiệu lực, không xoá vì có thể đã được tham chiếu.",
    ]
    for bang, ds in (("job_title", chuc_danh), ("subject_group", khoi)):
        codes = ", ".join(q(r["code"]) for r in ds)
        out += [
            f"UPDATE org.{bang} SET valid_to = GREATEST(current_date, valid_from + 1), updated_at = now(),",
            f"  updated_by = {a}, row_version = row_version + 1",
            f"WHERE tenant_id = {t} AND valid_to = 'infinity' AND code NOT IN ({codes});",
        ]
    out += [
        "",
        "ALTER TABLE org.subject_group FORCE ROW LEVEL SECURITY;",
        "ALTER TABLE org.job_title FORCE ROW LEVEL SECURITY;",
        "",
    ]
    return "\n".join(out)


if __name__ == "__main__":
    sql = sinh()
    if "--kiem-tra" in sys.argv:
        if not DICH.exists() or DICH.read_text(encoding="utf-8") != sql:
            sys.exit(f"{DICH} lệch với CSV; chạy lại sinh_migration.py")
        print("OK")
    else:
        DICH.write_text(sql, encoding="utf-8")
        print(f"Đã ghi {DICH.relative_to(GOC)}")

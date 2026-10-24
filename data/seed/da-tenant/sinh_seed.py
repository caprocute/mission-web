#!/usr/bin/env python3
"""Sinh tệp seed của bộ dữ liệu thử đa tenant từ `du_lieu.py` (giả lập, YC-PT-13).

Sinh ba tệp, commit cùng mã nguồn:
  infra/dev/postgres/seed/da-tenant-du-lieu-thu.sql  tenant-registry, tổ chức (org), căn cứ OKR, danh mục công việc
  infra/dev/keycloak/tai-khoan-da-tenant.json        tài khoản realm `mission` (mật khẩu, khoá OTP là biến thay thế)
  data/seed/da-tenant/TAI-KHOAN.md                   bảng tài khoản theo tenant, dùng cho kịch bản trải nghiệm

    python3 -I data/seed/da-tenant/sinh_seed.py          # sinh lại
    python3 -I data/seed/da-tenant/sinh_seed.py --kiem   # chỉ kiểm tệp đã commit khớp dữ liệu nguồn (mã thoát 1 nếu lệch)
"""

import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_spec = importlib.util.spec_from_file_location("du_lieu", str(HERE / "du_lieu.py"))
D = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(D)

SQL = ROOT / "infra/dev/postgres/seed/da-tenant-du-lieu-thu.sql"
KC = ROOT / "infra/dev/keycloak/tai-khoan-da-tenant.json"
MD = HERE / "TAI-KHOAN.md"

HE_THONG = "00000000-0000-0000-0000-000000000000"
HE_THONG_V7 = "00000000-0000-7000-8000-000000000000"


def q(s):
    return "NULL" if s is None else "'" + str(s).replace("'", "''") + "'"


def kiem_du_lieu():
    """Kiểm tính nhất quán của dữ liệu nguồn trước khi sinh; trả danh sách lỗi."""
    loi = []
    tai_khoan = set()
    for t in D.TENANT:
        dv = {u[0]: u for u in t["don_vi"]}
        cd = {c[0] for c in t["chuc_danh"]}
        nhom = {n[0] for n in t["nhom"]}
        cb = {}
        for u in t["don_vi"]:
            if u[6] is not None and u[6] not in dv:
                loi.append("%s: đơn vị %s có cha không tồn tại" % (t["ma"], u[1]))
            if u[5] not in nhom:
                loi.append("%s: đơn vị %s có nhóm đối tượng lạ" % (t["ma"], u[1]))
        dau = {}
        for p in t["can_bo"]:
            if p["tk"] in tai_khoan:
                loi.append("trùng tài khoản %s" % p["tk"])
            tai_khoan.add(p["tk"])
            if not p["tk"].startswith(t["ma"] if t["ma"] != "dang" else "tu."):
                loi.append("%s: tài khoản %s sai tiền tố" % (t["ma"], p["tk"]))
            if p["dv"] not in dv or p["cd"] not in cd:
                loi.append("%s: %s có đơn vị hoặc chức danh lạ" % (t["ma"], p["tk"]))
            for r in p["vai_tro"]:
                if r not in D.VAI_TRO_NHAN:
                    loi.append("%s: vai trò lạ %s" % (p["tk"], r))
            if p.get("dung_dau"):
                if p["dv"] in dau:
                    loi.append("%s: đơn vị %s có hai người đứng đầu" % (t["ma"], dv[p["dv"]][1]))
                dau[p["dv"]] = p["tk"]
            for k in p.get("kiem_nhiem", []):
                if k[0] not in dv or k[1] not in cd:
                    loi.append("%s: kiêm nhiệm lạ" % p["tk"])
            cb[p["tk"]] = p
        okr = {}
        for o in D.OKR[t["ma"]]:
            okr[o["khoa"]] = o
            for k in ("soan", "duyet"):
                if o[k] not in cb:
                    loi.append("%s/%s: %s lạ" % (t["ma"], o["khoa"], o[k]))
            if o["soan"] == o["duyet"]:
                loi.append("%s/%s: người duyệt trùng người soạn" % (t["ma"], o["khoa"]))
            if "leader" not in cb.get(o["duyet"], {}).get("vai_tro", []):
                loi.append("%s/%s: người duyệt không có vai trò lãnh đạo" % (t["ma"], o["khoa"]))
            can = "manager" if o["loai"] == "TAP_THE" else "member"
            if can not in cb.get(o["soan"], {}).get("vai_tro", []):
                loi.append("%s/%s: người soạn thiếu vai trò %s" % (t["ma"], o["khoa"], can))
            if o.get("cha") and o["cha"] not in okr:
                loi.append("%s/%s: mục tiêu cha phải khai báo trước" % (t["ma"], o["khoa"]))
            if not o.get("cha") and dv[o["dv"]][6] is not None:
                loi.append("%s/%s: mục tiêu đơn vị cấp dưới phải có mục tiêu cha" % (t["ma"], o["khoa"]))
            tong = round(sum(k[6] for k in o["kr"]), 6)
            if tong != 1:
                loi.append("%s/%s: tổng trọng số KR là %s" % (t["ma"], o["khoa"], tong))
            if not 2 <= len(o["kr"]) <= 5:
                loi.append("%s/%s: cần 2 đến 5 KR" % (t["ma"], o["khoa"]))
            for k in o["kr"]:
                chu = o["soan"]
                if k[7] not in cb or k[7] == chu:
                    loi.append("%s/%s: người xác nhận KR không hợp lệ" % (t["ma"], o["khoa"]))
                if k[3] == k[4]:
                    loi.append("%s/%s: chỉ tiêu trùng giá trị đầu" % (t["ma"], o["khoa"]))
                if (k[5] == "GIAM") != (k[4] < k[3]):
                    loi.append("%s/%s: chiều KR không khớp chỉ tiêu" % (t["ma"], o["khoa"]))
                if k[8] is not None and o.get("dich", "DA_DUYET") != "DA_DUYET":
                    loi.append("%s/%s: chỉ check-in được KR đã duyệt" % (t["ma"], o["khoa"]))
        for n in D.NHIEM_VU[t["ma"]]:
            giao, chu = cb.get(n[0]), cb.get(n[1])
            if not giao or not chu or "manager" not in giao["vai_tro"] or "member" not in chu["vai_tro"]:
                loi.append("%s: nhiệm vụ '%s' sai người giao hoặc chủ trì" % (t["ma"], n[2][:40]))
            elif giao["dv"] != chu["dv"]:
                loi.append("%s: nhiệm vụ '%s' giao khác đơn vị" % (t["ma"], n[2][:40]))
            if n[12]:
                khoa, so = n[12].split("#")
                if khoa not in okr or int(so) > len(okr[khoa]["kr"]) or okr[khoa].get("dich", "DA_DUYET") != "DA_DUYET":
                    loi.append("%s: nhiệm vụ '%s' liên kết KR lạ" % (t["ma"], n[2][:40]))
            for p in n[13]:
                if p not in cb or p in (n[0], n[1]):
                    loi.append("%s: người phối hợp lạ %s" % (t["ma"], p))
    return loi


def sinh_sql():
    out = [
        "-- SINH TỰ ĐỘNG bởi data/seed/da-tenant/sinh_seed.py từ data/seed/da-tenant/du_lieu.py, không sửa tay.",
        "-- Bộ dữ liệu thử đa tenant cho dev (giả lập, YC-PT-13): ba tenant hư cấu của tỉnh giả định Phú Lâm",
        "-- (khối Đảng, khối chính quyền, doanh nghiệp nhà nước) trên cell DEV dùng chung, mô hình pool:",
        "-- tenant-registry, nhóm đối tượng, đơn vị và quan hệ, chức danh, cán bộ, phân công, căn cứ OKR,",
        "-- danh mục công việc. Không có số định danh, ngày sinh, liên hệ (không có cột C2).",
        "-- Nạp bằng tài khoản quản trị (bỏ qua RLS) sau migration của tenant-registry, org-service, mission-core:",
        "--   ./infra/dev/scripts/seed-da-tenant.sh",
        "-- Chạy lại không sinh bản ghi trùng (ON CONFLICT DO NOTHING trên khoá cố định).",
        "BEGIN;",
        "",
    ]
    for t in D.TENANT:
        u = lambda n, t=t: D.uid(t["tien_to"], n)  # noqa: E731
        tid = u(1)
        flows = "ARRAY[%s]::varchar(12)[]" % ", ".join(q(f) for f in t["flows"])
        out.append("-- ===== %s (%s) =====" % (t["ten"], tid))
        out.append(
            "INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, max_accounts, flows, kek_ref,\n"
            "                             created_by, updated_by)\n"
            "VALUES (%s, %s, %s, %s, 'POOL', 'DANG_VAN_HANH', 10000, %s, 'dev/gia-lap', %s, %s)\n"
            "ON CONFLICT (id) DO NOTHING;"
            % (q(tid), q(t["code"]), q(t["ten"]), q(t["sector"]), flows, q(HE_THONG), q(HE_THONG))
        )
        out.append(
            "INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, tenancy_model,\n"
            "                                  data_region, created_by, updated_by)\n"
            "SELECT %s, %s, %s, 'mission', '2026-01-01T00:00:00+07:00', 'pool', 'VN', %s, %s\n"
            "WHERE NOT EXISTS (SELECT 1 FROM registry.tenant_cell WHERE tenant_id = %s);"
            % (q(u(0xC2)), q(tid), q(D.CELL_DEV), q(HE_THONG), q(HE_THONG), q(tid))
        )
        for so, code, ten, flow in t["nhom"]:
            out.append(
                "INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by)\n"
                "VALUES (%s, %s, %s, %s, %s, '2000-01-01', %s, %s) ON CONFLICT DO NOTHING;"
                % (q(u(so)), q(tid), q(code), q(ten), q(flow), q(HE_THONG), q(HE_THONG))
            )
        dong = []
        for so, code, ten, loai, cap, nhom, _cha, _dang in t["don_vi"]:
            dong.append("  (%s, %s, %s, %s, %s, %s)" % (q(u(so)), q(code), q(ten), q(loai), q(cap), q(u(nhom))))
        out.append(
            "INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, level, subject_group_id, flows,\n"
            "                          valid_from, created_by, updated_by)\n"
            "SELECT md5('org_unit:' || v.id)::uuid, v.id::uuid, %s, v.code, v.name, v.unit_type, v.level,\n"
            "       v.sg::uuid, %s, '2000-01-01', %s, %s\n"
            "FROM (VALUES\n%s\n) AS v(id, code, name, unit_type, level, sg)\nON CONFLICT DO NOTHING;"
            % (q(tid), flows, q(HE_THONG), q(HE_THONG), ",\n".join(dong))
        )
        dong = []
        for so, _c, _t, _l, _cap, _n, cha, dang in t["don_vi"]:
            if cha is None:
                continue
            dong.append("  (%s, %s, 'CHUYEN_MON')" % (q(u(cha)), q(u(so))))
            if dang:
                dong.append("  (%s, %s, 'DANG')" % (q(u(cha)), q(u(so))))
        out.append(
            "INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id,\n"
            "                                   relation_type, valid_from, created_by, updated_by)\n"
            "SELECT md5('org_unit_relation:' || r.tree || ':' || r.child)::uuid, md5('relation:' || r.tree || ':' || r.child)::uuid,\n"
            "       %s, r.tree, r.parent::uuid, r.child::uuid, 'TRUC_THUOC', '2000-01-01', %s, %s\n"
            "FROM (VALUES\n%s\n) AS r(parent, child, tree)\nON CONFLICT DO NOTHING;"
            % (q(tid), q(HE_THONG), q(HE_THONG), ",\n".join(dong))
        )
        dong = ["  (%s, %s, %s, %d, %s)" % (q(u(so)), q(c), q(n), lv, "true" if tw else "false")
                for so, c, n, lv, tw in t["chuc_danh"]]
        out.append(
            "INSERT INTO org.job_title (id, tenant_id, code, name, level, is_central_managed, valid_from, created_by,\n"
            "                           updated_by)\n"
            "SELECT j.id::uuid, %s, j.code, j.name, j.level, j.tw, '2000-01-01', %s, %s\n"
            "FROM (VALUES\n%s\n) AS j(id, code, name, level, tw)\nON CONFLICT DO NOTHING;"
            % (q(tid), q(HE_THONG), q(HE_THONG), ",\n".join(dong))
        )
        dong = ["  (%s, %s, %s)" % (q(u(p["so"])), q(p["ten"]), "true" if p.get("dang_vien", True) else "false")
                for p in t["can_bo"]]
        out.append(
            "INSERT INTO org.person (version_id, id, tenant_id, full_name, is_party_member, employment_status,\n"
            "                        valid_from, created_by, updated_by)\n"
            "SELECT md5('person:' || p.id)::uuid, p.id::uuid, %s, p.name, p.dv, 'DANG_CONG_TAC', '2000-01-01', %s, %s\n"
            "FROM (VALUES\n%s\n) AS p(id, name, dv)\nON CONFLICT DO NOTHING;"
            % (q(tid), q(HE_THONG), q(HE_THONG), ",\n".join(dong))
        )
        dong = []
        for p in t["can_bo"]:
            dong.append("  (%s, %s, %s, 'CHINH', %s, %s)" % (
                q(u(p["so"])), q(u(p["dv"])), q(u(p["cd"])), q(t["nhanh"]), "true" if p.get("dung_dau") else "false"))
            for dvk, cdk, dauk in p.get("kiem_nhiem", []):
                nhanh = "DANG" if t["ma"] == "dang" else t["nhanh"]
                dong.append("  (%s, %s, %s, 'KIEM_NHIEM', %s, %s)" % (
                    q(u(p["so"])), q(u(dvk)), q(u(cdk)), q(nhanh), "true" if dauk else "false"))
        out.append(
            "INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,\n"
            "                            is_head, valid_from, created_by, updated_by)\n"
            "SELECT md5('assignment-version:' || a.kind || ':' || a.person || ':' || a.unit)::uuid,\n"
            "       md5('assignment:' || a.kind || ':' || a.person || ':' || a.unit)::uuid, %s, a.person::uuid,\n"
            "       a.unit::uuid, a.title::uuid, a.kind, a.branch, a.head, '2000-01-01', %s, %s\n"
            "FROM (VALUES\n%s\n) AS a(person, unit, title, kind, branch, head)\nON CONFLICT DO NOTHING;"
            % (q(tid), q(HE_THONG), q(HE_THONG), ",\n".join(dong))
        )
        dong = ["  (%s, %s, %s, %s, %s)" % (q(u(so)), q(kind), q(num), q(ten), q(ngay))
                for so, kind, num, ten, ngay in t["can_cu"]]
        out.append(
            "INSERT INTO okr.basis (id, tenant_id, created_by, updated_by, basis_kind, doc_number, title,\n"
            "                       issued_by_unit_id, issued_on, valid_from)\n"
            "SELECT b.id::uuid, %s, %s, %s, b.kind, b.num, b.title, %s, b.ngay::date, b.ngay::date\n"
            "FROM (VALUES\n%s\n) AS b(id, kind, num, title, ngay)\nON CONFLICT (id) DO NOTHING;"
            % (q(tid), q(HE_THONG_V7), q(HE_THONG_V7), q(u(0x100)), ",\n".join(dong))
        )
        dm = t["danh_muc"]
        out.append(
            "INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope, org_unit_id, version,\n"
            "                               source, status, version_no)\n"
            "VALUES (%s, %s, %s, %s, %s, %s, 'DON_VI', %s, 1, 'TU_TAO', 'HIEU_LUC', 1)\nON CONFLICT (id) DO NOTHING;"
            % (q(u(dm["so"])), q(tid), q(HE_THONG_V7), q(HE_THONG_V7), q(dm["code"]), q(dm["ten"]), q(u(dm["dv"])))
        )
        dong = []
        for so, code, ten, sp, dvt, kho, diem, cl, mc, nhom, ngay in dm["muc"]:
            dong.append("  (%s, %s, %s, %s, %s, %d, %s, %s, %s, %s, %d)" % (
                q(u(so)), q(code), q(ten), q(sp), q(dvt), kho, diem, q(cl), q(mc), q(nhom), ngay))
        out.append(
            "INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id, code, name,\n"
            "                               product_name, unit, difficulty, standard_score, quality_standard,\n"
            "                               evidence_requirement, task_group, standard_days, status)\n"
            "SELECT i.id::uuid, %s, %s, %s, %s, i.code, i.name, i.product, i.unit, i.difficulty, i.score, i.quality,\n"
            "       i.evidence, i.task_group, i.days, 'AP_DUNG'\n"
            "FROM (VALUES\n%s\n) AS i(id, code, name, product, unit, difficulty, score, quality, evidence, task_group, days)\n"
            "ON CONFLICT (id) DO NOTHING;"
            % (q(tid), q(HE_THONG_V7), q(HE_THONG_V7), q(u(dm["so"])), ",\n".join(dong))
        )
        out.append("")
    out.append("COMMIT;")
    return "\n".join(out) + "\n"


def tach_ten(ho_ten):
    phan = ho_ten.split()
    return " ".join(phan[:-1]), phan[-1]


def sinh_keycloak():
    users = []
    for t in D.TENANT:
        for p in t["can_bo"]:
            ho, ten = tach_ten(p["ten"])
            users.append({
                "id": D.uid(t["tien_to"], p["so"]),
                "username": p["tk"],
                "enabled": True,
                "firstName": ten,
                "lastName": ho,
                "email": p["tk"] + "@mission.local",
                "emailVerified": True,
                "attributes": {"tenant": [D.uid(t["tien_to"], 1)], "unit_id": [D.uid(t["tien_to"], p["dv"])]},
                "credentials": [
                    {"type": "password", "value": "${KC_TEST_USER_PASSWORD}", "temporary": False},
                    {"type": "otp", "userLabel": "dev (đa tenant)",
                     "secretData": "{\"value\":\"${KC_TEST_OTP_SECRET}\"}",
                     "credentialData": "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,"
                                       "\"algorithm\":\"HmacSHA1\"}"},
                ],
                "realmRoles": p["vai_tro"],
            })
    return json.dumps({"users": users}, ensure_ascii=False, indent=2) + "\n"


def sinh_md():
    out = [
        "<!-- SINH TỰ ĐỘNG bởi data/seed/da-tenant/sinh_seed.py, không sửa tay. -->",
        "# Tài khoản thử đa tenant",
        "",
        "Mọi tài khoản dùng chung mật khẩu thử và mã OTP thử của realm `mission` trên môi trường dev (giá trị nằm trong "
        "`infra/dev/.env`, không ghi ở đây). Tên người và đơn vị là hư cấu.",
        "",
    ]
    for t in D.TENANT:
        dv = {u[0]: u[2] for u in t["don_vi"]}
        cd = {c[0]: c[2] for c in t["chuc_danh"]}
        out += ["## %s – %s" % (t["ten_ngan"], t["ten"]), "", t["mo_ta"], "",
                "| Tài khoản | Họ và tên | Chức danh, đơn vị | Vai trò | Dùng để thử |", "|---|---|---|---|---|"]
        for p in t["can_bo"]:
            vt = ", ".join(D.VAI_TRO_NHAN[r] for r in p["vai_tro"])
            cv = "%s, %s" % (cd[p["cd"]], dv[p["dv"]])
            for dvk, cdk, _ in p.get("kiem_nhiem", []):
                cv += "; kiêm %s, %s" % (cd[cdk].lower(), dv[dvk])
            out.append("| `%s` | %s | %s | %s | %s |" % (p["tk"], p["ten"], cv, vt, p.get("ghi_chu", "")))
        out.append("")
    return "\n".join(out)


def main():
    loi = kiem_du_lieu()
    if loi:
        print("Dữ liệu nguồn còn lỗi:\n  " + "\n  ".join(loi), file=sys.stderr)
        return 1
    tep = {SQL: sinh_sql(), KC: sinh_keycloak(), MD: sinh_md()}
    if "--kiem" in sys.argv[1:]:
        lech = [str(p.relative_to(ROOT)) for p, nd in tep.items() if not p.exists() or p.read_text(encoding="utf-8") != nd]
        if lech:
            print("Tệp sinh lệch dữ liệu nguồn, chạy lại sinh_seed.py: " + ", ".join(lech), file=sys.stderr)
            return 1
        print("Tệp sinh khớp dữ liệu nguồn.")
        return 0
    for p, nd in tep.items():
        p.write_text(nd, encoding="utf-8")
        print("Đã sinh " + str(p.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

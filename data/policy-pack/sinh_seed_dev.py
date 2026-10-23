"""Sinh dữ liệu seed dev của Policy Pack luồng Đảng (khoi-dang/quy-dinh-moi) từ chính thư mục gói giả lập.

Hợp đồng policies.yaml chưa có API tạo gói (chỉ tạo phiên bản cho gói đã có, `importPack` của mission-core chưa
có nơi gọi), nên tenant dev mới không có gói nào để soạn, kiểm thử và phát hành (QT-15, SCR-1501..SCR-1503).
Tệp SQL sinh ra làm đúng việc của `importPack` cho tenant thử: tạo gói và phiên bản 2.1.0 ở trạng thái bản
nháp với manifest là `policy.yaml` đổi sang JSON và quy tắc dựng từ `definitions` (như ManifestRuleImport), rồi thêm ba bảng quyết định từ `dmn/` (rules_json theo dạng
mà PolicyDecisionTableService lưu) và bộ ca mẫu `tests/bo-ca-mau-gia-lap.yaml` (ghép giá trị mặc định như
GoldenSuiteYaml) ở trạng thái đã duyệt, không ký số. Chỉ dùng cho dev, không dùng cho môi trường thật.

Cách chạy (từ gốc repo, cần PyYAML), rồi commit tệp SQL:
    python3 -I data/policy-pack/sinh_seed_dev.py > infra/dev/postgres/seed/policy-goi-mau.sql
"""
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

import yaml

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GOI = os.path.join(REPO, "data/policy-pack/khoi-dang/quy-dinh-moi")
TENANT = "d0000000-0000-4000-8000-000000000001"
ACTOR = "00000000-0000-7000-8000-000000000000"
# Người duyệt ca mẫu khác người soạn (BR-15-08), tài khoản kỹ thuật của seed.
APPROVER = "00000000-0000-7000-8000-000000000001"
PACK_ID = "d0000000-0000-4000-8000-0000000c0001"
VERSION_ID = "d0000000-0000-4000-8000-0000000c0021"
NS = {"d": "https://www.omg.org/spec/DMN/20211108/MODEL/"}
NGUON_CA = {"KHACH_HANG", "BA", "VAN_BAN"}
THAM_SO = re.compile(r"tham_so\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")


def q(v):
    """Chuỗi SQL dạng dollar-quote để không phải thoát dấu nháy trong JSON và XML."""
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, default=str)
    tag = "$s$"
    assert tag not in s
    return f"{tag}{s}{tag}"


def uid(n):
    return f"d0000000-0000-4000-8000-{n:012x}"


def bang(meta):
    """Một dòng decision_table từ tệp DMN: cột đầu vào, các dòng (ô '-' là bất kỳ), mẫu câu hạ mức."""
    path = os.path.join(GOI, meta["dmn"])
    xml = open(path, encoding="utf-8").read()
    root = ET.fromstring(xml)
    dt = root.find(".//d:decisionTable", NS)
    cols = []
    for i in dt.findall("d:input", NS):
        e = i.find("d:inputExpression", NS)
        cols.append({"name": e.find("d:text", NS).text.strip(), "type": e.get("typeRef"), "label": i.get("label")})
    notes = [a.get("name") for a in dt.findall("d:annotation", NS)]
    rows, templates = [], {}
    for r in dt.findall("d:rule", NS):
        cells = [x.find("d:text", NS).text or "" for x in r.findall("d:inputEntry", NS)]
        out = (r.find("d:outputEntry/d:text", NS).text or "").strip().strip('"')
        ann = dict(zip(notes, [(x.find("d:text", NS).text or "").strip() for x in r.findall("d:annotationEntry", NS)]))
        inputs = {c["name"]: v.strip() for c, v in zip(cols, cells) if v.strip() and v.strip() != "-"}
        rows.append(
            {
                "inputs": inputs,
                "ratingLevel": out,
                "clause": ann.get("dieu_khoan", ""),
                "explanation": ann.get("giai_thich", ""),
            }
        )
        mau = ann.get("mau_ha_muc", "-")
        if mau and mau != "-":
            templates.setdefault("ha_muc", mau)
    rules = {"inputs": cols, "rules": rows}
    return {
        "key": meta["table_key"],
        "xml": xml,
        "hit": dt.get("hitPolicy", meta.get("hit_policy", "FIRST")),
        "hash": hashlib.sha256(xml.encode("utf-8")).hexdigest(),
        "legal": meta.get("legal_ref", ""),
        "rules": rules,
        "templates": templates,
    }


def quy_tac(manifest):
    """Quy tắc của phiên bản dựng từ definitions như ManifestRuleImport của mission-core khi nhập gói."""
    params = manifest.get("params") or {}
    out, seen = [], set()
    nhom_xong = not isinstance(params.get("nhom"), dict)
    for block in ("definitions", "definitions_nam"):
        for pham_vi, defs in (manifest.get(block) or {}).items():
            for key, d in (defs or {}).items():
                if not isinstance(d, dict) or key in seen:
                    continue
                seen.add(key)
                expr = str(d.get("expression")).strip()
                used = {}
                for m in THAM_SO.finditer(expr):
                    path = m.group(1)
                    while path:
                        if isinstance(params.get(path), dict):
                            used.setdefault(path, params[path].get("mac_dinh"))
                            break
                        path = path.rpartition(".")[0]
                # Danh sách nhóm đặt ở quy tắc đầu tiên của phạm vi nhom đọc g.diem_toi_da, như ManifestRuleImport.
                if not nhom_xong and pham_vi == "nhom" and "g.diem_toi_da" in expr:
                    used["nhom"] = params["nhom"].get("mac_dinh")
                    nhom_xong = True
                out.append(
                    {
                        "key": key,
                        "expr": expr,
                        "type": str(d.get("result_type")),
                        "rounding": d.get("rounding") if isinstance(d.get("rounding"), dict) else {},
                        "legal": str(d.get("legal_ref") or "")[:200],
                        "order": int(d.get("eval_order") or 0),
                        "params": used,
                        "overridable": d.get("overridable") is True,
                    }
                )
    return out


def ca_mau(doc):
    """Ghép mặc định theo `loai` và cho từng phần tử `nhiem_vu`, cùng dạng {loai, tham_so, dau_vao}."""
    md = doc.get("mac_dinh") or {}
    out = []
    for ca in doc["ca"]:
        loai = ca.get("loai")
        dv = dict(md.get(loai) or {}) if loai else {}
        dv.update(ca.get("dau_vao") or {})
        if isinstance(md.get("nhiem_vu"), dict) and isinstance(dv.get("nhiem_vu"), list):
            dv["nhiem_vu"] = [dict(md["nhiem_vu"], **(nv or {})) for nv in dv["nhiem_vu"]]
        if loai is None and "tham_so" not in ca:
            inp = dv
        else:
            inp = {}
            if loai is not None:
                inp["loai"] = loai
            if "tham_so" in ca:
                inp["tham_so"] = ca["tham_so"]
            inp["dau_vao"] = dv
        nguon = ca.get("source") if ca.get("source") in NGUON_CA else "BA"
        out.append((str(ca["id"]), str(ca.get("mo_ta", "")), inp, ca.get("mong_doi") or {}, ca.get("legal_ref"), nguon))
    return out


def main():
    manifest = yaml.safe_load(open(os.path.join(GOI, "policy.yaml"), encoding="utf-8"))
    meta = manifest["metadata"]
    can_cu = "; ".join(str(x) for x in meta.get("can_cu_phap_ly") or [])
    tables = [bang(t) for t in manifest["classification"]["bang_quyet_dinh"]]
    suite = yaml.safe_load(open(os.path.join(GOI, manifest["tests"]["bo_ca_mau"]), encoding="utf-8"))
    cases = ca_mau(suite)
    w = sys.stdout.write
    w(
        "-- SINH TỰ ĐỘNG bởi data/policy-pack/sinh_seed_dev.py từ data/policy-pack/khoi-dang/quy-dinh-moi, không sửa tay.\n"
        "-- Policy Pack giả lập luồng Đảng cho tenant thử (MOD-05, QT-15, SCR-1501..SCR-1503): hợp đồng chưa có API\n"
        "-- tạo gói nên seed làm việc của importPack (gói + phiên bản nháp 2.1.0, manifest từ policy.yaml, quy tắc từ\n"
        "-- definitions), thêm ba bảng quyết định từ dmn/ và bộ ca mẫu đã duyệt, chưa ký số. Chỉ dữ liệu dev. Chạy\n"
        "-- lại không sinh bản ghi trùng và không đụng phiên bản đã đóng băng (chỉ chèn dòng còn thiếu). Nạp bởi\n"
        "-- scripts/seed-org.sh.\n"
        "BEGIN;\n"
    )
    w(
        "INSERT INTO policy.policy_pack (id, tenant_id, created_by, updated_by, code, name, sector, flow, origin, legal_basis)\n"
        f"SELECT '{PACK_ID}', '{TENANT}', '{ACTOR}', '{ACTOR}', {q(meta['id'])}, {q(meta['ten'])}, 'KHOI_DANG',\n"
        f"       {q(meta['luong'])}, 'GOC_TOAN_CUC', {q(can_cu)}\n"
        f"WHERE NOT EXISTS (SELECT 1 FROM policy.policy_pack WHERE tenant_id = '{TENANT}' AND code = {q(meta['id'])});\n\n"
    )
    pack = f"(SELECT id FROM policy.policy_pack WHERE tenant_id = '{TENANT}' AND code = {q(meta['id'])})"
    w(
        "INSERT INTO policy.policy_pack_version (id, tenant_id, created_by, updated_by, policy_pack_id, version, status,\n"
        "                                        manifest, change_note)\n"
        f"SELECT '{VERSION_ID}', '{TENANT}', '{ACTOR}', '{ACTOR}', {pack}, {q(meta['version'])}, 'NHAP',\n"
        f"       {q(manifest)}::jsonb, {q('Nhập gói ' + meta['id'] + ' phiên bản ' + meta['version'] + ' (dữ liệu dev)')}\n"
        f"WHERE NOT EXISTS (SELECT 1 FROM policy.policy_pack_version WHERE policy_pack_id = {pack}\n"
        f"                  AND version = {q(meta['version'])});\n\n"
    )
    ver = (
        f"(SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = {pack} AND version = {q(meta['version'])})"
    )
    for i, r in enumerate(quy_tac(manifest)):
        w(
            "INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,\n"
            "                                result_type, rounding, legal_ref, eval_order, params_json, overridable)\n"
            f"SELECT '{uid(0xC2001 + i)}', '{TENANT}', '{ACTOR}', '{ACTOR}', v.id, {q(r['key'])}, {q(r['expr'])},\n"
            f"       {q(r['type'])}, {q(r['rounding'])}::jsonb, {q(r['legal'])}, {r['order']}, {q(r['params'])}::jsonb, "
            f"{'true' if r['overridable'] else 'false'}\n"
            f"FROM (SELECT {ver} AS id) v\n"
            f"WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id\n"
            f"                                       AND x.rule_key = {q(r['key'])})\n"
            "  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';\n\n"
        )
    for i, t in enumerate(tables):
        w(
            "INSERT INTO policy.decision_table (id, tenant_id, created_by, updated_by, policy_pack_version_id, table_key,\n"
            "                                   dmn_xml, hit_policy, dmn_hash, legal_ref, rules_json, explanation_templates_json)\n"
            f"SELECT '{uid(0xC0101 + i)}', '{TENANT}', '{ACTOR}', '{ACTOR}', v.id, {q(t['key'])},\n"
            f"       {q(t['xml'])},\n"
            f"       {q(t['hit'])}, decode('{t['hash']}', 'hex'), {q(t['legal'])}, {q(t['rules'])}::jsonb, {q(t['templates'])}::jsonb\n"
            f"FROM (SELECT {ver} AS id) v\n"
            f"WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.decision_table d WHERE d.policy_pack_version_id = v.id\n"
            f"                                       AND d.table_key = {q(t['key'])})\n"
            "  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';\n\n"
        )
    w(
        "INSERT INTO policy.golden_test_case (id, tenant_id, created_by, updated_by, policy_pack_id, case_code, description,\n"
        "                                     input, expected, legal_ref, source, status, approved_by, approved_at)\n"
        f"SELECT c.id::uuid, '{TENANT}', '{ACTOR}', '{ACTOR}', {pack}, c.code, c.mo_ta, c.dau_vao::jsonb, c.mong_doi::jsonb,\n"
        f"       c.legal_ref, c.nguon, 'DA_DUYET', '{APPROVER}', now()\n"
        "FROM (VALUES\n"
    )
    rows = []
    for i, (code, mo_ta, inp, exp, legal, nguon) in enumerate(cases):
        rows.append(
            f"  ('{uid(0xC1001 + i)}', {q(code)}, {q(mo_ta)}, {q(inp)}, {q(exp)}, "
            f"{q(legal) if legal else 'NULL'}, '{nguon}')"
        )
    w(",\n".join(rows))
    w(
        "\n) AS c(id, code, mo_ta, dau_vao, mong_doi, legal_ref, nguon)\n"
        f"WHERE NOT EXISTS (SELECT 1 FROM policy.golden_test_case g WHERE g.policy_pack_id = {pack} AND g.case_code = c.code);\n"
        "COMMIT;\n"
    )


if __name__ == "__main__":
    main()

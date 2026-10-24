-- SINH TỰ ĐỘNG bởi data/policy-pack/sinh_seed_dev.py từ data/policy-pack/khoi-chinh-quyen/nd335, không sửa tay.
-- Policy Pack giả lập luồng NHA_NUOC cho tenant thử e3000000-0000-4000-8000-000000000001 (MOD-05, QT-15, SCR-1501..SCR-1503): hợp đồng chưa có API
-- tạo gói nên seed làm việc của importPack (gói + phiên bản nháp 1.4.0, manifest từ policy.yaml, quy tắc từ
-- definitions), thêm ba bảng quyết định từ dmn/ và bộ ca mẫu đã duyệt, chưa ký số. Chỉ dữ liệu dev. Chạy
-- lại không sinh bản ghi trùng và không đụng phiên bản đã đóng băng (chỉ chèn dòng còn thiếu). Nạp bởi
-- scripts/seed-org.sh.
BEGIN;
INSERT INTO policy.policy_pack (id, tenant_id, created_by, updated_by, code, name, sector, flow, origin, legal_basis)
SELECT 'e3000000-0000-4000-8000-0000000c0001', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', $s$khoi-chinh-quyen/nd335$s$, $s$Bộ quy định đánh giá cán bộ, công chức, viên chức theo Nghị định số 335/2025/NĐ-CP$s$, 'DOANH_NGHIEP',
       $s$NHA_NUOC$s$, 'GOC_TOAN_CUC', $s$Nghị định số 335/2025/NĐ-CP về đánh giá, xếp loại chất lượng cán bộ, công chức, viên chức; Nghị định số 233/2026/NĐ-CP về đánh giá viên chức trong đơn vị sự nghiệp công lập; Luật Cán bộ, công chức (sửa đổi) năm 2025$s$
WHERE NOT EXISTS (SELECT 1 FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$);

INSERT INTO policy.policy_pack_version (id, tenant_id, created_by, updated_by, policy_pack_id, version, status,
                                        manifest, change_note)
SELECT 'e3000000-0000-4000-8000-0000000c0021', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$), $s$1.4.0$s$, 'NHAP',
       $s${"apiVersion": "policy.okrkpi.vn/v1", "kind": "PolicyPack", "metadata": {"id": "khoi-chinh-quyen/nd335", "version": "1.4.0", "trang_thai": "NHAP", "luong": "NHA_NUOC", "ten": "Bộ quy định đánh giá cán bộ, công chức, viên chức theo Nghị định số 335/2025/NĐ-CP", "ap_dung_cho": {"tenant_tags": ["khoi-chinh-quyen"], "doi_tuong": ["CAN_BO", "CONG_CHUC", "VIEN_CHUC"]}, "hieu_luc": {"tu_ky": "2027-Q1", "den_ky": null}, "can_cu_phap_ly": ["Nghị định số 335/2025/NĐ-CP về đánh giá, xếp loại chất lượng cán bộ, công chức, viên chức", "Nghị định số 233/2026/NĐ-CP về đánh giá viên chức trong đơn vị sự nghiệp công lập", "Luật Cán bộ, công chức (sửa đổi) năm 2025"], "phe_duyet": {"ky_so": [], "nguoi_soan": null, "nguoi_duyet": null}, "ghi_chu_ban_nhap": "Bản nháp chờ cơ quan có thẩm quyền xác nhận cơ cấu điểm, cách tính tiêu chí chung và căn cứ.", "gia_lap": true, "ghi_chu_gia_lap": "Bản giả lập soạn theo Nghị định 335/2025/NĐ-CP, chưa phải bản chính thức. Khi cơ quan có thẩm quyền ban hành nội dung chính thức, phát hành phiên bản mới của gói này với gia_lap: false; không sửa mã."}, "types": {"NhiemVu": {"ma": {"type": "string", "source": "task.aggregate.scored_tasks.code"}, "nhom": {"type": "string", "source": "task.aggregate.scored_tasks.task_group", "enum": ["THUONG_XUYEN", "THEN_CHOT"]}, "ke_hoach": {"type": "int", "source": "task.aggregate.planned_count", "min": 0}, "so_luong_duyet": {"type": "int", "source": "task.aggregate.approved_count", "min": 0}, "muc_chat_luong": {"type": "double", "source": "product.aggregate.quality_avg", "enum_scale": [1, 0.75, 0.5, 0.25, 0]}, "so_lan_chinh_sua": {"type": "int", "source": "product.aggregate.revision_count", "min": 0}, "ngay_lam_viec_cham_chu_quan": {"type": "int", "source": "task.aggregate.late_workdays", "min": 0}, "ngay_lich_cham_chu_quan": {"type": "int", "source": "task.aggregate.late_calendar_days", "min": 0}, "diem_chuan": {"type": "double", "source": "task.aggregate.catalog_base_score", "min": 0, "max": 100}, "khong_hoan_thanh_chu_quan": {"type": "bool", "source": "task.aggregate.key_missed_subjective"}}, "KetQuaNhiemVu": {"ma": {"type": "string"}, "nhom": {"type": "string"}, "diem_chuan": {"type": "double"}, "kpi_nhiem_vu": {"type": "double"}, "vuot_muc_tieu": {"type": "bool"}}, "Nhom": {"ma": {"type": "string"}, "diem_toi_da": {"type": "int"}, "nhom_nhiem_vu": {"type": "list<string>"}}, "KetQuaNhomDauVao": {"ma": {"type": "string"}, "diem_toi_da": {"type": "int"}, "ds_kpi": {"type": "list<double>"}}, "KetQuaNhom": {"ma": {"type": "string"}, "diem_toi_da": {"type": "int"}, "kpi_nhom": {"type": "double"}, "diem_nhom": {"type": "double"}}}, "inputs": {"nhiem_vu": {"type": "list<NhiemVu>", "source": "task.aggregate.scored_tasks"}, "ty_le_dap_ung_tieu_chi_chung": {"type": "double", "source": "appraisal.aggregate.general_criteria_ratio", "min": 0, "max": 1}}, "ngu_canh": {"nv": {"type": "NhiemVu", "pham_vi": "nhiem_vu"}, "g": {"type": "KetQuaNhomDauVao", "pham_vi": "nhom"}, "ket_qua_nhiem_vu": {"type": "list<KetQuaNhiemVu>", "pham_vi": ["ho_so"]}, "ket_qua_nhom": {"type": "list<KetQuaNhom>", "pham_vi": ["ho_so"]}}, "params": {"tru_tien_do": {"type": "double", "mac_dinh": 0.05, "mien": {"min": 0.0, "max": 0.2}, "chu_so": 2, "overridable": false, "legal_ref": "NĐ 335/2025; PL 3 mục 3"}, "loai_ngay": {"type": "string", "mac_dinh": "NGAY_LAM_VIEC", "mien": {"enum": ["NGAY_LAM_VIEC", "NGAY_LICH"]}, "overridable": false, "legal_ref": "PL A4; BR-15-03"}, "cach_tinh_chat_luong": {"type": "string", "mac_dinh": "NAM_MUC_PL3", "mien": {"enum": ["NAM_MUC_PL3", "TRU_25_MOI_LAN"]}, "overridable": false, "legal_ref": "PL A4; BR-15-02"}, "tru_moi_lan_chinh_sua": {"type": "double", "mac_dinh": 0.25, "mien": {"min": 0.0, "max": 1.0}, "overridable": false, "legal_ref": "Bảng chấm điểm; BR-15-02"}, "lam_tron": {"type": "map", "mac_dinh": {"digits": 1, "mode": "NUA_LEN"}, "overridable": false, "legal_ref": "BR-09-03; SCR-1501"}, "diem_toi_da_tieu_chi_chung": {"type": "int", "mac_dinh": 30, "mien": {"min": 30, "max": 30}, "overridable": false, "legal_ref": "NĐ 335/2025; PL A5; BR-09-11; BR-15-05"}, "nhom": {"type": "list<Nhom>", "mac_dinh": [{"ma": "KET_QUA_NHIEM_VU", "diem_toi_da": 70, "nhom_nhiem_vu": ["THUONG_XUYEN", "THEN_CHOT"]}], "rang_buoc": {"tong_diem_toi_da": 70, "cong_tieu_chi_chung": 100}, "overridable": true, "legal_ref": "NĐ 335/2025; PL A5; BR-09-11; BR-15-05"}, "htxs.cach_dem_vuot": {"type": "string", "mac_dinh": "THEO_SO_NHIEM_VU", "mien": {"enum": ["THEO_SO_NHIEM_VU", "THEO_DIEM"]}, "overridable": false, "legal_ref": "PL A3; BR-09-04"}, "phan_bo.toi_da_htxsnv": {"type": "double", "mac_dinh": 0.2, "mien": {"min": 0.0, "max": 1.0}, "overridable": true, "legal_ref": "BR-09-17 (áp cho luồng Nhà nước là suy luận)"}, "tham_dinh.so_lan_tra_lai_toi_da": {"type": "int", "mac_dinh": 2, "mien": {"min": 0, "max": 5}, "overridable": true, "legal_ref": "QT-09"}, "plan_approval_deadline_rule": {"type": "string", "mac_dinh": "NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2", "mien": {"enum": ["NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2"]}, "overridable": false, "legal_ref": "TL-02a QT-04"}, "plan_max_adjustments_per_quarter": {"type": "int", "mac_dinh": 2, "mien": {"min": 0, "max": 6}, "overridable": true, "legal_ref": "QT-04 bước 9"}, "extension_max_per_task_quarter": {"type": "int", "mac_dinh": 2, "mien": {"min": 0, "max": 6}, "overridable": true, "legal_ref": "BR-06-06"}, "extension_escalation_days": {"type": "int", "mac_dinh": 10, "mien": {"min": 1, "max": 30}, "don_vi": "NGAY_LAM_VIEC", "overridable": true, "legal_ref": "BR-06-06"}, "acceptance_sla_days": {"type": "int", "mac_dinh": 5, "mien": {"min": 1, "max": 15}, "don_vi": "NGAY_LAM_VIEC", "overridable": true, "legal_ref": "BR-07-12"}, "kr_risk_low_streak": {"type": "int", "mac_dinh": 2, "mien": {"min": 1, "max": 6}, "overridable": true, "legal_ref": "QT-03"}, "similarity_threshold": {"type": "double", "mac_dinh": 0.7, "mien": {"min": 0.5, "max": 0.95}, "overridable": false, "legal_ref": "BR-05-06"}, "mfa_stepup_window_minutes": {"type": "int", "mac_dinh": 15, "mien": {"min": 1, "max": 15}, "overridable": false, "legal_ref": "BR-07-11"}, "khieu_nai.thoi_han": {"type": "int", "mac_dinh": 10, "mien": {"min": 1, "max": 30}, "don_vi": "NGAY_LAM_VIEC", "overridable": false, "legal_ref": "BR-12-01"}, "difficulty_coefficients": {"type": "map<int,double>", "mac_dinh": {"4": 1.2}, "gia_dinh": true, "overridable": false, "legal_ref": "TL-02a QT-04 (giả định chờ cơ quan tổ chức xác nhận)"}}, "definitions": {"nhiem_vu": {"kpi_A": {"expression": "nv.ke_hoach == 0 ? 1.0 : math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; PL 3 mục 1", "eval_order": 10, "overridable": false}, "kpi_B": {"expression": "tham_so.cach_tinh_chat_luong == 'TRU_25_MOI_LAN' ? math.greatest(0.0, 1.0 - tham_so.tru_moi_lan_chinh_sua * double(nv.so_lan_chinh_sua)) : double(nv.muc_chat_luong)", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; PL 3 mục 2", "eval_order": 20, "overridable": false}, "ngay_cham": {"expression": "tham_so.loai_ngay == 'NGAY_LICH' ? nv.ngay_lich_cham_chu_quan : nv.ngay_lam_viec_cham_chu_quan", "result_type": "DECIMAL", "legal_ref": "PL 3 mục 3; BR-15-03", "eval_order": 30, "overridable": false}, "kpi_C": {"expression": "math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(ngay_cham))", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; PL 3 mục 3", "eval_order": 40, "overridable": false}, "kpi_nhiem_vu": {"expression": "(kpi_A + kpi_B + kpi_C) / 3.0", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; PL 3", "eval_order": 50, "overridable": false}, "vuot_muc_tieu": {"expression": "nv.ke_hoach > 0 && nv.so_luong_duyet > nv.ke_hoach", "result_type": "BOOLEAN", "legal_ref": "PL A3; BR-09-04", "eval_order": 60, "overridable": false}}, "nhom": {"kpi_nhom": {"expression": "g.ds_kpi.size() == 0 ? 0.0 : sum(g.ds_kpi) / double(g.ds_kpi.size())", "result_type": "DECIMAL", "legal_ref": "PL A5; BR-09-03", "eval_order": 70, "overridable": false}, "diem_nhom": {"expression": "kpi_nhom * double(g.diem_toi_da)", "result_type": "DECIMAL", "legal_ref": "PL A5; BR-09-11", "eval_order": 80, "overridable": false}}, "ho_so": {"diem_ket_qua": {"expression": "sum(ket_qua_nhom.map(k, k.diem_nhom))", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; BR-09-11", "eval_order": 90, "overridable": false}, "diem_tieu_chi_chung": {"expression": "double(tham_so.diem_toi_da_tieu_chi_chung) * math.least(math.greatest(ty_le_dap_ung_tieu_chi_chung, 0.0), 1.0)", "result_type": "DECIMAL", "legal_ref": "NĐ 335/2025; PL A5; BR-09-11 (cách quy đổi là suy luận)", "eval_order": 100, "overridable": false}, "diem": {"expression": "diem_tieu_chi_chung + diem_ket_qua", "result_type": "DECIMAL", "rounding": {"digits": 1, "mode": "NUA_LEN"}, "legal_ref": "NĐ 335/2025; BR-09-11; BR-15-05", "eval_order": 110, "overridable": false}, "ty_le_vuot": {"expression": "ket_qua_nhiem_vu.size() == 0 ? 0.0 : (tham_so.htxs.cach_dem_vuot == 'THEO_DIEM'\n  ? (sum(ket_qua_nhiem_vu.map(k, k.diem_chuan)) == 0.0 ? 0.0\n     : sum(ket_qua_nhiem_vu.filter(k, k.vuot_muc_tieu).map(k, k.diem_chuan)) / sum(ket_qua_nhiem_vu.map(k, k.diem_chuan)))\n  : double(ket_qua_nhiem_vu.filter(k, k.vuot_muc_tieu).size()) / double(ket_qua_nhiem_vu.size()))", "result_type": "DECIMAL", "rounding": {"digits": 4, "mode": "NUA_LEN"}, "legal_ref": "PL A3; BR-09-04 (ghi nhận trong snapshot, không dùng trong bảng xếp loại luồng Nhà nước)", "eval_order": 120, "overridable": false}}}, "rounding": {"diem": {"digits": 1, "mode": "NUA_LEN"}}, "classification": {"thu_tu": ["HTXSNV", "HTTNV", "HTNV", "KHTNV"], "bang_quyet_dinh": [{"table_key": "xep_loai", "dmn": "dmn/xep-loai-nha-nuoc.dmn", "hit_policy": "FIRST", "legal_ref": "NĐ 335/2025; PL A5; BR-09-11"}], "nhan_hien_thi": {"muc_xep_loai": {"HTXSNV": "Hoàn thành xuất sắc nhiệm vụ", "HTTNV": "Hoàn thành tốt nhiệm vụ", "HTNV": "Hoàn thành nhiệm vụ", "KHTNV": "Không hoàn thành nhiệm vụ"}}, "mau_cau": {}}, "quotas": {"HTXSNV": {"ty_le_toi_da": 0.2, "tham_so": "phan_bo.toi_da_htxsnv", "pham_vi": "DON_VI_CAP_TREN", "hanh_dong": "DANH_DAU_VUOT", "legal_ref": "BR-09-17 (suy luận áp cho luồng Nhà nước)"}}, "ket_qua": {"snapshot_rieng": true, "luu": ["diem_tieu_chi_chung", "diem_ket_qua", "diem", "ty_le_vuot", "xep_loai"]}, "task_lifecycle": {"tham_chieu": "khoi-dang/quy-dinh-moi@2.1.0#task_lifecycle"}, "workflow_bindings": {"thu_muc": "workflow-bindings/"}, "tests": {"bo_ca_mau": "tests/bo-ca-mau-gia-lap.yaml", "yeu_cau_dat": 1.0}, "templates": {"thu_muc": "templates/"}}$s$::jsonb, $s$Nhập gói khoi-chinh-quyen/nd335 phiên bản 1.4.0 (dữ liệu dev)$s$
WHERE NOT EXISTS (SELECT 1 FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$)
                  AND version = $s$1.4.0$s$);

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2001', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$kpi_A$s$, $s$nv.ke_hoach == 0 ? 1.0 : math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; PL 3 mục 1$s$, 10, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$kpi_A$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2002', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$kpi_B$s$, $s$tham_so.cach_tinh_chat_luong == 'TRU_25_MOI_LAN' ? math.greatest(0.0, 1.0 - tham_so.tru_moi_lan_chinh_sua * double(nv.so_lan_chinh_sua)) : double(nv.muc_chat_luong)$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; PL 3 mục 2$s$, 20, $s${"cach_tinh_chat_luong": "NAM_MUC_PL3", "tru_moi_lan_chinh_sua": 0.25}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$kpi_B$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2003', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$ngay_cham$s$, $s$tham_so.loai_ngay == 'NGAY_LICH' ? nv.ngay_lich_cham_chu_quan : nv.ngay_lam_viec_cham_chu_quan$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$PL 3 mục 3; BR-15-03$s$, 30, $s${"loai_ngay": "NGAY_LAM_VIEC"}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$ngay_cham$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2004', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$kpi_C$s$, $s$math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(ngay_cham))$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; PL 3 mục 3$s$, 40, $s${"tru_tien_do": 0.05}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$kpi_C$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2005', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$kpi_nhiem_vu$s$, $s$(kpi_A + kpi_B + kpi_C) / 3.0$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; PL 3$s$, 50, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$kpi_nhiem_vu$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2006', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$vuot_muc_tieu$s$, $s$nv.ke_hoach > 0 && nv.so_luong_duyet > nv.ke_hoach$s$,
       $s$BOOLEAN$s$, $s${}$s$::jsonb, $s$PL A3; BR-09-04$s$, 60, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$vuot_muc_tieu$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2007', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$kpi_nhom$s$, $s$g.ds_kpi.size() == 0 ? 0.0 : sum(g.ds_kpi) / double(g.ds_kpi.size())$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$PL A5; BR-09-03$s$, 70, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$kpi_nhom$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2008', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$diem_nhom$s$, $s$kpi_nhom * double(g.diem_toi_da)$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$PL A5; BR-09-11$s$, 80, $s${"nhom": [{"ma": "KET_QUA_NHIEM_VU", "diem_toi_da": 70, "nhom_nhiem_vu": ["THUONG_XUYEN", "THEN_CHOT"]}]}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$diem_nhom$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c2009', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$diem_ket_qua$s$, $s$sum(ket_qua_nhom.map(k, k.diem_nhom))$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; BR-09-11$s$, 90, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$diem_ket_qua$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c200a', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$diem_tieu_chi_chung$s$, $s$double(tham_so.diem_toi_da_tieu_chi_chung) * math.least(math.greatest(ty_le_dap_ung_tieu_chi_chung, 0.0), 1.0)$s$,
       $s$DECIMAL$s$, $s${}$s$::jsonb, $s$NĐ 335/2025; PL A5; BR-09-11 (cách quy đổi là suy luận)$s$, 100, $s${"diem_toi_da_tieu_chi_chung": 30}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$diem_tieu_chi_chung$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c200b', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$diem$s$, $s$diem_tieu_chi_chung + diem_ket_qua$s$,
       $s$DECIMAL$s$, $s${"digits": 1, "mode": "NUA_LEN"}$s$::jsonb, $s$NĐ 335/2025; BR-09-11; BR-15-05$s$, 110, $s${}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$diem$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.policy_rule (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key, expression,
                                result_type, rounding, legal_ref, eval_order, params_json, overridable)
SELECT 'e3000000-0000-4000-8000-0000000c200c', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$ty_le_vuot$s$, $s$ket_qua_nhiem_vu.size() == 0 ? 0.0 : (tham_so.htxs.cach_dem_vuot == 'THEO_DIEM'
  ? (sum(ket_qua_nhiem_vu.map(k, k.diem_chuan)) == 0.0 ? 0.0
     : sum(ket_qua_nhiem_vu.filter(k, k.vuot_muc_tieu).map(k, k.diem_chuan)) / sum(ket_qua_nhiem_vu.map(k, k.diem_chuan)))
  : double(ket_qua_nhiem_vu.filter(k, k.vuot_muc_tieu).size()) / double(ket_qua_nhiem_vu.size()))$s$,
       $s$DECIMAL$s$, $s${"digits": 4, "mode": "NUA_LEN"}$s$::jsonb, $s$PL A3; BR-09-04 (ghi nhận trong snapshot, không dùng trong bảng xếp loại luồng Nhà nước)$s$, 120, $s${"htxs.cach_dem_vuot": "THEO_SO_NHIEM_VU"}$s$::jsonb, false
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.policy_rule x WHERE x.policy_pack_version_id = v.id
                                       AND x.rule_key = $s$ty_le_vuot$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.decision_table (id, tenant_id, created_by, updated_by, policy_pack_version_id, table_key,
                                   dmn_xml, hit_policy, dmn_hash, legal_ref, rules_json, explanation_templates_json)
SELECT 'e3000000-0000-4000-8000-0000000c0101', 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', v.id, $s$xep_loai$s$,
       $s$<?xml version="1.0" encoding="UTF-8"?>
<!-- BẢN NHÁP chờ nghiệp vụ duyệt. BR-09-11; table_key xep_loai; gói khoi-chinh-quyen/nd335 -->
<definitions xmlns="https://www.omg.org/spec/DMN/20211108/MODEL/" id="xep-loai-nha-nuoc-dinh-nghia" name="Xếp loại chất lượng, luồng Nhà nước"
             namespace="https://policy.okrkpi.vn/khoi-chinh-quyen/nd335" expressionLanguage="https://www.omg.org/spec/DMN/20211108/FEEL/" typeLanguage="https://www.omg.org/spec/DMN/20211108/FEEL/">
  <decision id="xep-loai-nha-nuoc" name="Xếp loại chất lượng, luồng Nhà nước">
    <variable id="xep-loai-nha-nuoc-var" name="xep_loai" typeRef="string"/>
    <decisionTable id="xep-loai-nha-nuoc-bang" hitPolicy="FIRST" outputLabel="Mức xếp loại">
      <input id="xep-loai-nha-nuoc-in1" label="Tổng điểm (tiêu chí chung và kết quả nhiệm vụ)">
        <inputExpression id="xep-loai-nha-nuoc-in1-expr" typeRef="number"><text>diem</text></inputExpression>
      </input>
      <output id="xep-loai-nha-nuoc-out" name="xep_loai" typeRef="string">
        <outputValues id="xep-loai-nha-nuoc-out-values"><text>"HTXSNV","HTTNV","HTNV","KHTNV"</text></outputValues>
      </output>
      <annotation name="legal_ref"/>
      <annotation name="dieu_khoan"/>
      <annotation name="giai_thich"/>
      <annotation name="mau_ha_muc"/>
      <rule id="xep-loai-nha-nuoc-dong-1">
        <description>Từ 90 điểm</description>
        <inputEntry id="xep-loai-nha-nuoc-dong-1-in1"><text>&gt;= 90</text></inputEntry>
        <outputEntry id="xep-loai-nha-nuoc-dong-1-out"><text>"HTXSNV"</text></outputEntry>
        <annotationEntry><text>NĐ 335/2025; PL A5; BR-09-11</text></annotationEntry>
        <annotationEntry><text>Nghị định số 335/2025/NĐ-CP</text></annotationEntry>
        <annotationEntry><text>Tổng điểm {diem} từ 90 điểm trở lên nên xếp loại Hoàn thành xuất sắc nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}.</text></annotationEntry>
        <annotationEntry><text>-</text></annotationEntry>
      </rule>
      <rule id="xep-loai-nha-nuoc-dong-2">
        <description>Từ 70 đến dưới 90 điểm</description>
        <inputEntry id="xep-loai-nha-nuoc-dong-2-in1"><text>&gt;= 70</text></inputEntry>
        <outputEntry id="xep-loai-nha-nuoc-dong-2-out"><text>"HTTNV"</text></outputEntry>
        <annotationEntry><text>NĐ 335/2025; PL A5; BR-09-11</text></annotationEntry>
        <annotationEntry><text>Nghị định số 335/2025/NĐ-CP</text></annotationEntry>
        <annotationEntry><text>Tổng điểm {diem} từ 70 đến dưới 90 điểm nên xếp loại Hoàn thành tốt nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}.</text></annotationEntry>
        <annotationEntry><text>-</text></annotationEntry>
      </rule>
      <rule id="xep-loai-nha-nuoc-dong-3">
        <description>Từ 50 đến dưới 70 điểm</description>
        <inputEntry id="xep-loai-nha-nuoc-dong-3-in1"><text>&gt;= 50</text></inputEntry>
        <outputEntry id="xep-loai-nha-nuoc-dong-3-out"><text>"HTNV"</text></outputEntry>
        <annotationEntry><text>NĐ 335/2025; PL A5; BR-09-11</text></annotationEntry>
        <annotationEntry><text>Nghị định số 335/2025/NĐ-CP</text></annotationEntry>
        <annotationEntry><text>Tổng điểm {diem} từ 50 đến dưới 70 điểm nên xếp loại Hoàn thành nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}.</text></annotationEntry>
        <annotationEntry><text>-</text></annotationEntry>
      </rule>
      <rule id="xep-loai-nha-nuoc-dong-4">
        <description>Dưới 50 điểm</description>
        <inputEntry id="xep-loai-nha-nuoc-dong-4-in1"><text>-</text></inputEntry>
        <outputEntry id="xep-loai-nha-nuoc-dong-4-out"><text>"KHTNV"</text></outputEntry>
        <annotationEntry><text>NĐ 335/2025; PL A5; BR-09-11</text></annotationEntry>
        <annotationEntry><text>Nghị định số 335/2025/NĐ-CP</text></annotationEntry>
        <annotationEntry><text>Tổng điểm {diem} dưới 50 điểm nên xếp loại Không hoàn thành nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}.</text></annotationEntry>
        <annotationEntry><text>-</text></annotationEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
$s$,
       $s$FIRST$s$, decode('238e195a96c489024fa2e8ab94165a21192294efc24a58d2e3e6cc9e03dea389', 'hex'), $s$NĐ 335/2025; PL A5; BR-09-11$s$, $s${"inputs": [{"name": "diem", "type": "number", "label": "Tổng điểm (tiêu chí chung và kết quả nhiệm vụ)"}], "rules": [{"inputs": {"diem": ">= 90"}, "ratingLevel": "HTXSNV", "clause": "Nghị định số 335/2025/NĐ-CP", "explanation": "Tổng điểm {diem} từ 90 điểm trở lên nên xếp loại Hoàn thành xuất sắc nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}."}, {"inputs": {"diem": ">= 70"}, "ratingLevel": "HTTNV", "clause": "Nghị định số 335/2025/NĐ-CP", "explanation": "Tổng điểm {diem} từ 70 đến dưới 90 điểm nên xếp loại Hoàn thành tốt nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}."}, {"inputs": {"diem": ">= 50"}, "ratingLevel": "HTNV", "clause": "Nghị định số 335/2025/NĐ-CP", "explanation": "Tổng điểm {diem} từ 50 đến dưới 70 điểm nên xếp loại Hoàn thành nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}."}, {"inputs": {}, "ratingLevel": "KHTNV", "clause": "Nghị định số 335/2025/NĐ-CP", "explanation": "Tổng điểm {diem} dưới 50 điểm nên xếp loại Không hoàn thành nhiệm vụ theo {dieu_khoan}. Bộ quy định đánh giá {phien_ban}."}]}$s$::jsonb, $s${}$s$::jsonb
FROM (SELECT (SELECT id FROM policy.policy_pack_version WHERE policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND version = $s$1.4.0$s$) AS id) v
WHERE v.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM policy.decision_table d WHERE d.policy_pack_version_id = v.id
                                       AND d.table_key = $s$xep_loai$s$)
  AND (SELECT status FROM policy.policy_pack_version WHERE id = v.id) = 'NHAP';

INSERT INTO policy.golden_test_case (id, tenant_id, created_by, updated_by, policy_pack_id, case_code, description,
                                     input, expected, legal_ref, source, status, approved_by, approved_at)
SELECT c.id::uuid, 'e3000000-0000-4000-8000-000000000001', 'e3000000-0000-4000-8000-000000000306', 'e3000000-0000-4000-8000-000000000306', (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$), c.code, c.mo_ta, c.dau_vao::jsonb, c.mong_doi::jsonb,
       c.legal_ref, c.nguon, 'DA_DUYET', 'e3000000-0000-4000-8000-000000000302', now()
FROM (VALUES
  ('e3000000-0000-4000-8000-0000000c1001', $s$S-001$s$, $s$Đủ 30 điểm tiêu chí chung và 70 điểm kết quả$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 1.0, "nhiem_vu": [{"ke_hoach": 10, "so_luong_duyet": 10, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 0, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THUONG_XUYEN"}, {"ke_hoach": 10, "so_luong_duyet": 10, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 0, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 100.0, "dong_dmn": "xep-loai-nha-nuoc-dong-1", "xep_loai": "HTXSNV"}$s$, $s$NĐ 335/2025; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1002', $s$S-002$s$, $s$Điểm thô 89,95 làm tròn thành 90,0$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 0.665, "nhiem_vu": [{"ke_hoach": 10, "so_luong_duyet": 10, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 0, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THUONG_XUYEN"}, {"ke_hoach": 10, "so_luong_duyet": 10, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 0, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 90.0, "dong_dmn": "xep-loai-nha-nuoc-dong-1", "xep_loai": "HTXSNV"}$s$, $s$NĐ 335/2025; BR-09-03; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1003', $s$S-003$s$, $s$Điểm thô 69,95 làm tròn thành 70,0$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 0.675, "nhiem_vu": [{"ke_hoach": 25, "so_luong_duyet": 7, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 3, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 70.0, "dong_dmn": "xep-loai-nha-nuoc-dong-2", "xep_loai": "HTTNV"}$s$, $s$NĐ 335/2025; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1004', $s$S-004$s$, $s$Điểm 69,9 dưới ngưỡng 70$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 0.9, "nhiem_vu": [{"ke_hoach": 25, "so_luong_duyet": 1, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 4, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 69.9, "dong_dmn": "xep-loai-nha-nuoc-dong-3", "xep_loai": "HTNV"}$s$, $s$NĐ 335/2025; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1005', $s$S-005$s$, $s$Điểm thô 49,95 làm tròn thành 50,0$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 0.125, "nhiem_vu": [{"ke_hoach": 25, "so_luong_duyet": 2, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 2, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 50.0, "dong_dmn": "xep-loai-nha-nuoc-dong-3", "xep_loai": "HTNV"}$s$, $s$NĐ 335/2025; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1006', $s$S-006$s$, $s$Điểm 49,9 dưới ngưỡng 50$s$, $s${"loai": "NHA_NUOC", "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 0.35, "nhiem_vu": [{"ke_hoach": 25, "so_luong_duyet": 1, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 0, "ngay_lam_viec_cham_chu_quan": 7, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 49.9, "dong_dmn": "xep-loai-nha-nuoc-dong-4", "xep_loai": "KHTNV"}$s$, $s$NĐ 335/2025; BR-09-11$s$, 'BA'),
  ('e3000000-0000-4000-8000-0000000c1007', $s$S-007$s$, $s$Cách tính chất lượng trừ 25% mỗi lần chỉnh sửa (một lần)$s$, $s${"loai": "NHA_NUOC", "tham_so": {"cach_tinh_chat_luong": "TRU_25_MOI_LAN"}, "dau_vao": {"ty_le_dap_ung_tieu_chi_chung": 1.0, "nhiem_vu": [{"ke_hoach": 10, "so_luong_duyet": 10, "muc_chat_luong": 1.0, "so_lan_chinh_sua": 1, "ngay_lam_viec_cham_chu_quan": 0, "ngay_lich_cham_chu_quan": 0, "diem_chuan": 10, "khong_hoan_thanh_chu_quan": false, "nhom": "THEN_CHOT"}]}}$s$, $s${"diem": 94.2, "xep_loai": "HTXSNV"}$s$, $s$Bảng chấm điểm; BR-15-02$s$, 'BA')
) AS c(id, code, mo_ta, dau_vao, mong_doi, legal_ref, nguon)
WHERE NOT EXISTS (SELECT 1 FROM policy.golden_test_case g WHERE g.policy_pack_id = (SELECT id FROM policy.policy_pack WHERE tenant_id = 'e3000000-0000-4000-8000-000000000001' AND code = $s$khoi-chinh-quyen/nd335$s$) AND g.case_code = c.code);
COMMIT;

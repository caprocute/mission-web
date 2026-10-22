"""Cổng CI: mọi cột C2, C3, dữ liệu cá nhân trong migration đều phải có luật che."""

import pytest

from anonymize.chay import ThieuLuat, chay
from anonymize.danh_muc import cac_tep_migration, quet_migration
from anonymize.luat import TEP_LUAT, cot_thieu_luat, doc_luat


def test_moi_cot_trong_danh_muc_deu_co_luat():
    dm = quet_migration()
    assert len(cac_tep_migration()) > 30
    assert {"org.person.national_id_enc", "org.person.birth_date_enc", "org.person.full_name",
            "evidence.object_meta.file_name_enc", "product.acceptance.comment_enc"} <= set(dm)
    assert cot_thieu_luat(doc_luat(), dm) == []


def test_bo_luat_mot_cot_c2_thi_do():
    luat = doc_luat(bo_cot={"org.person.national_id_enc"})
    assert cot_thieu_luat(luat, quet_migration()) == ["org.person.national_id_enc"]
    with pytest.raises(ThieuLuat, match="national_id_enc"):
        chay(None, None, None, luat=luat)  # dừng trước khi chạm CSDL


def test_cot_c2_moi_trong_migration_ma_chua_co_luat_thi_do(tmp_path):
    thu_muc = tmp_path / "x" / "src/main/resources/db/migration/hr"
    thu_muc.mkdir(parents=True)
    (thu_muc / "V1__moi.sql").write_text(
        "CREATE TABLE hr.ho_so (\n  id uuid NOT NULL,\n  dia_chi_enc bytea,\n  ghi_chu text\n);\n"
        "COMMENT ON COLUMN hr.ho_so.ghi_chu IS '{\"cls\": \"C3\"}';\n"
        "COMMENT ON COLUMN hr.ho_so.id IS '{\"cls\":\"C1\",\"pii\":false}';\n", encoding="utf-8")
    dm = quet_migration([thu_muc / "V1__moi.sql"])
    assert cot_thieu_luat(doc_luat(), dm) == ["hr.ho_so.dia_chi_enc", "hr.ho_so.ghi_chu"]


def test_luat_sai_bi_tu_choi(tmp_path):
    t = tmp_path / "rules.yaml"
    t.write_text(TEP_LUAT.read_text(encoding="utf-8").replace("{luat: so_the_dang}", "{luat: doan_bua}"), encoding="utf-8")
    with pytest.raises(ValueError, match="doan_bua"):
        doc_luat(t)

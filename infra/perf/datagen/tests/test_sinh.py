import hashlib
from collections import defaultdict
from datetime import date

from datagen.bang import COT
from datagen.kiem import quet_du_lieu_ca_nhan, so_sanh_phan_phoi
from datagen.ho_so import doc_ho_so
from datagen.sinh import BoSinh


def _doc(goc, bang):
    cot = COT[bang]
    for tep in sorted(goc.glob(f"tenant-*/{bang}/*.tsv")):
        for dong in tep.read_text(encoding="utf-8").splitlines():
            yield dict(zip(cot, dong.split("\t")))


def _bam_thu_muc(goc):
    h = hashlib.sha256()
    for tep in sorted(goc.rglob("*.tsv")):
        h.update(tep.relative_to(goc).as_posix().encode())
        h.update(tep.read_bytes())
    return h.hexdigest()


def test_phan_phoi_lech_khong_qua_5_phan_tram(ban_sinh, ho_so_ci):
    _, tk = ban_sinh
    # Đại lượng đếm dưới 50 (số đơn vị ở hệ số 1%) lệch do làm tròn mỗi cấp ít nhất 1, không xét.
    lech = {k: v for k, v in so_sanh_phan_phoi(ho_so_ci, tk).items()
            if v[2] > 0.05 and not (k == "don_vi" and v[1] < 50)}
    assert lech == {}


def test_tat_dinh_cung_hat_giong_cung_du_lieu(tmp_path, ban_sinh, ho_so_ci):
    goc, _ = ban_sinh
    BoSinh(ho_so_ci, tmp_path / "lan2").sinh_tat_ca()
    assert _bam_thu_muc(tmp_path / "lan2") == _bam_thu_muc(goc)
    BoSinh(doc_ho_so("cell-compact", 0.01, hat_giong=7), tmp_path / "khac").sinh_tat_ca()
    assert _bam_thu_muc(tmp_path / "khac") != _bam_thu_muc(goc)


def test_cay_to_chuc_5_cap_moi_don_vi_mot_cap_tren(ban_sinh):
    goc, tk = ban_sinh
    don_vi = {d["id"] for d in _doc(goc, "org.org_unit")}
    cha = defaultdict(list)
    for r in _doc(goc, "org.org_unit_relation"):
        assert r["parent_unit_id"] in don_vi and r["relation_type"] == "TRUC_THUOC"
        cha[r["child_unit_id"]].append(r["parent_unit_id"])
    assert all(len(v) == 1 for v in cha.values())
    assert len(don_vi) - len(cha) == 1  # đúng một gốc
    dem = next(iter(tk["tenant"].values()))["dem"]
    assert all(dem.get(f"don_vi_cap_{c}", 0) >= 1 for c in range(1, 6))


def test_phien_ban_hai_truc_thoi_gian_khong_chong_va_mot_phan_cong_chinh(ban_sinh):
    goc, _ = ban_sinh

    def khong_chong(khoang):
        khoang = sorted((date.fromisoformat(a), date.max if b == "infinity" else date.fromisoformat(b))
                        for a, b in khoang)
        return all(khoang[i][1] <= khoang[i + 1][0] for i in range(len(khoang) - 1))

    for bang, khoa in (("org.org_unit", "id"), ("org.person", "id"), ("org.assignment", "person_id")):
        nhom = defaultdict(list)
        for r in _doc(goc, bang):
            if bang != "org.assignment" or r["kind"] == "CHINH":
                nhom[r[khoa]].append((r["valid_from"], r["valid_to"]))
        assert all(khong_chong(v) for v in nhom.values()), bang
    assert any(len(v) == 2 for v in nhom.values()), "phải có người điều động giữa năm"


def test_truong_c2_chi_o_dang_ban_ma(ban_sinh):
    goc, _ = ban_sinh
    for r in _doc(goc, "org.person"):
        assert r["national_id_enc"].startswith("\\\\x01") and r["dek_id"] != "\\N"
        assert len(bytes.fromhex(r["national_id_enc"][3:])) == 45 + 12
        assert len(bytes.fromhex(r["national_id_bidx"][3:])) == 32


def test_khong_co_du_lieu_ca_nhan_that_va_bo_quet_bat_duoc_mau(ban_sinh, tmp_path):
    goc, _ = ban_sinh
    assert quet_du_lieu_ca_nhan(goc) == []
    # Kiểm thử âm: chèn một số CCCD có mã tỉnh thật và một số di động vào tên người.
    tep = next(goc.glob("tenant-*/org.person/*.tsv"))
    ban = tmp_path / "ban" / tep.parent.parent.name / "org.person"
    ban.mkdir(parents=True)
    dong = tep.read_text(encoding="utf-8").splitlines()[0].split("\t")
    dong[COT["org.person"].index("full_name")] = "Nguyễn Văn A 001203004005 0912345678"
    (ban / "p.tsv").write_text("\t".join(dong) + "\n", encoding="utf-8")
    vi_pham = quet_du_lieu_ca_nhan(tmp_path / "ban")
    assert any("CCCD" in v for v in vi_pham) and any("di động" in v for v in vi_pham)


def test_rang_buoc_nghiep_vu_cua_nhiem_vu_va_minh_chung(ban_sinh):
    goc, _ = ban_sinh
    ma = set()
    for r in _doc(goc, "task.task"):
        assert (r["period_bucket"], r["code"]) not in ma
        ma.add((r["period_bucket"], r["code"]))
        assert r["start_date"] <= r["due_date"]
        assert r["status"] != "HOAN_THANH" or r["completed_at"] != "\\N"
    for r in _doc(goc, "product.evidence_version"):
        if r["status"] != "NHAP":
            assert "\\N" not in (r["object_id"], r["content_sha256"], r["submitted_by"], r["submitted_at"])
        if r["status"] not in ("NHAP", "DA_NOP", "TU_CHOI"):
            assert r["scan_status"] == "SACH"

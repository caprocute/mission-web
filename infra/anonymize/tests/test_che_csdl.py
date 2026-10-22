"""Che trên PostgreSQL 17 đã áp migration thật, nguồn là dữ liệu DL-08 hệ số 0,001 (cell-l).

Bản mã của DL-08 là bản mã giả không khoá nào giải được, nên fixture `lam_nguon` biến nó thành nguồn
"gần thật": thay DEK bằng khoá thật bọc bởi KEK nguồn, ghi họ tên ngoài kho tên giả, ngày sinh, số định
danh, số thẻ đảng, điện thoại, thư, tên tệp dạng rõ rồi mã hoá phong bì đúng NEN-015.
"""

import os
import random
import uuid
from collections import Counter
from datetime import date

import pytest

from anonymize import c2, gia
from anonymize.chay import chay
from anonymize.khoa import KhoaCucBo
from anonymize.luat import doc_luat

pytestmark = pytest.mark.csdl

HO_NGUON = ["Khổng", "Lã", "Âu", "Mạc", "Thái", "Quách", "Lạc", "Văn"]  # ngoài kho họ của bộ giả
MOC = date(2026, 1, 1)


@pytest.fixture(scope="module")
def kn(ket_noi_thu, tmp_path_factory):
    from datagen.ho_so import doc_ho_so
    from datagen.nap import nap
    from datagen.sinh import BoSinh

    goc = tmp_path_factory.mktemp("nguon")
    BoSinh(doc_ho_so("cell-l", 0.001), goc).sinh_tat_ca()
    nap(ket_noi_thu, goc, thay_the=True)
    with ket_noi_thu.mo() as c:
        c.autocommit = True
        yield c


def _bidx_goc(kn, tenant, kk):
    r = kn.execute("SELECT wrapped_dek FROM keyring.dek WHERE tenant_id = %s AND purpose = 'BLIND_INDEX' "
                   "ORDER BY valid_month LIMIT 1", (tenant,)).fetchone()
    return kk.mo_boc(tenant, bytes(r[0]))


def lam_nguon(kn, kk: KhoaCucBo, hat: int) -> dict:
    rng = random.Random(hat)
    nguon: dict = {"nguoi": {}, "lien_he": {}, "tep": {}}
    with kn.transaction():
        dek = {}
        for did, t in kn.execute("SELECT id, tenant_id FROM keyring.dek").fetchall():
            dek[did] = os.urandom(32)
            boc, ref = kk.boc(t, dek[did])
            kn.execute("UPDATE keyring.dek SET wrapped_dek = %s, kek_ref = %s WHERE id = %s", (boc, ref, did))
        for t, in kn.execute("SELECT DISTINCT tenant_id FROM keyring.dek").fetchall():
            goc = _bidx_goc(kn, t, kk)
            nguoi = kn.execute("SELECT DISTINCT ON (id) id, gender, dek_id, is_party_member FROM org.person "
                               "WHERE tenant_id = %s ORDER BY id, valid_from", (t,)).fetchall()
            for pid, gioi, did, dang in nguoi:
                ns = date(1966, 1, 1).toordinal() + rng.randrange(365 * 38)
                ns = date.fromordinal(ns)
                cccd = f"001{(0 if ns.year < 2000 else 2) + (gioi == 'NU')}{ns.year % 100:02d}{rng.randrange(10**6):06d}"
                ten = f"{rng.choice(HO_NGUON)} {rng.choice(['Thế', 'Diệu', 'Khánh'])} {rng.choice(['Vinh', 'Châu', 'Ngọc', 'Lâm'])}"
                the = f"0{rng.randrange(10**11):011d}" if dang else None
                k = dek[did]
                enc = lambda cot, v: None if v is None else c2.ma_hoa(k, did, v.encode(), c2.aad(str(t), f"org.person.{cot}", str(pid)))
                kn.execute("UPDATE org.person SET full_name = %s, birth_date_enc = %s, national_id_enc = %s, "
                           "national_id_bidx = %s, party_member_no_enc = %s, party_member_no_bidx = %s WHERE id = %s",
                           (ten, enc("birth_date_enc", ns.isoformat()), enc("national_id_enc", cccd),
                            c2.chi_muc_mu(goc, str(t), "org.person.national_id_bidx", cccd), enc("party_member_no_enc", the),
                            None if the is None else c2.chi_muc_mu(goc, str(t), "org.person.party_member_no_bidx", the), pid))
                nguon["nguoi"][pid] = {"t": t, "gioi": gioi, "ns": ns, "cccd": cccd, "ten": ten, "the": the}
            kn.execute("DELETE FROM org.person_contact WHERE tenant_id = %s", (t,))
            he_thong = uuid.UUID(int=0)
            for pid, _g, did, _d in nguoi[:300]:
                dt, thu = f"09{rng.randrange(10**8):08d}", f"nguoi.{rng.randrange(10**6)}@co-quan.gov.vn"
                cid, k = uuid.uuid4(), dek[did]
                enc = lambda cot, v: c2.ma_hoa(k, did, v.encode(), c2.aad(str(t), f"org.person_contact.{cot}", str(cid)))
                kn.execute("INSERT INTO org.person_contact (id, tenant_id, person_id, phone_enc, phone_bidx, email_enc, "
                           "email_bidx, dek_id, created_by, updated_by) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                           (cid, t, pid, enc("phone_enc", dt), c2.chi_muc_mu(goc, str(t), "org.person_contact.phone_bidx", dt),
                            enc("email_enc", thu), c2.chi_muc_mu(goc, str(t), "org.person_contact.email_bidx", thu), did, he_thong, he_thong))
                nguon["lien_he"][cid] = (dt, thu)
            ten_mau = next(iter(nguon["nguoi"].values()))["ten"]
            for oid, did in kn.execute("SELECT id, dek_id FROM evidence.object_meta WHERE tenant_id = %s", (t,)).fetchall():
                ten_tep = f"Báo cáo của {ten_mau} {rng.randrange(10**4)}.pdf"
                kn.execute("UPDATE evidence.object_meta SET file_name_enc = %s WHERE id = %s",
                           (c2.ma_hoa(dek[did], did, ten_tep.encode(), c2.aad(str(t), "evidence.object_meta.file_name_enc", str(oid))), oid))
                nguon["tep"][oid] = ten_tep
            mot = next(iter(nguon["nguoi"].values()))
            kn.execute("INSERT INTO org.outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, "
                       "event_type, topic, event_key, payload, schema_version, headers) VALUES "
                       "(%s,%s,'person',%s,1,'PersonChanged','org.person.changed.v1','k',%s,1,%s)",
                       (uuid.uuid4(), t, uuid.uuid4(), mot["cccd"].encode(), f'{{"ten": "{mot["ten"]}"}}'))
    return nguon


def _khoi(kn) -> Counter:
    return Counter(dict(kn.execute(
        "SELECT u.subject_group_id, count(DISTINCT a.person_id) FROM org.assignment a JOIN org.org_unit u "
        "ON u.tenant_id = a.tenant_id AND u.id = a.org_unit_id AND u.valid_to = 'infinity' "
        "WHERE a.kind = 'CHINH' AND a.valid_to = 'infinity' GROUP BY 1").fetchall()))


def _ty_le(c: Counter) -> dict:
    n = sum(c.values())
    return {k: v / n for k, v in c.items()}


def _lech_toi_da(a: Counter, b: Counter) -> float:
    pa, pb = _ty_le(a), _ty_le(b)
    return max(abs(pa.get(k, 0) - pb.get(k, 0)) for k in set(pa) | set(pb))


def test_che_thanh_cong_quet_sach_giu_phan_phoi_va_rls(kn, ket_noi_thu, tmp_path):
    from datagen.nap import kiem_rls

    kk_nguon, kk_dich = KhoaCucBo(os.urandom(32), "nguon"), KhoaCucBo(os.urandom(32), "staging")
    nguon = lam_nguon(kn, kk_nguon, 1)
    khoi_truoc = _khoi(kn)
    dem_truoc = {t: dict(kn.execute("SELECT 'org.person', count(*) FROM org.person WHERE tenant_id = %s UNION ALL "
                                    "SELECT 'evidence.object_meta', count(*) FROM evidence.object_meta WHERE tenant_id = %s",
                                    (t, t)).fetchall()) for t in {n["t"] for n in nguon["nguoi"].values()}}

    bb = chay(kn, kk_nguon, kk_dich, tep_bien_ban=tmp_path / "bien-ban.json")

    assert bb["ket_luan"] == "DAT", bb["quet"]
    assert bb["quet"] == [] and bb["cot_thieu_luat"] == [] and bb["khoa_anh_xa_da_huy"]
    assert bb["bang_loai_bo"]["org.outbox_event"] >= 1
    assert bb["so_mau_nguon"]["so"] >= len(nguon["nguoi"]) and bb["so_mau_nguon"]["ho_ten"] > 0
    assert all(c["khong_giai_duoc"] == 0 for b in bb["bang_che"].values() for c in b.get("cot", {}).values())
    van_ban = (tmp_path / "bien-ban.json").read_text(encoding="utf-8")
    mau = next(iter(nguon["nguoi"].values()))
    assert mau["cccd"] not in van_ban and mau["ten"] not in van_ban  # biên bản không chứa giá trị
    assert kn.execute("SELECT count(*) FROM org.outbox_event").fetchone()[0] == 0
    for t, dem in dem_truoc.items():
        assert kiem_rls(ket_noi_thu, str(t), dem) == []

    # Khoá staging mở được mọi DEK, khoá nguồn thì không.
    for t, boc in kn.execute("SELECT tenant_id, wrapped_dek FROM keyring.dek").fetchall():
        kk_dich.mo_boc(t, bytes(boc))
        with pytest.raises(Exception):
            kk_nguon.mo_boc(t, bytes(boc))

    dek = {d: kk_dich.mo_boc(t, bytes(b)) for d, t, b in kn.execute("SELECT id, tenant_id, wrapped_dek FROM keyring.dek")}
    tuoi_truoc, tuoi_sau, gioi_truoc, gioi_sau = Counter(), Counter(), Counter(), Counter()
    cccd_theo_nguoi: dict = {}
    for pid, t, ten, gioi, did, ns_e, cc_e, cc_b, the_e in kn.execute(
            "SELECT id, tenant_id, full_name, gender, dek_id, birth_date_enc, national_id_enc, national_id_bidx, "
            "party_member_no_enc FROM org.person").fetchall():
        n = nguon["nguoi"][pid]
        g = lambda cot, b: c2.giai_ma(dek[did], bytes(b), c2.aad(str(t), f"org.person.{cot}", str(pid))).decode()
        ns, cccd = date.fromisoformat(g("birth_date_enc", ns_e)), g("national_id_enc", cc_e)
        assert ten != n["ten"] and gia.thuoc_kho_ho_ten_gia(ten)
        assert gioi == n["gioi"] and ns != n["ns"] and cccd != n["cccd"]
        assert gia.nhom_tuoi(ns, MOC, 5) == gia.nhom_tuoi(n["ns"], MOC, 5)
        assert cccd.startswith("999") and cccd[4:6] == f"{ns.year % 100:02d}"
        goc = _bidx_goc(kn, t, kk_dich)
        assert bytes(cc_b) == c2.chi_muc_mu(goc, str(t), "org.person.national_id_bidx", cccd)
        assert (the_e is None) == (n["the"] is None)
        assert cccd_theo_nguoi.setdefault(pid, cccd) == cccd  # mọi phiên bản của một người nhận cùng giá trị giả
        tuoi_truoc[gia.nhom_tuoi(n["ns"], MOC, 5)] += 1
        tuoi_sau[gia.nhom_tuoi(ns, MOC, 5)] += 1
        gioi_truoc[n["gioi"]] += 1
        gioi_sau[gioi] += 1
    assert _lech_toi_da(tuoi_truoc, tuoi_sau) <= 0.05
    assert _lech_toi_da(gioi_truoc, gioi_sau) <= 0.05
    assert _lech_toi_da(khoi_truoc, _khoi(kn)) <= 0.05
    for cid, t, did, p in kn.execute("SELECT id, tenant_id, dek_id, phone_enc FROM org.person_contact").fetchall():
        dt = c2.giai_ma(dek[did], bytes(p), c2.aad(str(t), "org.person_contact.phone_enc", str(cid))).decode()
        assert dt != nguon["lien_he"][cid][0] and dt.startswith(gia.DAU_SO_DIEN_THOAI)


def test_sot_mot_so_dinh_danh_thi_buoc_quet_chan(kn, tmp_path):
    kk_nguon, kk_dich = KhoaCucBo(os.urandom(32)), KhoaCucBo(os.urandom(32))
    nguon = lam_nguon(kn, kk_nguon, 2)
    pid, n = next(iter(nguon["nguoi"].items()))
    kn.execute("UPDATE org.person SET cbccvc_code = %s WHERE id = %s", (n["cccd"], pid))  # cột C1 không có luật
    bb = chay(kn, kk_nguon, kk_dich)
    assert bb["ket_luan"] == "CHAN"
    assert {"bang": "org.person", "cot": "cbccvc_code", "loai": "so"} in [
        {k: v for k, v in x.items() if k != "so_lan"} for x in bb["quet"]]


def test_bo_luat_mot_cot_c2_khi_cho_phep_thi_ban_ro_bi_giu_va_quet_chan(kn):
    kk_nguon, kk_dich = KhoaCucBo(os.urandom(32)), KhoaCucBo(os.urandom(32))
    lam_nguon(kn, kk_nguon, 3)
    bb = chay(kn, kk_nguon, kk_dich, luat=doc_luat(bo_cot={"org.person.national_id_enc"}), cho_phep_thieu_luat=True)
    assert bb["cot_thieu_luat"] == ["org.person.national_id_enc"]
    assert bb["cot_ngam_dinh_giu"] == ["org.person.national_id_enc"]
    assert bb["ket_luan"] == "CHAN"
    assert any(x["cot"] == "national_id_enc" and x["loai"] == "so" for x in bb["quet"])

import json
import os
import uuid

import pytest

from anonymize import c2
from anonymize.khoa import KhoaCucBo
from conftest import GOC

VECTOR = GOC.parents[1] / "backend/libs/vt-platform-starter/src/test/resources/vn/viettel/okrkpi/shared/crypto/c2-test-vectors.json"


def test_phong_bi_khop_tung_byte_voi_java_nen015():
    e = json.loads(VECTOR.read_text(encoding="utf-8"))["envelopeVector"]
    aad = c2.aad(e["tenantId"], *e["aadParts"])
    assert aad.decode() == e["aadUtf8"]
    bm = c2.ma_hoa(bytes.fromhex(e["keyHex"]), uuid.UUID(e["dekId"]), e["plaintextUtf8"].encode(), aad,
                   bytes.fromhex(e["nonceHex"]))
    assert bm.hex() == e["envelopeHex"]
    assert c2.dek_cua(bm) == uuid.UUID(e["dekId"])
    assert c2.giai_ma(bytes.fromhex(e["keyHex"]), bm, aad).decode() == e["plaintextUtf8"]


def test_chi_muc_mu_khop_java_nen015():
    b = json.loads(VECTOR.read_text(encoding="utf-8"))["blindIndexVector"]
    assert c2.chuan_hoa(b["rawInput"]) == b["normalizedInput"]
    assert c2.chi_muc_mu(bytes.fromhex(b["rootKeyHex"]), b["tenantId"], b["column"], b["rawInput"]).hex() == b["blindIndexHex"]


def test_sai_aad_thi_khong_giai_duoc():
    k, d = os.urandom(32), uuid.uuid4()
    bm = c2.ma_hoa(k, d, b"x", c2.aad("t", "a.b.c", "1"))
    with pytest.raises(Exception):
        c2.giai_ma(k, bm, c2.aad("t", "a.b.c", "2"))


def test_kek_cuc_bo_boc_va_mo_boc_theo_tenant():
    kk = KhoaCucBo(os.urandom(32))
    t, dek = uuid.uuid4(), os.urandom(32)
    boc, ref = kk.boc(t, dek)
    assert kk.mo_boc(t, boc) == dek and ref.endswith(f"kek-{t}")
    with pytest.raises(Exception):
        kk.mo_boc(uuid.uuid4(), boc)
    with pytest.raises(Exception):
        KhoaCucBo(os.urandom(32)).mo_boc(t, boc)

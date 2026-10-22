"""Kiểm thử không cần Docker của DV-14-02: chia đoạn, nhúng giả lập, lược đồ EVT-24, trích văn bản tệp đính kèm."""

from __future__ import annotations

import io
import json
import math
import pathlib
import unicodedata
import zipfile

import pytest

from app.rag import consumer
from app.rag.chunker import MAX_WORDS, MIN_WORDS, chunk_text
from app.rag.embed import DIM, HashEmbedder
from app.rag.ingest import AttachmentError, document_text, extract_text
from app.rag.retriever import header, parse_header

REPO = pathlib.Path(__file__).resolve().parents[3]


def regulation(articles: int, clauses: int, words_per_clause: int) -> str:
    out = ["QUY ĐỊNH MẪU", "Chương I", "QUY ĐỊNH CHUNG"]
    for a in range(1, articles + 1):
        out.append(f"Điều {a}. Nội dung điều {a}")
        for k in range(1, clauses + 1):
            body = " ".join(f"từ{a}_{k}_{i}" for i in range(words_per_clause - 1))
            out.append(f"{k}. {body} hết.")
    return "\n".join(out)


def test_chunks_respect_size_and_structure() -> None:
    text = regulation(articles=12, clauses=4, words_per_clause=70)
    chunks = chunk_text(text)
    assert len(chunks) > 3
    for c in chunks[:-1]:
        assert c.words <= MAX_WORDS + 20, c.words  # cộng dòng tiêu đề điều nhắc lại
        assert c.words >= MIN_WORDS - 140, c.words
    for c in chunks:
        # Không cắt giữa khoản: mỗi khoản xuất hiện nguyên vẹn (kết thúc bằng "hết.").
        for line in c.text.splitlines():
            if line[:3].rstrip(".").isdigit():
                assert line.endswith("hết."), line[:40]
        assert c.text.splitlines()[0].startswith(("Điều", "QUY ĐỊNH", "Chương", "1.", "2.", "3.", "4."))
    # Chồng lấp: khoản cuối của đoạn trước xuất hiện lại ở đoạn sau.
    last = chunks[0].text.splitlines()[-1]
    assert last in chunks[1].text
    assert chunks[1].location.startswith("Điều")


def test_long_clause_is_split_by_sentence_and_nfc() -> None:
    sent = "Câu " + " ".join(["dài"] * 40) + "."
    text = "Điều 1. Một điều rất dài\n1. " + " ".join([sent] * 40)
    nfd = unicodedata.normalize("NFD", text)
    chunks = chunk_text(nfd)
    assert len(chunks) >= 2 and all(c.words <= MAX_WORDS for c in chunks)
    assert all(unicodedata.is_normalized("NFC", c.text) for c in chunks)


def test_hash_embedder_is_normalized_and_semantic() -> None:
    e = HashEmbedder()
    a, b, c = e.vector("Hệ số tiến độ khi nộp chậm"), e.vector("he so tien do nop cham"), e.vector("Mua sắm tài sản")
    assert len(a) == DIM and math.isclose(sum(x * x for x in a), 1.0, rel_tol=1e-6)
    assert sum(x * y for x, y in zip(a, b, strict=True)) > 0.6 > sum(x * y for x, y in zip(a, c, strict=True))


def test_header_roundtrip() -> None:
    t, loc, body = parse_header(header("Hướng dẫn; bản mẫu", "Điều 4, khoản 1") + "\nNội dung")
    assert (t, loc, body) == ("Hướng dẫn; bản mẫu", "Điều 4, khoản 1", "Nội dung")
    assert parse_header("Không có tiêu đề") == ("Tài liệu", "", "Không có tiêu đề")


def test_avro_schema_matches_contract_and_roundtrips() -> None:
    contract = REPO / "contracts" / "events" / "avro" / "integ" / "VanBanChiDaoDaNhan.avsc"
    assert json.loads(contract.read_text()) == json.loads(consumer.SCHEMA_PATH.read_text())
    rec = {"documentId": "0192f000-0000-7000-8000-0000000000d1", "connectorCode": "QLVB", "externalId": "x-1",
           "docNumber": "12/CT-UBND", "issuedDate": 20400, "issuer": "UBND tỉnh", "summary": "Kế hoạch 2027",
           "objectKey": None, "aggregateVersion": 1}
    assert consumer.decode(consumer.encode(rec))["docNumber"] == "12/CT-UBND"
    with pytest.raises(consumer.PermanentError):
        consumer.decode(b"\x01\x02")
    with pytest.raises(consumer.PermanentError):
        consumer.to_event("t", 0, 0, {"id": "khong-phai-uuid", "tenantid": "x"})


def test_source_doc_text() -> None:
    doc = {"issuerHeader": "UBND TỈNH", "title": "Chỉ thị", "preamble": "Để triển khai",
           "paragraphs": [{"no": 1, "text": "1. Sở Nội vụ chủ trì.", "assignment": True}], "recipients": "Các sở"}
    assert document_text(doc) == "UBND TỈNH\nChỉ thị\nĐể triển khai\n1. Sở Nội vụ chủ trì."


def _docx(paragraphs: list[str]) -> bytes:
    ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    body = "".join(f"<w:p><w:r><w:t>{p}</w:t></w:r></w:p>" for p in paragraphs)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", f'<w:document xmlns:w="{ns}"><w:body>{body}</w:body></w:document>')
    return buf.getvalue()


def _pdf(text: str) -> bytes:
    stream = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode()
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
            b"/Resources << /Font << /F1 5 0 R >> >> >>",
            b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out = b"%PDF-1.4\n"
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{x:010d} 00000 n \n".encode() for x in offsets)
    out += f"trailer << /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF".encode()
    return out


def test_extract_attachment_text() -> None:
    assert extract_text("ke-hoach.docx", _docx(["Điều 1. Phạm vi", "1. Áp dụng cho sở"])) == \
        "Điều 1. Phạm vi\n1. Áp dụng cho sở"
    assert "Ke hoach nam 2027" in extract_text("vb.pdf", _pdf("Ke hoach nam 2027"))
    with pytest.raises(AttachmentError):
        extract_text("anh.png", b"\x89PNG")
    with pytest.raises(AttachmentError):
        extract_text("x.docx", b"khong phai zip")
    with pytest.raises(AttachmentError):
        extract_text("lon.pdf", b"0" * (20 * 1024 * 1024 + 1))

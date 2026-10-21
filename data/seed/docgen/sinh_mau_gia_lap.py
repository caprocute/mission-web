"""Sinh tệp DOCX giả lập cho mọi mẫu biểu có trạng thái GIA_LAP (DL-05, YC-PT-14).

Cách chạy:  python -I data/seed/docgen/sinh_mau_gia_lap.py data/policy-pack

Mẫu giả lập không phải bố cục văn bản pháp lý: nó chỉ dựng đủ vùng trộn (content control) theo
tệp kê khai để document-service phát triển và kiểm thử trộn dữ liệu, chuyển PDF/A. Mọi trang mang
dòng "MẪU GIẢ LẬP" ở đầu trang. Khi có bản mẫu chính thức, thay tệp DOCX, đổi trạng thái kê khai
và phát hành phiên bản Policy Pack mới; không sửa mã. Chỉ dùng thư viện chuẩn của Python 3.12.
Tệp sinh ra cố định nội dung (thời điểm trong gói zip đặt cố định) để băm mẫu không đổi khi sinh lại.
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

DAU_GIA_LAP = "MẪU GIẢ LẬP – CHỈ DÙNG ĐỂ PHÁT TRIỂN VÀ KIỂM THỬ, KHÔNG CÓ GIÁ TRỊ PHÁP LÝ"
NS = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ' \
     'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
NGAY_CO_DINH = (2026, 1, 1, 0, 0, 0)

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
</Relationships>"""

STYLES = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {NS}>
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman" w:eastAsia="Times New Roman"/>
<w:sz w:val="26"/><w:szCs w:val="26"/><w:lang w:val="vi-VN"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="80"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="table" w:styleId="BangLuoi"><w:name w:val="Bang luoi"/><w:tblPr><w:tblBorders>
<w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/>
<w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/>
</w:tblBorders></w:tblPr></w:style>
</w:styles>"""


def chu(text: str, dam: bool = False, nghieng: bool = False, co: int | None = None) -> str:
    rpr = ("<w:b/>" if dam else "") + ("<w:i/>" if nghieng else "") + (f'<w:sz w:val="{co}"/>' if co else "")
    return f'<w:r>{"<w:rPr>" + rpr + "</w:rPr>" if rpr else ""}<w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def doan(noi_dung: str, giua: bool = False) -> str:
    ppr = '<w:pPr><w:jc w:val="center"/></w:pPr>' if giua else ""
    return f"<w:p>{ppr}{noi_dung}</w:p>"


def sdt_dong(t: dict) -> str:
    """Vùng trộn trong dòng: một content control có tag ổn định."""
    return (f'<w:sdt><w:sdtPr><w:alias w:val={quoteattr(t["nhan"])}/><w:tag w:val={quoteattr(t["tag"])}/>'
            f'<w:showingPlcHdr/></w:sdtPr><w:sdtContent>{chu("[" + t["nhan"] + "]", nghieng=True)}</w:sdtContent></w:sdt>')


def sdt_khoi(t: dict, ben_trong: str) -> str:
    return (f'<w:sdt><w:sdtPr><w:alias w:val={quoteattr(t["nhan"])}/><w:tag w:val={quoteattr(t["tag"])}/></w:sdtPr>'
            f"<w:sdtContent>{ben_trong}</w:sdtContent></w:sdt>")


def o(noi_dung: str) -> str:
    return f'<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/></w:tcPr><w:p>{noi_dung}</w:p></w:tc>'


def bang(t: dict) -> str:
    cot = t.get("cot") or []
    if not cot:
        return sdt_khoi(t, doan(chu("[" + t["nhan"] + ": cột sẽ xác định theo bản mẫu chính thức]", nghieng=True)))
    dau = "<w:tr>" + "".join(o(chu(c["nhan"], dam=True)) for c in cot) + "</w:tr>"
    dong = "<w:tr>" + "".join(o(sdt_dong(c)) for c in cot) + "</w:tr>"
    tbl = (f'<w:tbl><w:tblPr><w:tblStyle w:val="BangLuoi"/><w:tblW w:w="5000" w:type="pct"/></w:tblPr>'
           f"<w:tblGrid>{'<w:gridCol/>' * len(cot)}</w:tblGrid>{dau}{dong}</w:tbl>")
    return sdt_khoi(t, tbl)


def truong_than(t: dict) -> str:
    if t["kieu"] == "bang":
        return doan(chu(t["nhan"], dam=True)) + bang(t)
    return doan(chu(t["nhan"] + ": ") + sdt_dong(t))


def tai_lieu(m: dict) -> dict[str, str]:
    ds = m["truong"]
    than = [doan(chu(DAU_GIA_LAP, dam=True, co=20), giua=True),
            doan(chu(m["ten_mau"].upper(), dam=True, co=28), giua=True),
            doan(chu(m["mo_ta"], nghieng=True), giua=True)]
    for vung in ("dau_trang", "than", "ky"):
        if vung == "ky" and any(t.get("vung") == "ky" for t in ds):
            than.append(doan(""))
        than += [truong_than(t) for t in ds if t.get("vung", "than") == vung]
    # Mẫu danh sách nhiều cột (Mẫu 3) đặt khổ ngang để bảng đọc được.
    kho = '<w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>' if m["doi_tuong"] == "DANH_SACH" else '<w:pgSz w:w="11906" w:h="16838"/>'
    sect = ('<w:sectPr><w:headerReference w:type="default" r:id="rId2"/><w:footerReference w:type="default" r:id="rId3"/>'
            f'{kho}<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1701" '
            'w:header="567" w:footer="567" w:gutter="0"/></w:sectPr>')
    document = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:document {NS}><w:body>{"".join(than)}{sect}</w:body></w:document>'
    header = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:hdr {NS}>{doan(chu(DAU_GIA_LAP, nghieng=True, co=18), giua=True)}</w:hdr>'
    chan = [t for t in ds if t.get("vung") == "chan_trang"]
    noi = []
    for i, t in enumerate(chan):
        noi.append(chu(("   " if i else "") + t["nhan"] + ": ", co=18) + sdt_dong(t))
    footer = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:ftr {NS}>{doan("".join(noi))}</w:ftr>'
    return {"[Content_Types].xml": CONTENT_TYPES, "_rels/.rels": RELS, "word/_rels/document.xml.rels": DOC_RELS,
            "word/styles.xml": STYLES, "word/document.xml": document, "word/header1.xml": header, "word/footer1.xml": footer}


def ghi_docx(tep: Path, phan: dict[str, str]) -> None:
    with zipfile.ZipFile(tep, "w", zipfile.ZIP_DEFLATED) as z:
        for ten, noi_dung in phan.items():
            info = zipfile.ZipInfo(ten, NGAY_CO_DINH)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, noi_dung.encode("utf-8"))


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("Cách dùng: python -I sinh_mau_gia_lap.py <thư mục data/policy-pack>", file=sys.stderr)
        return 2
    so = 0
    for tep in sorted(Path(argv[0]).glob("**/templates/*/manifest.json")):
        m = json.loads(tep.read_text(encoding="utf-8"))
        if m.get("trang_thai") != "GIA_LAP":
            continue
        ghi_docx(tep.parent / m["tep_mau"], tai_lieu(m))
        so += 1
        print(f"Đã sinh {tep.parent / m['tep_mau']}")
    print(f"Tổng cộng {so} mẫu giả lập.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

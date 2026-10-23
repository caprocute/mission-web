"""Kiểm mọi runbook_url trong luật cảnh báo trỏ tới sổ tay có thật (VH-02, TL-04 OPS-14).

Quét các tệp YAML và mẫu Helm dưới deploy/, lấy chú thích runbook_url dạng
https://runbook.vt.local/okr/<tên>[#<mục>] hoặc {{ $rb }}/<tên>[#<mục>] (biến runbookBase của chart),
rồi kiểm có ops/runbooks/<tên>.md hoặc ops/runbooks/canh-bao/<tên>.md, và nếu có #<mục> thì sổ tay có
tiêu đề sinh ra đúng neo đó (bỏ dấu tiếng Việt, chữ thường, khoảng trắng thành gạch nối) hoặc
neo đặt tay <a id="<mục>"></a>.
Kiểm thêm mọi liên kết tương đối giữa các sổ tay trong ops/runbooks trỏ tới tệp có thật.
Cách chạy từ gốc repo: python3 -I ops/runbooks/kiem-lien-ket.py   (mã thoát 1 khi có liên kết gãy).
Thêm --liet-ke để in bảng cảnh báo → sổ tay."""
import pathlib
import re
import sys
import unicodedata

GOC = pathlib.Path(__file__).resolve().parents[2]
SO_TAY = GOC / "ops" / "runbooks"
MAU_URL = re.compile(r"runbook_url:\s*['\"]?(?:https://runbook\.vt\.local/okr|\{\{\s*\$rb\s*\}\})/([a-z0-9-]+)(?:#([a-z0-9-]+))?")
MAU_ALERT = re.compile(r"^\s*-?\s*alert:\s*['\"]?([A-Za-z0-9_]+)")


def neo(tieu_de: str) -> str:
    s = unicodedata.normalize("NFD", tieu_de.replace("đ", "d").replace("Đ", "D"))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"[^a-z0-9 -]", "", s)
    return re.sub(r" +", "-", s.strip())


def tim_so_tay(ten: str):
    for p in (SO_TAY / f"{ten}.md", SO_TAY / "canh-bao" / f"{ten}.md"):
        if p.is_file():
            return p
    return None


def cac_neo(p: pathlib.Path) -> set:
    nd = p.read_text(encoding="utf-8")
    tu_tieu_de = {neo(m.group(1)) for m in re.finditer(r"^#{1,6}\s+(.+?)\s*$", nd, re.M)}
    tuong_minh = set(re.findall(r'<a (?:id|name)="([a-z0-9-]+)"', nd))  # neo đặt tay khi tiêu đề không sinh được
    return tu_tieu_de | tuong_minh


def main() -> int:
    lien_ket = []  # (tệp luật, cảnh báo, tên, neo)
    for f in sorted((GOC / "deploy").rglob("*")):
        if f.suffix not in (".yaml", ".yml", ".tpl") or not f.is_file():
            continue
        canh_bao = "?"
        for dong in f.read_text(encoding="utf-8", errors="replace").splitlines():
            a = MAU_ALERT.match(dong)
            if a:
                canh_bao = a.group(1)
            m = MAU_URL.search(dong)
            if m:
                lien_ket.append((f.relative_to(GOC), canh_bao, m.group(1), m.group(2)))
    loi = []
    for tep, canh_bao, ten, muc in lien_ket:
        p = tim_so_tay(ten)
        if p is None:
            loi.append(f"{tep}: {canh_bao} → thiếu sổ tay {ten}.md")
        elif muc and muc not in cac_neo(p):
            loi.append(f"{tep}: {canh_bao} → {p.relative_to(GOC)} không có mục #{muc}")
    # Liên kết tương đối giữa các sổ tay: [..](tệp.md) và tham chiếu dạng `../tệp.md`.
    for md in sorted(SO_TAY.rglob("*.md")):
        for m in re.finditer(r"\]\(([^)#:]+\.md)(?:#[^)]*)?\)|`(\.\./[A-Za-z0-9_./-]+\.md)`", md.read_text(encoding="utf-8")):
            dich = (md.parent / (m.group(1) or m.group(2))).resolve()
            if not dich.is_file():
                loi.append(f"{md.relative_to(GOC)}: liên kết tới {m.group(1) or m.group(2)} không có tệp")
    if "--liet-ke" in sys.argv:
        for tep, canh_bao, ten, muc in sorted(set(lien_ket), key=lambda x: (x[2], x[1])):
            print(f"{ten}{'#' + muc if muc else ''}\t{canh_bao}\t{tep}")
    so_ten = len({x[2] for x in lien_ket})
    print(f"{len(lien_ket)} runbook_url, {so_ten} sổ tay khác nhau; {len(loi)} liên kết gãy.")
    for d in sorted(set(loi)):
        print("  " + d)
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())

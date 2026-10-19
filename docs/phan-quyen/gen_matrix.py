#!/usr/bin/env python3
"""Sinh ma trận phân quyền từ một nguồn dữ liệu duy nhất.

Nguồn: docs/phan-quyen/ma-tran.json (sửa ma trận ở đây, không sửa bảng sinh ra).
Đầu ra:
  - docs/phan-quyen/ma-tran-phan-quyen.xlsx (6 sheet: Chú giải, Vai trò, Ma trận chức năng, API, Mức dữ liệu, SoD)
  - các khối bảng giữa cặp dấu <!-- BEGIN GEN:<tên> --> ... <!-- END GEN:<tên> --> trong wiki/15a-ma-tran-phan-quyen.md
  - frontend/projects/auth/src/lib/permissions.generated.ts (bảng quyền phía giao diện cho `@vt/auth`, WEB-054)

Dùng:
  python3 docs/phan-quyen/gen_matrix.py           # sinh lại xlsx, Markdown và tệp TypeScript
  python3 docs/phan-quyen/gen_matrix.py --check   # chỉ kiểm tra Markdown và TypeScript có khớp dữ liệu không (dùng trong CI)
  python3 docs/phan-quyen/gen_matrix.py --md-only # chỉ sinh lại Markdown
  python3 docs/phan-quyen/gen_matrix.py --ts      # chỉ sinh lại tệp TypeScript

Chỉ cần Python 3 và openpyxl (không cài thêm gói nào).
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DATA = HERE / "ma-tran.json"
XLSX = HERE / "ma-tran-phan-quyen.xlsx"
WIKI = ROOT / "wiki" / "15a-ma-tran-phan-quyen.md"
TS = ROOT / "frontend" / "projects" / "auth" / "src" / "lib" / "permissions.generated.ts"

CELL_RE = re.compile(r"^(-|A\*?|H|[XTDR]/(B|Gn|Đv|Cd|Tn|N)\*?)$")
FILL = {"X": "93C47D", "T": "A4C2F4", "D": "F9CB9C", "R": "D9EAD3", "A": "D9D2E9", "H": "E0E0E0"}


def load():
    d = json.loads(DATA.read_text(encoding="utf-8"))
    acs = [r["ac"] for r in d["vai_tro"]]
    keys = set()
    for f in d["chuc_nang"]:
        if f["key"] in keys:
            raise SystemExit(f"Trùng khoá chức năng: {f['key']}")
        keys.add(f["key"])
        if f["scr"] not in d["man_hinh"]:
            raise SystemExit(f"{f['key']}: SCR không có trong danh mục man_hinh")
        if set(f["quyen"]) != set(acs):
            raise SystemExit(f"{f['key']}: thiếu hoặc thừa cột vai trò")
        for ac, v in f["quyen"].items():
            if not CELL_RE.match(v):
                raise SystemExit(f"{f['key']} {ac}: ô '{v}' sai cú pháp")
    missing = [s for s in d["man_hinh"] if s not in {f["scr"] for f in d["chuc_nang"]}]
    if missing:
        raise SystemExit(f"Màn hình chưa có dòng nào trong ma trận: {', '.join(missing)}")
    return d, acs


def esc(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def stats(d):
    fs = d["chuc_nang"]
    inferred = sum(1 for f in fs for v in f["quyen"].values() if v.endswith("*"))
    return dict(n=len(fs), web=sum(1 for f in fs if f["web"]), scr=len({f["scr"] for f in fs}),
                scr_web=len({f["scr"] for f in fs if f["web"]}), inferred=inferred)


# ------------------------------------------------------------------ Markdown

def md_blocks(d, acs):
    b = {}
    s = stats(d)
    roles = {r["ac"]: r["role"] for r in d["vai_tro"]}

    lines = ["| Ký hiệu | Nghĩa | Mô tả |", "|---|---|---|"]
    lines += [f"| `{x['ky_hieu']}` | {x['ten']} | {esc(x['mo_ta'])} |" for x in d["ky_hieu"]]
    lines += ["", "| Phạm vi | Nghĩa | Mô tả |", "|---|---|---|"]
    lines += [f"| `{x['ky_hieu']}` | {x['ten']} | {esc(x['mo_ta'])} |" for x in d["pham_vi"]]
    lines += ["", f"Dữ liệu hiện có {s['n']} chức năng trên {s['scr']} màn hình, trong đó {s['web']} chức năng thuộc {s['scr_web']} màn hình web; "
              f"{s['inferred']} ô được đánh dấu suy luận (`*`)."]
    b["chu-giai"] = "\n".join(lines)

    head = "| SCR | Chức năng (khoá) | " + " | ".join(acs) + " | Ràng buộc |"
    sep = "|---|---|" + "---|" * len(acs) + "---|"
    out = ["Dòng tiêu đề cột: " + ", ".join(f"{a} `{roles[a]}`" for a in acs) + "."]
    cur = None
    for f in d["chuc_nang"]:
        if f["qt"] != cur:
            cur = f["qt"]
            out += ["", f"#### {cur}. {d['quy_trinh'][cur]}", "", head, sep]
        short = f["key"].split(".", 1)[1]
        name = f"{esc(f['ten'])} `{short}`"
        cells = " | ".join(esc(f["quyen"][a]) for a in acs)
        out.append(f"| {f['scr']} | {name} | {cells} | {esc(f['rang_buoc'])} |")
    b["ma-tran-chinh"] = "\n".join(out).strip()

    notes = ["| Khoá | Ghi chú về ô suy luận hoặc điểm lệch |", "|---|---|"]
    notes += [f"| `{f['key']}` | {esc(f['ghi_chu'])} |" for f in d["chuc_nang"] if f["ghi_chu"]]
    b["ghi-chu"] = "\n".join(notes)

    lines = ["| Module | " + " | ".join(acs) + " | Ghi chú |", "|---|" + "---|" * len(acs) + "---|"]
    for m in d["api"]:
        mod = f"`/api/v1/{m['module']}`" + (" (ngoài danh sách baseline 13)" if m["ngoai_baseline"] else "")
        lines.append(f"| {mod} | " + " | ".join(esc(m["quyen"][a]) for a in acs) + f" | {esc(m['ghi_chu'])} |")
    b["api"] = "\n".join(lines)

    lines = ["| Vai trò | C0 Công khai | C1 Nội bộ | C2 Nhạy cảm | C3 Hạn chế |", "|---|---|---|---|---|"]
    for r in d["muc_du_lieu"]:
        lines.append(f"| {r['ac']} `{roles[r['ac']]}` | {esc(r['C0'])} | {esc(r['C1'])} | {esc(r['C2'])} | {esc(r['C3'])} |")
    b["muc-du-lieu"] = "\n".join(lines)

    lines = ["| STT | Màn hình | Chức năng | Loại | Quy tắc | Nguồn | Kiểm soát ở | Thông báo |", "|---|---|---|---|---|---|---|---|"]
    for i, r in enumerate(d["rang_buoc"], 1):
        lines.append(f"| {i} | {esc(r['scr'])} | {esc(r['chuc_nang'])} | {esc(r['loai'])} | {esc(r['quy_tac'])} | {esc(r['nguon'])} | {esc(r['kiem_soat'])} | {esc(r['msg'])} |")
    b["rang-buoc"] = "\n".join(lines)
    return b


def render_md(text, blocks):
    for name, body in blocks.items():
        pat = re.compile(r"(<!-- BEGIN GEN:%s -->\n)(?:.*?\n)?(<!-- END GEN:%s -->)" % (re.escape(name), re.escape(name)), re.S)
        if not pat.search(text):
            raise SystemExit(f"Thiếu cặp dấu GEN:{name} trong {WIKI}")
        text = pat.sub(lambda m: m.group(1) + body + "\n" + m.group(2), text)
    return text


# ------------------------------------------------------------------ Excel

def write_xlsx(d, acs):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

    thin = Side(style="thin", color="BFBFBF")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    head_fill = PatternFill("solid", fgColor="1F4E78")
    head_font = Font(bold=True, color="FFFFFF")
    wrap = Alignment(wrap_text=True, vertical="top")
    center = Alignment(horizontal="center", vertical="center", wrap_text=True)
    roles = {r["ac"]: r["role"] for r in d["vai_tro"]}

    def sheet(wb, title, headers, rows, widths, freeze="A2", center_cols=()):
        ws = wb.create_sheet(title)
        ws.append(headers)
        for c in ws[1]:
            c.fill, c.font, c.alignment, c.border = head_fill, head_font, center, border
        for r in rows:
            ws.append(r)
        for row in ws.iter_rows(min_row=2):
            for c in row:
                c.border = border
                c.alignment = center if c.column in center_cols else wrap
        for i, w in enumerate(widths, 1):
            ws.column_dimensions[ws.cell(row=1, column=i).column_letter].width = w
        ws.freeze_panes = freeze
        ws.auto_filter.ref = ws.dimensions
        return ws

    def paint(c):
        v = str(c.value or "")
        k = v[:1]
        if k in FILL and v != "-":
            c.fill = PatternFill("solid", fgColor=FILL[k])
        if v.endswith("*"):
            c.font = Font(italic=True, color="9C0006")

    wb = Workbook()
    wb.remove(wb.active)
    m = d["meta"]
    s = stats(d)
    ws = sheet(wb, "Chú giải", ["Mục", "Giá trị", "Mô tả"],
               [["Tài liệu", m["ten"], f"Phiên bản {m['phien_ban']}, {m['ngay']}"],
                ["Nguồn", m["nguon"], "Sinh từ docs/phan-quyen/ma-tran.json bằng gen_matrix.py; không sửa tay tệp này."],
                ["Quy ước ô", m["quy_uoc_o"], "Ô chữ nghiêng màu đỏ là suy luận."],
                ["Thống kê", f"{s['n']} chức năng, {s['scr']} màn hình", f"{s['web']} chức năng web, {s['inferred']} ô suy luận"]]
               + [["Ký hiệu " + x["ky_hieu"], x["ten"], x["mo_ta"]] for x in d["ky_hieu"]]
               + [["Phạm vi " + x["ky_hieu"], x["ten"], x["mo_ta"]] for x in d["pham_vi"]],
               [18, 60, 80])
    for row in ws.iter_rows(min_row=2):
        lab = str(row[0].value)
        if lab.startswith("Ký hiệu "):
            k = lab.split(" ", 2)[2]
            if k in FILL:
                row[0].fill = PatternFill("solid", fgColor=FILL[k])

    sheet(wb, "Vai trò", ["Mã", "Vai trò Keycloak", "Tác nhân", "Phạm vi mặc định", "Mô tả phạm vi", "Quyền chính", "Mức xác thực", "Hiện thực ở Keycloak", "Nguồn"],
          [[r["ac"], r["role"], r["ten"], r["pham_vi"], r["pham_vi_mo_ta"], r["mo_ta"], r["aal"], r["keycloak"], r["nguon"]] for r in d["vai_tro"]],
          [8, 18, 30, 10, 45, 55, 35, 30, 40], freeze="C2", center_cols=(1, 4))

    heads = ["QT", "SCR", "Tên màn hình", "Khoá", "Chức năng", "Web"] + [f"{a}\n{roles[a]}" for a in acs] + ["Ràng buộc", "API chính", "Nguồn", "Ghi chú"]
    rows = []
    for f in d["chuc_nang"]:
        rows.append([f["qt"], f["scr"], d["man_hinh"][f["scr"]], f["key"], f["ten"], "Có" if f["web"] else "Di động"]
                    + [f["quyen"][a] for a in acs] + [f["rang_buoc"], f["api"], f["nguon"], f["ghi_chu"]])
    ws = sheet(wb, "Ma trận chức năng", heads, rows,
               [7, 10, 24, 22, 45, 8] + [9] * len(acs) + [35, 45, 40, 40], freeze="F2",
               center_cols=tuple(range(1, 3)) + tuple(range(6, 7 + len(acs))))
    ws.row_dimensions[1].height = 32
    for row in ws.iter_rows(min_row=2, min_col=7, max_col=6 + len(acs)):
        for c in row:
            paint(c)

    ws = sheet(wb, "API", ["Module", "Ngoài baseline mục 13"] + [f"{a}\n{roles[a]}" for a in acs] + ["Ghi chú", "Nguồn"],
               [[f"/api/v1/{x['module']}", "Có" if x["ngoai_baseline"] else ""] + [x["quyen"][a] for a in acs] + [x["ghi_chu"], x["nguon"]] for x in d["api"]],
               [18, 10] + [13] * len(acs) + [50, 40], freeze="C2", center_cols=tuple(range(2, 3 + len(acs))))
    ws.row_dimensions[1].height = 32
    for row in ws.iter_rows(min_row=2, min_col=3, max_col=2 + len(acs)):
        for c in row:
            v = str(c.value or "")
            if v and v != "-":
                c.fill = PatternFill("solid", fgColor=FILL["D"] if re.search(r"POST|PUT|PATCH|DELETE", v) else FILL["R"])
            if v.endswith("*"):
                c.font = Font(italic=True, color="9C0006")

    ws = sheet(wb, "Mức dữ liệu", ["Mã", "Vai trò", "C0 Công khai", "C1 Nội bộ", "C2 Nhạy cảm", "C3 Hạn chế"],
               [[r["ac"], roles[r["ac"]], r["C0"], r["C1"], r["C2"], r["C3"]] for r in d["muc_du_lieu"]],
               [8, 18, 18, 40, 55, 30], freeze="C2")
    for row in ws.iter_rows(min_row=2, min_col=3, max_col=6):
        for c in row:
            if str(c.value).endswith("*"):
                c.font = Font(italic=True, color="9C0006")

    sheet(wb, "SoD", ["STT", "Màn hình", "Chức năng", "Loại", "Quy tắc", "Nguồn", "Kiểm soát ở", "Thông báo"],
          [[i, r["scr"], r["chuc_nang"], r["loai"], r["quy_tac"], r["nguon"], r["kiem_soat"], r["msg"]] for i, r in enumerate(d["rang_buoc"], 1)],
          [6, 18, 30, 16, 70, 35, 35, 20], freeze="C2", center_cols=(1,))
    wb.save(XLSX)


# ------------------------------------------------------------------ TypeScript (WEB-054)

def ts_source(d):
    """Bảng quyền cho `@vt/auth`: chỉ chức năng web, chỉ vai trò người dùng có realm role (bỏ AC-11, AC-12).

    Chỉ giữ dữ liệu cần để quyết định ẩn/hiện (khoá và vai trò), không đưa tên chức năng, phạm vi
    hay ghi chú vào bundle. Vai trò có ô suy luận mang hậu tố `*` để có thể tắt khi kiểm thử chặt.
    """
    skip = {"AC-11", "AC-12"}
    roles = [(r["ac"], r["role"]) for r in d["vai_tro"] if r["ac"] not in skip]
    fs = [f for f in d["chuc_nang"] if f["web"]]
    m = d["meta"]
    out = [
        "// TỆP SINH TỰ ĐỘNG, KHÔNG SỬA TAY.",
        "// Nguồn: docs/phan-quyen/ma-tran.json; sinh lại bằng `python3 docs/phan-quyen/gen_matrix.py`",
        "// và kiểm tra lệch bằng `npm run check:permissions` (WEB-054).",
        "",
        "/** Phiên bản ma trận phân quyền đã sinh ra tệp này. */",
        f"export const PERMISSION_MATRIX_VERSION = '{m['phien_ban']} ({m['ngay']})';",
        "",
        "/** Khoá chức năng web của ma trận (wiki/15a), dạng `SCR-qqnn.<khoá>`. */",
        "export type PermissionKey =",
    ]
    out += [f"  | '{f['key']}'" + (";" if i == len(fs) - 1 else "") for i, f in enumerate(fs)]
    out += [
        "",
        "/**",
        " * Vai trò có thể có quyền với từng chức năng, cách nhau bởi dấu cách; hậu tố `*` đánh dấu ô suy luận.",
        " * Chuỗi rỗng: không vai trò người dùng nào có quyền trên giao diện.",
        " */",
        "export const PERMISSION_TABLE: Readonly<Record<PermissionKey, string>> = {",
    ]
    for f in fs:
        cells = []
        for ac, role in roles:
            v = f["quyen"][ac]
            if v != "-":
                cells.append(role + ("*" if v.endswith("*") else ""))
        line = f"  '{f['key']}': '{' '.join(cells)}',"
        # Giữ đúng định dạng Prettier (printWidth 100) để `format:check` không báo lệch
        out += [line] if len(line) <= 100 else [f"  '{f['key']}':", f"    '{' '.join(cells)}',"]
    out += ["};", ""]
    return "\n".join(out)


def main(argv):
    d, acs = load()
    blocks = md_blocks(d, acs)
    old = WIKI.read_text(encoding="utf-8")
    new = render_md(old, blocks)
    ts_new = ts_source(d)
    ts_old = TS.read_text(encoding="utf-8") if TS.exists() else ""
    if "--check" in argv:
        bad = [p for p, a, b in ((WIKI, new, old), (TS, ts_new, ts_old)) if a != b]
        for p in bad:
            print(f"{p.relative_to(ROOT)} lệch với {DATA.relative_to(ROOT)}; chạy lại gen_matrix.py", file=sys.stderr)
        if bad:
            return 1
        print("Markdown và TypeScript khớp dữ liệu.")
        return 0
    if "--ts" not in argv and new != old:
        WIKI.write_text(new, encoding="utf-8")
    if "--md-only" not in argv and ts_new != ts_old:
        TS.write_text(ts_new, encoding="utf-8")
    if "--md-only" not in argv and "--ts" not in argv:
        write_xlsx(d, acs)
    s = stats(d)
    print(f"Đã sinh: {s['n']} chức năng ({s['web']} web), {s['scr']} màn hình, {s['inferred']} ô suy luận.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

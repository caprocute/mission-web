#!/usr/bin/env python3
"""Sinh ma trận phân quyền từ một nguồn dữ liệu duy nhất.

Nguồn: docs/phan-quyen/ma-tran.json (sửa ma trận ở đây, không sửa bảng sinh ra).
Đầu ra:
  - docs/phan-quyen/ma-tran-phan-quyen.xlsx (6 sheet: Chú giải, Vai trò, Ma trận chức năng, API, Mức dữ liệu, SoD)
  - các khối bảng giữa cặp dấu <!-- BEGIN GEN:<tên> --> ... <!-- END GEN:<tên> --> trong wiki/15a-ma-tran-phan-quyen.md
  - frontend/projects/auth/src/lib/permissions.generated.ts (bảng quyền phía giao diện cho `@vt/auth`, WEB-054)
  - chỉ với --rego: backend/opa/okrkpi/generated/data.json và backend/opa/okrkpi/matrix_cases_test.rego (NEN-040)

Dùng:
  python3 docs/phan-quyen/gen_matrix.py           # sinh lại xlsx, Markdown và tệp TypeScript
  python3 docs/phan-quyen/gen_matrix.py --check   # chỉ kiểm tra Markdown và TypeScript có khớp dữ liệu không (dùng trong CI)
  python3 docs/phan-quyen/gen_matrix.py --md-only # chỉ sinh lại Markdown
  python3 docs/phan-quyen/gen_matrix.py --ts      # chỉ sinh lại tệp TypeScript
  python3 docs/phan-quyen/gen_matrix.py --rego    # chỉ sinh lại dữ liệu và ca kiểm thử của gói OPA (NEN-040)
  python3 docs/phan-quyen/gen_matrix.py --rego-check  # kiểm tra dữ liệu OPA có khớp ma trận không

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
        for ac, sub in f.get("vai_tro_con", {}).items():
            role = next(r for r in d["vai_tro"] if r["ac"] == ac)
            if f"vai trò con `{sub}`" not in role.get("keycloak", "") or f["quyen"][ac] == "-":
                raise SystemExit(f"{f['key']}: vai_tro_con {ac}={sub} không khai báo ở vai_tro hoặc ô là '-'")
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
                # Ô chỉ dành cho vai trò con (ví dụ `wf_template_author` của AC-01 ở SCR-1505, BS-01)
                role = f.get("vai_tro_con", {}).get(ac, role)
                cells.append(role + ("*" if v.endswith("*") else ""))
        line = f"  '{f['key']}': '{' '.join(cells)}',"
        # Giữ đúng định dạng Prettier (printWidth 100) để `format:check` không báo lệch
        out += [line] if len(line) <= 100 else [f"  '{f['key']}':", f"    '{' '.join(cells)}',"]
    out += ["};", ""]
    # Khoá năng lực nền (`mo_man_hinh: false`), ví dụ tra khoá công khai để mã hoá nội dung mật: không
    # tính là "vào được màn hình" khi gom khoá cho menu, lối tắt và guard tuyến (YC-PT-21).
    bg = [f["key"] for f in fs if f.get("mo_man_hinh") is False]
    out += [
        "/** Khoá năng lực nền, không mở màn hình: `screenKeys()` bỏ qua khi gom khoá cho menu và tuyến. */",
    ]
    head = "export const NON_ENTRY_KEYS: readonly PermissionKey[] = ["
    one = head + ", ".join(f"'{k}'" for k in bg) + "];"
    # Giữ định dạng Prettier: một dòng khi vừa printWidth 100
    out += [one, ""] if len(one) <= 100 else [head, *[f"  '{k}'," for k in bg], "];", ""]
    # Vai trò phạm vi nền tảng (`pham_vi` "N", AC-01): làm việc ở vùng điều phối toàn cục, không cần phân
    # công trong tenant, nên shell không chặn khi tài khoản không có phân công (QT-00, BR-00-07).
    plat = [role for ac, role in roles if next(r for r in d["vai_tro"] if r["ac"] == ac)["pham_vi"] == "N"]
    out += [
        "/** Vai trò phạm vi nền tảng (vùng điều phối toàn cục): không cần phân công đơn vị trong tenant. */",
        "export const PLATFORM_SCOPE_ROLES: readonly string[] = [" + ", ".join(f"'{r}'" for r in plat) + "];",
        "",
    ]
    # Ma trận API (YC-PT-21): giao diện ẩn phần tử gắn với một API mà vai trò không được gọi (ví dụ ô
    # tìm kiếm toàn cục) và cảnh báo khi màn hình gọi API ngoài ma trận; máy chủ vẫn kiểm quyền.
    out += [
        "/** Module API của ma trận (`/api/v1/<module>`). */",
        "export type ApiModule =",
    ]
    apis = d.get("api", [])
    out += [f"  | '{a['module']}'" + (";" if i == len(apis) - 1 else "") for i, a in enumerate(apis)]
    out += [
        "",
        "/**",
        " * Vai trò được gọi từng phương thức của module API, cách nhau bởi dấu cách; hậu tố `*` đánh dấu",
        " * ô suy luận. Phương thức không có trong bảng: không vai trò người dùng nào được gọi.",
        " */",
        "export const API_PERMISSION_TABLE: Readonly<",
        "  Record<ApiModule, Readonly<Partial<Record<string, string>>>>",
        "> = {",
    ]
    for a in apis:
        by_method = {}
        for ac, role in roles:
            for tok in a["quyen"].get(ac, "-").split():
                if tok == "-":
                    continue
                meth = tok.rstrip("*")
                by_method.setdefault(meth, []).append(role + ("*" if tok.endswith("*") else ""))
        if not by_method:
            out.append(f"  {a['module']}: {{}},")
            continue
        out.append(f"  {a['module']}: {{")
        for meth in HTTP_METHODS:
            if meth in by_method:
                line = f"    {meth}: '{' '.join(by_method[meth])}',"
                wrapped = f"      '{' '.join(by_method[meth])}',"
                # Prettier chỉ xuống dòng khi chuỗi xuống dòng vừa printWidth 100
                out += [line] if len(line) <= 100 or len(wrapped) > 100 else [f"    {meth}:", wrapped]
        out.append("  },")
    out += ["};", ""]
    return "\n".join(out)


# ------------------------------------------------------------------ OPA/Rego (NEN-040)

REGO_DIR = ROOT / "backend" / "opa" / "okrkpi"
REGO_DATA = REGO_DIR / "generated" / "data.json"
REGO_CASES = REGO_DIR / "matrix_cases_test.rego"
# Danh mục quyền cho vai trò tuỳ chỉnh (CORE-08-18, BS-04): GET /config/permission-catalog của MOD-08
CATALOG = (ROOT / "backend" / "mission-core" / "src" / "main" / "resources" / "vn" / "viettel" / "okrkpi" / "config"
           / "domain" / "permission-catalog.json")
# Bản sao cho dữ liệu giả lập của web (`useMockApi`), cùng nội dung với bản của mission-core.
MOCK_CATALOG = ROOT / "frontend" / "projects" / "api-client" / "src" / "mock" / "custom-roles" / "permission-catalog.json"
# Vai trò không dùng làm vai trò gốc của vai trò tuỳ chỉnh (BS-04 mục giới hạn; ba vai trò cần duyệt thứ hai)
NOT_CUSTOMIZABLE = {"tenant_admin", "platform_admin", "decider", "second_approver", "policy_author"}
# Tên chức năng hiển thị cho người quản trị (YC-PT-24): bỏ ghi chú kỹ thuật trong ngoặc, mã trạng thái, mã màn hình.
TECH_PAREN_RE = re.compile(r"\s*\((?:[^()]*(?:`|SCR-|tác vụ|bước \d|phiên bản n|JSON|PDF/A|C2|step-up|thư ký|chủ trì)[^()]*)\)")
TEN_HIEN_THI = {
    "SCR-0102.buoc-4-5": "Khai báo đăng nhập tập trung, kiểm tra kết nối, nhập tổ chức ban đầu",
    "SCR-0102.kich-hoat": "Kích hoạt tenant",
    "SCR-0102.huy": "Huỷ yêu cầu khởi tạo tenant",
    "SCR-0105.duyet": "Duyệt thứ hai hoặc từ chối đề nghị cấp vai trò cần duyệt",
    "SCR-0503.nhan-viec": "Nhận việc",
    "SCR-0602.rut": "Rút lại đề nghị đang chờ duyệt của mình",
    "SCR-0902.xem": "Xem hàng đợi, chi tiết hồ sơ, mở minh chứng",
    "SCR-0902.xuat": "Xuất danh sách hàng đợi",
    "SCR-0903.tai-mau-1b": "Tải Mẫu 1B đã ký",
    "SCR-1101.thu-ky": "Ghi ý kiến tại hội nghị, lập biên bản",
    "SCR-1101.ket-thuc": "Kết thúc hội nghị, khởi động ký biên bản",
    "SCR-1301.duyet-1": "Phê duyệt thứ nhất, ký số",
    "SCR-1301.duyet-2": "Phê duyệt thứ hai, ký số, mở lại kỳ",
    "SCR-1302.xuat": "Xuất bằng chứng kèm chữ ký",
    "SCR-1602.chia-se": "Chia sẻ bảng theo dõi (riêng, đơn vị, toàn tenant)",
    "SCR-1603.xuat": "Xuất chuỗi bằng chứng",
    "SCR-1801.quay-lui": "Quay lui về phiên bản tương thích",
    "SCR-0801.xem": "Xem danh sách kỳ, lệnh khoá kỳ và tiến trình chốt kỳ",
    "SCR-1102.tai-goi": "Tải gói hồ sơ hội nghị",
    "SCR-1502.soan": "Soạn bảng quyết định, lưu nháp, xuất tệp",
    "SCR-1505.de-nghi-phat-hanh": "Đề nghị phát hành mẫu luồng kèm tham số và kiểm thử",
}


# Ngoặc chứa từ kỹ thuật bị bỏ hẳn; từ kỹ thuật ngoài ngoặc thay theo bảng từ của wiki/16 (YC-PT-24).
TECH_WORD_PAREN_RE = re.compile(r"\s*\((?:[^()]*(?:SSO|VNeID|MFA|wizard|desktop|saga|BS-\d|four-eyes|UI Pack|"
                                r"mẫu \+ tham số|nội bộ nền tảng|Viettel|BA cấu hình)[^()]*)\)")
TU_THAY_THE = [("Policy Pack", "bộ quy định đánh giá"), ("UI Pack", "bộ giao diện"), ("tenant", "đơn vị triển khai"),
               ("Tenant", "Đơn vị triển khai"), (", BA cấu hình", ""), (" (four-eyes)", ""),
               ("lên engine từng cell", "trên từng cụm máy chủ"), ("saga", "tiến trình"),
               ("xác thực MFA", "xác thực nhiều lớp"), ("tải theo cell", "tải theo cụm máy chủ"),
               ("trên engine kiểm thử riêng", "trên môi trường kiểm thử riêng"), (" cell", " cụm máy chủ")]


# Tên vai trò trên giao diện, trùng ROLE_CODE_LABEL của @vt/api-client (baseline mục 10).
TEN_VAI_TRO = {
    "platform_admin": "Quản trị nền tảng",
    "tenant_admin": "Quản trị đơn vị",
    "policy_author": "Quản trị nghiệp vụ",
    "leader": "Lãnh đạo đơn vị",
    "manager": "Cán bộ quản lý trực tiếp",
    "member": "Cán bộ, công chức, viên chức",
    "appraiser": "Cán bộ thẩm định",
    "decider": "Người có thẩm quyền quyết định",
    "second_approver": "Người duyệt thứ hai",
    "inspector": "Cán bộ kiểm tra, giám sát",
}


def clean_name(ten):
    """Tên hiển thị không còn mã thiết kế hay từ kỹ thuật (YC-PT-10, YC-PT-24; wiki/16)."""
    out = TECH_WORD_PAREN_RE.sub("", TECH_PAREN_RE.sub("", ten))
    for a, b in TU_THAY_THE:
        out = out.replace(a, b)
    out = out.strip()
    return out[:1].upper() + out[1:]


def display_name(key, ten):
    return clean_name(TEN_HIEN_THI.get(key, ten))

SCOPE_CODES = ("B", "Gn", "Đv", "Cd", "Tn", "N")
SCOPE_TOKEN_RE = re.compile(r"(?<![\w-])(B|Gn|Đv|Cd|Tn|N)(?![\w-])")
HTTP_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")
# Vai trò AC-11 không phải realm role: OPA nhận chủ thể `kind = "client"` và tra theo đúng tên trong ma trận.
CLIENT_AC = "AC-11"

# Người tham gia một đối tượng mà máy chủ truyền vào `input.resource.participants` (danh sách user_id).
# Năm vai của TL-02a "Nguyên tắc tách vai trò" là initiator, acceptor, appraiser, decider, period_locker.
PARTICIPANTS = {
    "initiator": "Người khởi tạo, soạn, lập hoặc trình đối tượng",
    "submitter": "Người nộp sản phẩm, minh chứng",
    "lead": "Người chủ trì nhiệm vụ",
    "collaborator": "Người phối hợp nhiệm vụ",
    "acceptor": "Người nghiệm thu",
    "appraisee": "Người được đánh giá (chủ hồ sơ)",
    "appraiser": "Người thẩm định",
    "proposer": "Người đề xuất mức xếp loại",
    "decider": "Người quyết định",
    "period_locker": "Người khởi chạy khoá kỳ",
    "approver": "Người đã phê duyệt (bước trước)",
    "requester": "Người gửi đề nghị hoặc yêu cầu",
    "grantor": "Người cấp vai trò",
    "grantee": "Người được cấp vai trò",
    "alert_subject": "Đối tượng của cảnh báo",
}

# Chuẩn hoá 32 dòng `rang_buoc` (văn bản tự do) thành quy tắc máy đọc được. Khoá là STT 1-based như sheet SoD;
# mỗi mục ghi lại `scr` và `loai` để phát hiện khi ma trận đổi mà bảng này chưa cập nhật (sinh lỗi, không đoán).
# kind:
#   aal2_roles            vai trò chỉ có hiệu lực trong phiên AAL2 (tên lấy từ chính quy tắc)
#   step_up               cần `acr = aal2` và `auth_time` trong `params.step_up_max_age_seconds`
#   aal2_session          cần phiên `acr = aal2`
#   separation_of_duties  chủ thể không được thuộc các nhóm `subject_not_in` của đối tượng
#   disjoint              nhóm `participant` không được giao với các nhóm `not_in` (kiểm nội dung yêu cầu)
#   four_eyes             như separation_of_duties nhưng lý do là `four_eyes_required`
#   hidden_from_subject   đối tượng của cảnh báo không thấy cho tới khi có kết luận (`resource.concluded`)
#   obligation            không đổi quyết định, chỉ gắn cờ nghĩa vụ cho service (`flag`)
#   sensitive_role_grant  cấp vai trò nhạy cảm (tên lấy từ quy tắc) gắn cờ cần duyệt thứ hai
RANG_BUOC_MAP = {
    1: ("SCR-0001", "Mức xác thực", [{"kind": "aal2_roles"}]),
    2: ("SCR-0102", "Step-up MFA", [
        {"kind": "step_up", "functions": ["SCR-0102.kich-hoat"]},
        {"kind": "aal2_session", "functions": ["SCR-0102.buoc-1-3", "SCR-0102.buoc-4-5", "SCR-0102.huy"]}]),
    3: ("SCR-0104", "Step-up MFA", [{"kind": "aal2_session", "functions": ["SCR-0104.xu-ly-lech"]}]),
    4: ("SCR-0203", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0203.duyet", "SCR-0203.duyet-lo"], "subject_not_in": ["initiator"]}]),
    5: ("SCR-0403", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0403.duyet-ky"], "subject_not_in": ["initiator"]}]),
    6: ("SCR-0403", "Step-up MFA", [{"kind": "step_up", "functions": ["SCR-0403.duyet-ky"]}]),
    7: ("SCR-0501, SCR-0502", "Tách vai trò", [
        {"kind": "disjoint", "functions": ["SCR-0501.giao", "SCR-0501.giao-dong-loat", "SCR-0502.xac-nhan-giao"],
         "participant": "acceptor", "not_in": ["lead", "collaborator"]}]),
    8: ("SCR-0601", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0601.duyet-de-nghi"], "subject_not_in": ["requester"]}]),
    9: ("SCR-0703", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0703.xac-nhan", "SCR-0703.bo-sung-tu-choi"],
         "subject_not_in": ["submitter", "lead", "collaborator"], "exception": "small_unit"}]),
    10: ("SCR-0703", "Step-up MFA", [{"kind": "step_up", "functions": ["SCR-0703.xac-nhan"]}]),
    11: ("SCR-0801", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0801.duyet-lenh"], "subject_not_in": ["initiator"]},
        {"kind": "separation_of_duties", "functions": ["SCR-0801.khoi-chay"], "subject_not_in": ["approver"]}]),
    12: ("SCR-0801", "Step-up MFA", [
        {"kind": "step_up", "functions": ["SCR-0801.khoi-chay", "SCR-0801.dieu-khien", "SCR-0801.de-nghi-huy"]}]),
    13: ("SCR-0801", "Bốn mắt", [
        {"kind": "four_eyes", "functions": ["SCR-0801.duyet-huy"], "subject_not_in": ["requester"]}]),
    14: ("SCR-0901", "Step-up MFA", [{"kind": "aal2_session", "functions": ["SCR-0901.ky-nop"]}]),
    15: ("SCR-0902, SCR-0903, SCR-0904", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-0902.ky", "SCR-0902.tra-lai"],
         "subject_not_in": ["appraisee", "acceptor", "decider"]},
        {"kind": "separation_of_duties", "functions": ["SCR-0903.de-xuat"],
         "subject_not_in": ["appraisee", "appraiser", "decider"]},
        {"kind": "separation_of_duties", "functions": ["SCR-0904.ky-lo", "SCR-0904.doi-muc"],
         "subject_not_in": ["appraisee", "acceptor", "appraiser"]}]),
    16: ("SCR-0904", "Step-up MFA", [{"kind": "step_up", "functions": ["SCR-0904.ky-lo"]}]),
    17: ("SCR-0902, SCR-1603", "Mức dữ liệu", [
        {"kind": "obligation", "functions": ["SCR-0902.xem", "SCR-1603.mo-c2"], "when_classification": "C2",
         "flag": "c2_access_log"},
        {"kind": "obligation", "functions": ["SCR-0902.xuat"], "flag": "exclude_c2_fields"},
        {"kind": "step_up", "functions": ["SCR-1603.xuat"], "when_classification": "C2"}]),
    18: ("SCR-1002", "Mức dữ liệu", [
        {"kind": "obligation", "functions": ["SCR-1002.xem", "SCR-1002.xep-nhom", "SCR-1002.trinh"],
         "flag": "c2_no_identified_export"}]),
    19: ("SCR-1101", "Mức dữ liệu", [
        {"kind": "obligation", "functions": ["SCR-1101.gop-y", "SCR-1101.thu-ky", "SCR-1101.ket-thuc"],
         "flag": "c2_anonymous_feedback"}]),
    20: ("SCR-1102", "Step-up MFA", [{"kind": "step_up", "functions": ["SCR-1102.ky-goi"]}]),
    21: ("SCR-1202", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-1202.tiep-nhan", "SCR-1202.tinh-thu", "SCR-1202.mo-lai"],
         "subject_not_in": ["acceptor", "appraiser", "proposer", "decider"]}]),
    22: ("SCR-1301", "Bốn mắt", [
        {"kind": "four_eyes", "functions": ["SCR-1301.duyet-1"], "subject_not_in": ["requester", "appraiser", "decider"]},
        {"kind": "four_eyes", "functions": ["SCR-1301.duyet-2"],
         "subject_not_in": ["requester", "appraiser", "decider", "approver"]}]),
    23: ("SCR-1301", "Step-up MFA", [{"kind": "step_up", "functions": ["SCR-1301.duyet-1", "SCR-1301.duyet-2"]}]),
    24: ("SCR-1401", "Ẩn với đối tượng", [
        {"kind": "hidden_from_subject", "functions": ["SCR-1401.xem"], "participant": "alert_subject"},
        {"kind": "separation_of_duties", "functions": ["SCR-1401.xu-ly", "SCR-1401.ket-luan"],
         "subject_not_in": ["alert_subject"]}]),
    25: ("SCR-1503", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-1503.ky-duyet"], "subject_not_in": ["initiator"]},
        {"kind": "aal2_session", "functions": ["SCR-1503.ky-duyet"]}]),
    26: ("SCR-1801", "Tách vai trò", [
        {"kind": "separation_of_duties", "functions": ["SCR-1801.duyet"], "subject_not_in": ["initiator"]},
        {"kind": "aal2_session", "functions": ["SCR-1801.duyet"]}]),
    27: ("SCR-1601", "Quyền của người bấm", [
        {"kind": "obligation", "functions": ["SCR-1601.nhac", "SCR-1601.giao-lai"], "flag": "act_as_caller"}]),
    28: ("SCR-0202, SCR-0502, SCR-1701", "AI chỉ đọc", [
        {"kind": "obligation", "functions": ["SCR-0202.ai-soat", "SCR-0502.ai-trich", "SCR-1701.hoi"],
         "flag": "ai_no_c2_log"}]),
    29: ("SCR-0105", "Bốn mắt", [
        {"kind": "four_eyes", "functions": ["SCR-0105.duyet"], "subject_not_in": ["grantor", "grantee"]},
        {"kind": "sensitive_role_grant", "functions": ["SCR-0105.cap"], "flag": "second_approval_required"}]),
    30: ("SCR-0203, SCR-0601, SCR-0703, SCR-0107", "Uỷ quyền", [
        {"kind": "obligation", "functions": ["SCR-0203.duyet", "SCR-0601.duyet-de-nghi", "SCR-0703.xac-nhan"],
         "flag": "record_delegation", "when_delegate": True}]),
    31: ("SCR-1505", "Bốn mắt", [
        {"kind": "four_eyes", "functions": ["SCR-1505.phe-duyet"], "subject_not_in": ["initiator"]},
        {"kind": "step_up", "functions": ["SCR-1505.phe-duyet"]}]),
    32: ("SCR-1506", "Giới hạn thao tác", [
        {"kind": "obligation", "functions": ["SCR-1506.chuyen-nguoi-xu-ly", "SCR-1506.gia-han-buoc"],
         "flag": "ledger_record"}]),
}
NEEDS_FUNCTIONS = {"step_up", "aal2_session", "separation_of_duties", "disjoint", "four_eyes",
                   "hidden_from_subject", "obligation", "sensitive_role_grant"}


def _cell(v):
    """'T/Đv*' -> ('T', 'Đv', True); 'A' -> ('A', None, False); '-' -> None."""
    if v == "-":
        return None
    inferred = v.endswith("*")
    v = v.rstrip("*")
    level, _, scope = v.partition("/")
    return level, (scope or None), inferred


def _classification(text):
    t = text.strip()
    inferred = "*" in t
    if t == "-" or t.startswith("Không bao giờ"):
        return {"allowed": False, "inferred": inferred, "note": t}
    if t.startswith("Chỉ khi là người nhận"):
        return {"allowed": True, "recipient_only": True, "inferred": inferred, "note": t}
    scopes = sorted(set(SCOPE_TOKEN_RE.findall(t)), key=SCOPE_CODES.index)
    out = {"allowed": True, "inferred": inferred, "note": t}
    if scopes:
        out["scopes"] = scopes
    elif t.startswith("Theo người dùng"):
        out["basis"] = "user"
    else:
        out["basis"] = "contract"
    return out


def _backtick_roles(text, roles):
    found = re.findall(r"`([a-z_]+)`", text)
    bad = [r for r in found if r not in roles]
    if bad:
        raise SystemExit(f"Quy tắc nêu vai trò không có trong vai_tro: {', '.join(bad)}")
    return found


def rego_data(d):
    """Dữ liệu cho gói `okrkpi.authz`, nạp ở `data.okrkpi.generated`. Tất định: khoá sắp xếp, không có thời gian sinh."""
    by_ac = {r["ac"]: r for r in d["vai_tro"]}
    role_of = {r["ac"]: r["role"] for r in d["vai_tro"]}
    fkeys = {f["key"] for f in d["chuc_nang"]}

    # Ràng buộc
    if set(RANG_BUOC_MAP) != set(range(1, len(d["rang_buoc"]) + 1)):
        raise SystemExit("RANG_BUOC_MAP không khớp số dòng rang_buoc; cập nhật bảng chuẩn hoá trong gen_matrix.py")
    constraints, by_function = {}, {}
    aal2_roles, sensitive = [], []
    for stt, (scr, loai, rules) in sorted(RANG_BUOC_MAP.items()):
        src = d["rang_buoc"][stt - 1]
        if (src["scr"], src["loai"]) != (scr, loai):
            raise SystemExit(f"rang_buoc[{stt}] đã đổi ({src['scr']} / {src['loai']}); cập nhật RANG_BUOC_MAP")
        for i, rule in enumerate(rules, 1):
            rid = f"{stt}.{i}"
            c = dict(rule)
            c.update({"stt": stt, "scr": scr, "loai": loai, "nguon": src["nguon"]})
            if c["kind"] in NEEDS_FUNCTIONS:
                for k in c["functions"]:
                    if k not in fkeys:
                        raise SystemExit(f"RANG_BUOC_MAP {rid}: khoá chức năng {k} không có trong ma trận")
                    by_function.setdefault(k, []).append(rid)
            for p in c.get("subject_not_in", []) + c.get("not_in", []) + ([c["participant"]] if "participant" in c else []):
                if p not in PARTICIPANTS:
                    raise SystemExit(f"RANG_BUOC_MAP {rid}: nhóm tham gia {p} chưa khai báo")
            if c["kind"] == "aal2_roles":
                aal2_roles = _backtick_roles(src["quy_tac"], role_of.values())
                c["roles"] = aal2_roles
            if c["kind"] == "sensitive_role_grant":
                sensitive = _backtick_roles(src["quy_tac"], role_of.values())
                c["roles"] = sensitive
            constraints[rid] = c

    # Vai trò, kể cả vai trò con khai báo ở cột keycloak
    roles = {}
    for r in d["vai_tro"]:
        kind = "client" if r["ac"] == CLIENT_AC else ("ai" if r["role"] == "ai_readonly" else "user")
        roles[r["role"]] = {"ac": r["ac"], "kind": kind, "aal2": r["role"] in aal2_roles,
                            "default_scope": r["pham_vi"] if r["pham_vi"] in SCOPE_CODES else None}
        for sub in re.findall(r"vai trò con `([a-z_]+)`", r.get("keycloak", "")):
            roles[sub] = {"ac": r["ac"], "kind": "user", "parent": r["role"], "aal2": r["role"] in aal2_roles,
                          "default_scope": roles[r["role"]]["default_scope"]}

    functions = {}
    for f in d["chuc_nang"]:
        grants = {}
        for ac, v in f["quyen"].items():
            c = _cell(v)
            if c is None:
                continue
            level, scope, inferred = c
            role = f.get("vai_tro_con", {}).get(ac, role_of[ac])
            grants[role] = {"level": level, "scope": scope, "inferred": inferred}
        functions[f["key"]] = {"scr": f["scr"], "qt": f["qt"], "web": f["web"], "grants": grants,
                               "constraints": by_function.get(f["key"], [])}

    api = {}
    for row in d["api"]:
        m = {}
        for ac, v in row["quyen"].items():
            if v == "-":
                continue
            toks = v.split()
            m[role_of[ac]] = {"methods": sorted({t.rstrip("*") for t in toks}, key=HTTP_METHODS.index),
                              "inferred_methods": sorted({t.rstrip("*") for t in toks if t.endswith("*")},
                                                         key=HTTP_METHODS.index)}
        api[row["module"]] = {"roles": m, "outside_baseline": row["ngoai_baseline"]}

    classification = {role_of[r["ac"]]: {k: _classification(r[k]) for k in ("C0", "C1", "C2", "C3")}
                      for r in d["muc_du_lieu"]}

    m = d["meta"]
    return {
        "meta": {"source": "docs/phan-quyen/ma-tran.json", "matrix_version": f"{m['phien_ban']} ({m['ngay']})",
                 "generated_by": "python3 docs/phan-quyen/gen_matrix.py --rego",
                 "note": "TỆP SINH TỰ ĐỘNG, KHÔNG SỬA TAY"},
        "scopes": list(SCOPE_CODES),
        "client_role": role_of[CLIENT_AC],
        "aal2_roles": aal2_roles,
        "sensitive_roles": sensitive,
        "params": {"aal2_acr": "aal2", "step_up_max_age_seconds": 300},
        "participants": PARTICIPANTS,
        "roles": roles,
        "functions": functions,
        "api": api,
        "classification": classification,
        "constraints": constraints,
    }


def _rego_obj(o):
    return json.dumps(o, ensure_ascii=False, sort_keys=True, separators=(", ", ": "))


def rego_cases(data):
    """Ca kiểm thử sinh từ ma trận: ô có quyền -> cho phép và (nếu phạm vi hẹp hơn tenant) một ca ngoài phạm vi;
    ô '-' -> role_not_allowed. API: mọi module x vai trò x phương thức."""
    users = [r for r, v in data["roles"].items()]
    fcases = []
    for key, f in sorted(data["functions"].items()):
        fcases.append(f["qt"])
        for role in sorted(users):
            g = f["grants"].get(role)
            if g is None:
                # vai trò cha của vai trò con chỉ có quyền qua vai trò con; vai trò con chỉ có quyền ở ô của mình
                fcases.append({"key": key, "role": role, "expect": "role_not_allowed"})
                continue
            fcases.append({"key": key, "role": role, "level": g["level"], "scope": g["scope"], "expect": "allow",
                           "inferred": g["inferred"]})
            if g["scope"] in ("B", "Gn", "Đv", "Cd", "Tn"):
                fcases.append({"key": key, "role": role, "level": g["level"], "scope": g["scope"],
                               "expect": "out_of_scope"})
    acases = []
    for mod, row in sorted(data["api"].items()):
        for role in sorted(users):
            g = row["roles"].get(role, {"methods": [], "inferred_methods": []})
            for meth in HTTP_METHODS:
                ok = meth in g["methods"]
                acases.append({"module": mod, "role": role, "method": meth,
                               "expect": "allow" if ok else "role_not_allowed",
                               "inferred": meth in g["inferred_methods"]})
    # Chia theo quy trình QT để mỗi test chạy gọn trong thời hạn mặc định của `opa test` (5 giây)
    groups, qt = {}, None
    for c in fcases:
        if isinstance(c, str):
            qt = c
            continue
        groups.setdefault(qt, []).append(c)
    total = sum(len(v) for v in groups.values())
    out = [
        "# TỆP SINH TỰ ĐỘNG, KHÔNG SỬA TAY.",
        "# Nguồn: docs/phan-quyen/ma-tran.json; sinh lại bằng `python3 docs/phan-quyen/gen_matrix.py --rego`.",
        "# Ca kiểm thử theo từng ô ma trận; bộ chạy nằm ở matrix_test.rego (NEN-040).",
        "package okrkpi.authz_matrix_test",
        "",
        "import rego.v1",
        "",
        f"# {total} ca chức năng: ô có quyền -> allow (+ out_of_scope khi phạm vi B, Gn, Đv, Cd, Tn); ô '-' -> role_not_allowed",
    ]
    for g in sorted(groups):
        name = g.lower().replace("-", "_")
        out += ["", f"test_function_matrix_{name} if function_group_ok(\"{g}\")", "",
                f"function_cases[\"{g}\"] := ["]
        out += [f"\t{_rego_obj(c)}," for c in groups[g]]
        out += ["]"]
    # Ca API chia theo module, cùng lý do thời hạn như trên
    out += ["", f"# {len(acases)} ca API: module x vai trò x phương thức"]
    for mod in sorted({c["module"] for c in acases}):
        out += ["", f"test_api_matrix_{mod} if api_group_ok(\"{mod}\")", "", f"api_cases[\"{mod}\"] := ["]
        out += [f"\t{_rego_obj(c)}," for c in acases if c["module"] == mod]
        out += ["]"]
    out += [""]
    return "\n".join(out)


def catalog(d, data):
    """Danh mục quyền theo nhóm quy trình > màn hình > chức năng, dạng `PermissionCatalog` của config.yaml.
    Phạm vi viết theo hợp đồng (`Dv` thay cho `Đv`)."""
    api_scope = lambda s: "Dv" if s == "Đv" else s
    roles = []
    for r in d["vai_tro"]:
        meta = data["roles"][r["role"]]
        if meta["kind"] != "user":
            continue
        roles.append({"code": r["role"], "name": TEN_VAI_TRO.get(r["role"], clean_name(r["ten"])), "description": clean_name(r["mo_ta"]), "aal2": meta["aal2"],
                      "customizable": r["role"] not in NOT_CUSTOMIZABLE})
    groups = []
    for qt, qt_name in d["quy_trinh"].items():
        screens = []
        for scr, scr_name in d["man_hinh"].items():
            fns = []
            for f in d["chuc_nang"]:
                if f["qt"] != qt or f["scr"] != scr or not f["web"]:
                    continue
                g = data["functions"][f["key"]]["grants"]
                grants = {role: {"level": v["level"], "scope": api_scope(v["scope"]), "inferred": v["inferred"]}
                          for role, v in sorted(g.items())
                          if role in data["roles"] and data["roles"][role]["kind"] == "user" and v["scope"]}
                if grants:
                    fns.append({"key": f["key"], "name": display_name(f["key"], f["ten"]), "grants": grants})
            if fns:
                screens.append({"code": scr, "name": clean_name(scr_name), "functions": fns})
        if screens:
            groups.append({"code": qt, "name": clean_name(qt_name), "screens": screens})
    return {"version": data["meta"]["matrix_version"], "roles": roles, "groups": groups}


def rego_outputs(d):
    data = rego_data(d)
    return {REGO_DATA: json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            REGO_CASES: rego_cases(data),
            CATALOG: json.dumps(catalog(d, data), ensure_ascii=False, indent=1) + "\n",
            MOCK_CATALOG: json.dumps(catalog(d, data), ensure_ascii=False, indent=1) + "\n"}


def main(argv):
    d, acs = load()
    if "--rego" in argv or "--rego-check" in argv:
        # Chỉ đụng tới backend/opa (NEN-040); không sinh lại xlsx, Markdown hay TypeScript
        outs = rego_outputs(d)
        if "--rego-check" in argv:
            bad = [p for p, t in outs.items() if not p.exists() or p.read_text(encoding="utf-8") != t]
            for p in bad:
                print(f"{p.relative_to(ROOT)} lệch với {DATA.relative_to(ROOT)}; chạy lại gen_matrix.py --rego", file=sys.stderr)
            if bad:
                return 1
            print("Dữ liệu và ca kiểm thử OPA khớp ma trận.")
            return 0
        for p, t in outs.items():
            p.parent.mkdir(parents=True, exist_ok=True)
            if not p.exists() or p.read_text(encoding="utf-8") != t:
                p.write_text(t, encoding="utf-8")
        print(f"Đã sinh dữ liệu OPA và danh mục quyền: {len(outs)} tệp.")
        return 0
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

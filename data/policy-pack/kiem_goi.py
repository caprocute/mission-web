"""Kiểm tra tĩnh bản nháp Policy Pack (DL-01, DL-02, DL-04): YAML, biến CEL đã khai báo, miền tham số,
DMN phủ kín [0;100], task_lifecycle theo bộ trạng thái chuẩn. Cần PyYAML.
Cách chạy (từ gốc repo):  python -I data/policy-pack/kiem_goi.py .
Đây là kiểm tra tạm cho tới khi có kiểm thử nạp gói bằng vt-cel trong mission-core (CORE-05-04)."""
import sys, re, json, glob, itertools, os
from decimal import Decimal, ROUND_HALF_UP
import xml.etree.ElementTree as ET
import yaml

REPO = sys.argv[1]
PP = os.path.join(REPO, "data/policy-pack")
errors, notes = [], []
def err(m): errors.append(m)

# ---------- 1. YAML parse ----------
docs = {}
for p in sorted(glob.glob(PP + "/**/*.yaml", recursive=True)):
    try:
        docs[p] = yaml.safe_load(open(p, encoding="utf-8"))
    except Exception as e:
        err(f"YAML lỗi {p}: {e}")
print(f"[1] YAML parse: {len(docs)} tệp")

# ---------- 2. CEL: biến đã khai báo ----------
KW = {"true", "false", "null", "in", "int", "double", "string", "bool", "uint", "bytes", "list", "map", "math", "has", "size", "sum", "min_muc", "ngay_lam_viec", "dyn", "type"}
FUNCS_OK = {"sum", "min_muc", "ngay_lam_viec", "double", "int", "string", "size", "has"}
MATH_OK = {"least", "greatest", "abs", "ceil", "floor", "round"}
MACRO = re.compile(r"\.(map|filter|all|exists|exists_one)\(\s*([A-Za-z_]\w*)\s*,")
BANNED = re.compile(r"\b(now|timestamp|duration|getDate|getFullYear|random|rand)\s*\(")

def strip_strings(e):
    return re.sub(r"'[^']*'|\"[^\"]*\"", "''", e)

def cel_idents(expr):
    e = strip_strings(expr)
    bound = {m.group(2) for m in MACRO.finditer(e)}
    ids, funcs = set(), set()
    for m in re.finditer(r"[A-Za-z_]\w*", e):
        name, s, t = m.group(0), m.start(), m.end()
        if s > 0 and e[s - 1] == ".":
            if e[s - 2:s] != ".." and re.match(r"\s*\(", e[t:]) and e[max(0, s - 5):s - 1] == "math":
                if name not in MATH_OK: err(f"hàm math.{name} không được phép")
            continue
        if re.match(r"\s*\(", e[t:]):
            funcs.add(name); continue
        if re.match(r"\d", name): continue
        ids.add(name)
    return ids - bound - KW, funcs, bound

def check_expr(where, expr, avail, params, types_fields):
    if BANNED.search(expr): err(f"{where}: dùng hàm phụ thuộc thời gian/ngẫu nhiên")
    ids, funcs, bound = cel_idents(expr)
    for f in funcs - FUNCS_OK:
        err(f"{where}: hàm {f}() không thuộc danh sách cho phép")
    for i in ids - set(avail):
        err(f"{where}: biến '{i}' chưa khai báo")
    for m in re.finditer(r"\btham_so\.([A-Za-z_][\w.]*)", strip_strings(expr)):
        path = m.group(1).rstrip(".")
        parts = path.split(".")
        if not any(".".join(parts[:k]) in params for k in range(1, len(parts) + 1)):
            err(f"{where}: tham số tham_so.{path} chưa khai báo")
    for var, fields in types_fields.items():
        for m in re.finditer(r"\b" + var + r"\.([A-Za-z_]\w*)", strip_strings(expr)):
            if m.group(1) not in fields and m.group(1) != "size":
                err(f"{where}: trường {var}.{m.group(1)} không có trong kiểu")
    return ids

n_expr = 0
def check_pack(path, d):
    global n_expr
    params = d.get("params", {})
    types = d.get("types", {})
    inputs = set(d.get("inputs", {}))
    ngu = d.get("ngu_canh", {})
    def ctx_for(scope):
        out = {"tham_so"}
        for v, spec in ngu.items():
            ps = spec.get("pham_vi"); ps = ps if isinstance(ps, list) else [ps]
            if scope in ps: out.add(v)
        return out
    tf = {}
    for v, spec in ngu.items():
        t = spec.get("type")
        if t in types: tf[v] = set(types[t])
    order = list(d.get("definitions", {}).keys())
    done_ho_so = set()
    for scope in order:
        avail = ctx_for(scope)
        if scope in ("ho_so", "xep_loai"):
            avail |= inputs | done_ho_so
        for key, r in d["definitions"][scope].items():
            for f in ("expression", "result_type", "legal_ref", "eval_order", "overridable"):
                if f not in r: err(f"{path} {scope}.{key}: thiếu trường {f}")
            if r.get("result_type") not in ("DECIMAL", "BOOLEAN", "STRING"): err(f"{key}: result_type sai")
            if len(r.get("legal_ref", "")) > 200: err(f"{key}: legal_ref > 200")
            check_expr(f"{os.path.basename(os.path.dirname(path))}:{scope}.{key}", r["expression"], avail, params, tf)
            n_expr += 1
            avail.add(key)
            if scope in ("ho_so", "xep_loai"): done_ho_so.add(key)
    # năm
    if "definitions_nam" in d:
        avail = {"tham_so"} | set(d.get("inputs_nam", {}))
        for scope, rules in d["definitions_nam"].items():
            for key, r in rules.items():
                check_expr(f"nam.{key}", r["expression"], avail, params, {}); n_expr += 1; avail.add(key)
    if "phan_nhom" in d:
        pn = d["phan_nhom"]; avail = {"tham_so"} | set(pn["inputs"])
        for key, r in pn["definitions"].items():
            check_expr(f"phan_nhom.{key}", r["expression"], avail, params, {}); n_expr += 1
    # eval_order duy nhất
    eo = [r["eval_order"] for s in d.get("definitions", {}).values() for r in s.values()]
    if len(eo) != len(set(eo)): err(f"{path}: eval_order trùng")
    # tham số: mặc định trong miền
    for k, p in params.items():
        m, v = p.get("mien"), p.get("mac_dinh")
        if m and "enum" in m and v not in m["enum"]: err(f"{k}: mặc định {v} ngoài enum")
        if m and "min" in m and not (m["min"] <= v <= m["max"]): err(f"{k}: mặc định {v} ngoài [{m['min']},{m['max']}]")
    nhom = params["nhom"]["mac_dinh"]; tong = sum(g["diem_toi_da"] for g in nhom)
    exp = params["nhom"]["rang_buoc"]["tong_diem_toi_da"]
    if tong != exp: err(f"{path}: tổng điểm tối đa nhóm {tong} != {exp}")
    if d["metadata"]["luong"] == "NHA_NUOC" and tong + params["diem_toi_da_tieu_chi_chung"]["mac_dinh"] != 100: err("NN: 30+70 != 100")
    md = d["metadata"]
    if md["phe_duyet"]["nguoi_soan"] or md["phe_duyet"]["nguoi_duyet"] or md["phe_duyet"]["ky_so"]: err(f"{path}: phe_duyet phải để trống")
    if not re.fullmatch(r"\d+\.\d+\.\d+", md["version"]): err("version không semver")
    if not isinstance(md.get("gia_lap"), bool): err(f"{path}: thiếu cờ metadata.gia_lap (YC-PT-14)")
    elif md["gia_lap"] and not md.get("ghi_chu_gia_lap"): err(f"{path}: bản giả lập phải có ghi_chu_gia_lap")
    return tong

for p, d in docs.items():
    if p.endswith("/policy.yaml"):
        t = check_pack(p, d)
        print(f"[2] {os.path.relpath(p, PP)}: CEL ok tới đây; tổng điểm nhóm = {t}")
print(f"[2] Tổng biểu thức CEL đã kiểm: {n_expr}")

# ---------- 3. DMN ----------
NS = "{https://www.omg.org/spec/DMN/20211108/MODEL/}"
def parse_test(t):
    t = t.strip()
    if t == "-": return lambda v: True
    parts = [x.strip() for x in t.split(",")] if t.startswith('"') else [t]
    fns = []
    for x in parts:
        m = re.fullmatch(r"(<=|>=|<|>)\s*(-?[\d.]+)", x)
        if m:
            op, n = m.group(1), float(m.group(2))
            fns.append({"<": lambda v, n=n: v < n, "<=": lambda v, n=n: v <= n, ">": lambda v, n=n: v > n, ">=": lambda v, n=n: v >= n}[op]); continue
        m = re.fullmatch(r"([\[\(\]])\s*(-?[\d.]+)\s*\.\.\s*(-?[\d.]+)\s*([\]\)\[])", x)
        if m:
            lo_inc, a, b, hi_inc = m.group(1) == "[", float(m.group(2)), float(m.group(3)), m.group(4) == "]"
            fns.append(lambda v, a=a, b=b, li=lo_inc, hi=hi_inc: (v >= a if li else v > a) and (v <= b if hi else v < b)); continue
        if x in ("true", "false"):
            fns.append(lambda v, x=x: v == (x == "true")); continue
        if re.fullmatch(r'"[^"]*"', x):
            fns.append(lambda v, x=x[1:-1]: v == x); continue
        if re.fullmatch(r"-?[\d.]+", x):
            fns.append(lambda v, n=float(x): v == n); continue
        raise ValueError(f"FEEL không hỗ trợ trong bộ kiểm: {x}")
    return lambda v: any(f(v) for f in fns)

def domain(name, typ, values, entries):
    if typ == "boolean": return [True, False]
    if typ == "string": return [s.strip()[1:-1] for s in values.split(",")]
    if name.startswith("so_quy"): return [0, 1, 2, 3, 4]
    lo, hi = (0.0, 100.0) if name.startswith("diem") else (0.0, 1.0)
    pts = {lo, hi, (lo + hi) / 2}
    for e in entries:
        for n in re.findall(r"-?\d+(?:\.\d+)?", e):
            n = float(n); pts |= {n, n - 0.001, n + 0.001}
    return sorted(p for p in pts if lo <= p <= hi)

def run(rows, inputs, values):
    for i, r in enumerate(rows):
        if all(r["tests"][k](values[k]) for k in range(len(inputs))): return i
    return None

dmn_tables = {}
for p in sorted(glob.glob(PP + "/**/*.dmn", recursive=True)):
    tree = ET.parse(p); root = tree.getroot()
    if root.tag != NS + "definitions": err(f"{p}: không phải DMN 1.4 definitions")
    dt = root.find(f"{NS}decision/{NS}decisionTable")
    if dt.get("hitPolicy") != "FIRST": err(f"{p}: hitPolicy khác FIRST")
    ids = [e.get("id") for e in root.iter() if e.get("id")]
    if len(ids) != len(set(ids)): err(f"{p}: id trùng")
    inputs = []
    for inp in dt.findall(NS + "input"):
        ie = inp.find(NS + "inputExpression"); iv = inp.find(NS + "inputValues")
        inputs.append((ie.find(NS + "text").text, ie.get("typeRef"), iv.find(NS + "text").text if iv is not None else None))
    ann = [a.get("name") for a in dt.findall(NS + "annotation")]
    outv = [s.strip()[1:-1] for s in dt.find(f"{NS}output/{NS}outputValues/{NS}text").text.split(",")]
    rows = []
    for r in dt.findall(NS + "rule"):
        ents = [e.find(NS + "text").text for e in r.findall(NS + "inputEntry")]
        out = r.find(f"{NS}outputEntry/{NS}text").text.strip()[1:-1]
        anns = dict(zip(ann, [e.find(NS + "text").text for e in r.findall(NS + "annotationEntry")]))
        if out not in outv: err(f"{p}: kết quả {out} ngoài outputValues")
        if len(anns.get("legal_ref", "")) > 100: err(f"{p} {r.get('id')}: legal_ref > 100 ký tự")
        gt = anns.get("giai_thich", "")
        if not gt or len(gt) > 200: err(f"{p} {r.get('id')}: giải thích rỗng hoặc > 200 ký tự ({len(gt)})")
        bad = set(re.findall(r"\{(\w+)\}", gt)) - {"muc_tren", "muc", "diem", "nguong", "dieu_kien_thieu", "gia_tri", "dieu_khoan", "phien_ban", "cac_quy"}
        if bad: err(f"{p}: biến mẫu câu lạ {bad}")
        rows.append({"id": r.get("id"), "ents": ents, "out": out, "tests": [parse_test(e) for e in ents], "ann": anns})
    doms = [domain(n, t, v, [r["ents"][i] for r in rows]) for i, (n, t, v) in enumerate(inputs)]
    hit = [0] * len(rows); gaps = 0; total = 0; overlaps = set()
    for combo in itertools.product(*doms):
        total += 1
        k = run(rows, inputs, combo)
        if k is None: gaps += 1; continue
        hit[k] += 1
        for j in range(k + 1, len(rows)):
            if all(rows[j]["tests"][x](combo[x]) for x in range(len(inputs))) and rows[j]["out"] != rows[k]["out"]:
                overlaps.add((k + 1, j + 1))
    shadow = [rows[i]["id"] for i, h in enumerate(hit) if h == 0]
    if gaps: err(f"{p}: {gaps} điểm không khớp dòng nào (khoảng hở)")
    if shadow: err(f"{p}: dòng bị che {shadow}")
    rel = os.path.relpath(p, PP)
    print(f"[3] {rel}: XML hợp lệ, {len(rows)} dòng, {total} tổ hợp đầu vào, khoảng hở={gaps}, dòng bị che={len(shadow)}, "
          f"cặp dòng chồng lấn khác kết quả (cố ý theo FIRST)={sorted(overlaps)}")
    dmn_tables[os.path.basename(p)] = (inputs, rows)

# ca biên sau làm tròn (DL-01 làm tròn 1 chữ số, nửa lên)
def r1(x): return float(Decimal(str(x)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))
inp, rows = dmn_tables["xep-loai-dang.dmn"]
def xl(**kw):
    v = tuple(kw[n] for n, _, _ in inp); return rows[run(rows, inp, v)]["out"]
base = dict(muc_tieu_chi_chung="THUC_HIEN_TOT", loai_tru=False, ty_le_O=1.0, dat_ty_le_vuot=True, giai_phap_dot_pha_da_kiem_chung=True, truot_then_chot_chu_quan=False)
cases = [(59.995, "HTNV"), (59.94, "KHTNV"), (89.995, "HTXSNV"), (89.94, "HTTNV")]
for raw, exp in cases:
    got = xl(diem=r1(raw), **base)
    print(f"[3] Ca biên Đảng: điểm thô {raw} -> {r1(raw)} -> {got} (mong đợi {exp})")
    if got != exp: err(f"ca biên {raw}: {got} != {exp}")
got = xl(diem=91.2, **dict(base, dat_ty_le_vuot=False))
print(f"[3] Hồ sơ mẫu SCR-1501 (91,2 điểm, vượt 22% < 30%) -> {got} (mong đợi HTTNV, dòng 5)")
if got != "HTTNV": err("TC-017 sai")
inpn, rowsn = dmn_tables["xep-loai-nha-nuoc.dmn"]
for raw, exp in [(89.995, "HTXSNV"), (69.95, "HTTNV"), (49.94, "KHTNV"), (50, "HTNV")]:
    got = rowsn[run(rowsn, inpn, (r1(raw),))]["out"]
    print(f"[3] Ca biên Nhà nước: {raw} -> {r1(raw)} -> {got} (mong đợi {exp})")
    if got != exp: err(f"NN ca biên {raw}")

# ---------- 3b. Tính thử ba hồ sơ mẫu bằng Python (mô phỏng công thức CEL) ----------
def kpi(nv, tru=0.05):
    A = 1.0 if nv["ke_hoach"] == 0 else min(nv["so_luong_duyet"] / nv["ke_hoach"], 1.0)
    C = max(0.0, 1.0 - tru * nv["ngay_lam_viec_cham_chu_quan"])
    return A, nv["muc_chat_luong"], C, (A + nv["muc_chat_luong"] + C) / 3
def diem_dang(ds, nhom=(("THUONG_XUYEN", 30), ("THEN_CHOT", 70))):
    s = 0
    for ma, mx in nhom:
        k = [kpi(n)[3] for n in ds if n["nhom"] == ma]
        s += (sum(k) / len(k) if k else 0.0) * mx
    return r1(s)
mau = {
  "Kế hoạch bằng 0": [dict(nhom="THEN_CHOT", ke_hoach=0, so_luong_duyet=0, muc_chat_luong=0.75, ngay_lam_viec_cham_chu_quan=0),
                      dict(nhom="THUONG_XUYEN", ke_hoach=0, so_luong_duyet=0, muc_chat_luong=0.75, ngay_lam_viec_cham_chu_quan=0)],
  "Chậm 21 ngày, C không âm": [dict(nhom="THEN_CHOT", ke_hoach=4, so_luong_duyet=4, muc_chat_luong=1, ngay_lam_viec_cham_chu_quan=21),
                               dict(nhom="THUONG_XUYEN", ke_hoach=2, so_luong_duyet=3, muc_chat_luong=1, ngay_lam_viec_cham_chu_quan=0)],
  "Đủ mọi điều kiện": [dict(nhom="THEN_CHOT", ke_hoach=5, so_luong_duyet=6, muc_chat_luong=1, ngay_lam_viec_cham_chu_quan=0),
                       dict(nhom="THUONG_XUYEN", ke_hoach=3, so_luong_duyet=3, muc_chat_luong=1, ngay_lam_viec_cham_chu_quan=0)],
}
for ten, ds in mau.items():
    print(f"[3b] {ten}: A,B,C,KPI từng nhiệm vụ = {[tuple(round(x,3) for x in kpi(n)) for n in ds]}; điểm Đảng = {diem_dang(ds)}")
    for n in ds:
        A, B, C, K = kpi(n)
        if not (0 <= A <= 1 and C >= 0 and 0 <= K <= 1): err(f"bất biến sai {ten}")
import random
random.seed(7)
for _ in range(10000):
    ds = [dict(nhom=random.choice(["THEN_CHOT", "THUONG_XUYEN"]), ke_hoach=random.randint(0, 20), so_luong_duyet=random.randint(0, 30),
               muc_chat_luong=random.choice([1, .75, .5, .25, 0]), ngay_lam_viec_cham_chu_quan=random.randint(0, 60)) for _ in range(random.randint(0, 8))]
    d = diem_dang(ds)
    if not 0 <= d <= 100: err("điểm ngoài [0;100]")
print("[3b] Thử ngẫu nhiên 10.000 bộ (mô phỏng Python, không phải vt-cel): điểm ∈ [0;100], A ≤ 1, C ≥ 0")

# ---------- 4. task_lifecycle ----------
STD = {"NHAP", "DA_GIAO", "DANG_THUC_HIEN", "CHO_NGHIEM_THU", "HOAN_THANH", "TAM_DUNG", "HUY"}
roles = {r["role"] for r in json.load(open(os.path.join(REPO, "docs/phan-quyen/ma-tran.json"), encoding="utf-8"))["vai_tro"]}
tl = docs[os.path.join(PP, "khoi-dang/quy-dinh-moi/task_lifecycle.yaml")]
if set(tl["trang_thai"]) != STD: err("tập trạng thái khác baseline 9.1")
seen = set()
tf = {k: set(v["fields"]) for k, v in tl["ngu_canh"].items()}
for t in tl["transitions"]:
    for s in (t["from"], t["to"]):
        if s not in STD: err(f"trạng thái {s} ngoài baseline")
    key = f'{t["from"]}:{t["command"]}'
    if key in seen: err(f"trùng {key}")
    seen.add(key)
    if t["command"] not in tl["lenh"]: err(f"lệnh {t['command']} lạ")
    for r in t["allowedRoles"]:
        if r not in roles: err(f"vai trò {r} không có trong ma trận")
    if t["guardCel"]:
        check_expr(f"guard {key}", t["guardCel"], set(tl["ngu_canh"]), {}, {"actor": tf["actor"], "task": tf["task"], "change_request": tf["change_request"]})
        g = strip_strings(t["guardCel"])
        if not re.search(r"==|!=|>=|<=|<|>|\.all\(|\.exists\(|&&|\|\|", g): err(f"guard {key} có thể không trả bool")
print(f"[4] task_lifecycle: {len(tl['transitions'])} dòng, không trùng from:command, mọi trạng thái thuộc baseline, vai trò thuộc ma trận ({sorted(roles)})")
if "qua_han" in tl["trang_thai"]: err("qua_han không được là trạng thái")

# ---------- 5. workflow bindings ----------
for p, d in docs.items():
    if "/workflow-bindings/" in p:
        m = d["mien"]
        if not m["so_cap"]["min"] <= d["params"]["so_cap"] <= m["so_cap"]["max"] or not 2 <= d["params"]["so_cap"] <= 4: err(f"{p}: so_cap ngoài miền")
        if len(d["params"]["cap"]) != d["params"]["so_cap"]: err(f"{p}: số cấp không khớp")
        for c in d["params"]["cap"]:
            vt = c["vai_tro"] if isinstance(c["vai_tro"], list) else [c["vai_tro"]]
            for r in vt:
                if r not in roles: err(f"{p}: vai trò {r} lạ")
            for k, v in c.items():
                if k.startswith("thoi_han"):
                    n = int(re.fullmatch(r"P(\d+)D_LV", v).group(1))
                    if not 1 <= n <= 15: err(f"{p}: {k} ngoài 1..15")
        print(f"[5] {os.path.relpath(p, PP)}: so_cap={d['params']['so_cap']}, thời hạn trong 1..15 ngày làm việc, vai trò hợp lệ")

# ---------- 6. văn bản hiển thị không chứa mã kỹ thuật ----------
CODE = re.compile(r"\b(BR|QT|SCR|MSG|ADR|SVC|MOD|EVT|NFR|AC)-\d|\b(HTXSNV|HTTNV|HTNV|KHTNV)\b|\b[A-Z]{2,}_[A-Z_]+\b|Policy Pack|\bPL \d|§")
def walk(o, path=""):
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("mo_ta", "cach_xu_ly", "nhan", "criteria", "name", "ten") and isinstance(v, str):
                if CODE.search(v): err(f"văn bản hiển thị chứa mã: {path}.{k} = {v}")
            walk(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o): walk(v, f"{path}[{i}]")
for p, d in docs.items():
    if p.endswith("policy.yaml"):
        walk({k: v for k, v in d.items() if k in ("truong_hop_dac_thu", "classification", "phan_nhom", "metadata")})
        for k, v in d.get("classification", {}).get("mau_cau", {}).items():
            if CODE.search(v) or len(v) > 1000: err(f"mẫu câu {k} chứa mã hoặc quá dài")
        for k, v in d.get("classification", {}).get("nhan_hien_thi", {}).get("muc_xep_loai", {}).items():
            if CODE.search(v): err(f"nhãn {k}")
for name, (inp, rows) in dmn_tables.items():
    for r in rows:
        for a in ("giai_thich", "dieu_khoan"):
            if CODE.search(r["ann"].get(a, "")): err(f"{name} {r['id']} {a} chứa mã: {r['ann'][a]}")
d = docs[os.path.join(PP, "khoi-dang/quy-dinh-moi/policy.yaml")]
th = d["truong_hop_dac_thu"]
EFF = {"KHONG_XEP_LOAI", "XEP_LOAI_TAI_DON_VI_CU", "BAO_LUU", "XEP_LOAI_BINH_THUONG", "CHO_KET_LUAN"}
if [t["muc"] for t in th] != list(range(1, 14)): err("13 trường hợp không đủ hoặc sai thứ tự")
for t in th:
    if len(t["ma"]) > 32: err(f"mã {t['ma']} > 32")
    if t["hieu_ung"] not in EFF: err(f"hiệu ứng {t['hieu_ung']} ngoài CHECK TL-03")
    if len(t["legal_ref"]) > 100: err("legal_ref trường hợp > 100")
if len({t["ma"] for t in th}) != 13: err("mã trường hợp trùng")
print(f"[6] Văn bản hiển thị: đã quét nhãn, mô tả, cách xử lý, giải thích DMN; 13 trường hợp đặc thù, mã ≤ 32 ký tự, hiệu ứng theo CHECK TL-03")

print()
if errors:
    print(f"KẾT QUẢ: {len(errors)} LỖI"); [print(" -", e) for e in errors]; sys.exit(1)
print("KẾT QUẢ: ĐẠT, 0 lỗi")

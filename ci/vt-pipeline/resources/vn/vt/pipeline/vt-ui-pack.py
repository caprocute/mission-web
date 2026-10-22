#!/usr/bin/env python3
"""Kiểm UI Pack trước khi phát hành (INF-06, OPS-11; TL-01 chương 7 "Cấu trúc UI Pack").

    vt-ui-pack.py kiem <pack.json> --registry-src <glob>... [--nguong-kb 30] [--css-ra pack.css] [--bao-cao kq.json]
    vt-ui-pack.py trang-mau <pack.json> <trang.html>      # trang mẫu cho axe-core, chỉ dùng biến CSS của gói
    vt-ui-pack.py than-api <pack.json> <ra.json>          # thân UiPackVersionUpdate cho updateUiPackVersion

Các cổng của lệnh `kiem` (hỏng bất kỳ cổng nào trả mã 1):
  1. lược đồ: JSON Schema 2020-12 của UI Pack, kiểm bằng thư viện jsonschema (thông dịch lược đồ, không sinh
     mã lúc chạy, đúng AGENTS.md quy tắc 5);
  2. registry: mọi `component` trong layouts có trong registry SDUI (đọc tên `vt.*` từ mã nguồn registry);
  3. an toàn: không chuỗi nào chứa thẻ <script>, `javascript:`, thuộc tính sự kiện `on...=`; plugin chỉ trỏ
     tới https và đường dẫn tương đối;
  4. chế độ người cao tuổi: có `modes.elderly` với cờ `reducedSteps` (NFR-11);
  5. kích thước: CSS biên dịch (hoặc `compiledCss` có sẵn) ≤ ngưỡng KB sau gzip (TL-01, ngân sách UI Pack 30 KB).
CSS biên dịch giống `tokensToCss` của @vt/design-system để con số khớp với shell.
"""
import argparse, glob, gzip, json, re, sys

LUOC_DO = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["pack", "version", "requires", "tokens", "layouts"],
    "additionalProperties": False,
    "$defs": {
        "token": {"type": "object", "required": ["$value"],
                  "properties": {"$type": {"type": "string"}, "$value": {}, "sha256": {"type": "string"}}},
        "nhom": {"type": "object", "additionalProperties": {"anyOf": [{"$ref": "#/$defs/token"}, {"$ref": "#/$defs/nhom"}]}},
        "nut": {"type": "object", "required": ["component"], "additionalProperties": False,
                "properties": {"component": {"type": "string", "pattern": "^vt\\.[a-z][A-Za-z0-9.-]*$"},
                               "props": {"type": "object"}, "visibleWhen": {"type": "string", "maxLength": 500},
                               "children": {"type": "array", "items": {"$ref": "#/$defs/nut"}}}},
    },
    "properties": {
        "$schema": {"type": "string"},
        "pack": {"type": "string", "pattern": "^[a-z0-9][a-z0-9/_-]{1,127}$"},
        "version": {"type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$"},
        "requires": {"type": "object", "required": ["registry"],
                     "properties": {"registry": {"type": "string"}, "policyPack": {"type": "string"}}},
        "tokens": {"$ref": "#/$defs/nhom"},
        "modes": {"type": "object", "additionalProperties": {"type": "object"}},
        "compiledCss": {"type": "string"},
        "layouts": {"type": "object", "minProperties": 1, "additionalProperties": {"$ref": "#/$defs/nut"}},
        "forms": {"type": "object", "additionalProperties": {"type": "object", "required": ["schemaRef"],
                  "properties": {"schemaRef": {"type": "string"}, "ui": {}}}},
        "plugins": {"type": "array", "items": {"type": "object", "additionalProperties": False,
                    "required": ["name", "remoteEntry", "exposedModule", "routePath", "title"],
                    "properties": {k: {"type": "string"} for k in ["name", "remoteEntry", "exposedModule", "routePath", "title"]}}},
        "i18n": {"type": "object", "additionalProperties": {"type": "object", "additionalProperties": {"type": "string"}}},
    },
}
NGUY_HIEM = re.compile(r"<\s*script|javascript:|\bon[a-z]+\s*=|<\s*iframe|expression\s*\(", re.I)


def ten_css(k):
    return re.sub(r"([a-z])([A-Z])", r"\1-\2", k).lower()


def gia_tri(t):
    if t.get("$type") == "asset":
        return None
    v = t["$value"]
    if isinstance(v, list):
        return ", ".join(f'"{x}"' if re.search(r"\s", str(x)) else str(x) for x in v)
    return str(v) if isinstance(v, (str, int, float)) and not isinstance(v, bool) else None


def phang(nhom, tien_to="--vt"):
    ra = {}
    for k, n in nhom.items():
        if k.startswith("$") or k == "flags":
            continue
        ten = f"{tien_to}-{ten_css(k)}"
        if isinstance(n, dict) and "$value" in n:
            g = gia_tri(n)
            if g is not None:
                ra[ten] = g
        elif isinstance(n, dict):
            ra.update(phang(n, ten))
    return ra


def bien_dich(p):
    khoi = lambda sel, d: sel + "{" + ";".join(f"{k}:{v}" for k, v in d.items()) + "}"
    phan = [khoi(":root[data-tenant]", phang(p["tokens"]))]
    for m, g in (p.get("modes") or {}).items():
        phan.append(khoi(f':root[data-tenant][data-mode="{m}"]', phang(g)))
    return "\n".join(phan)


def cac_chuoi(o):
    if isinstance(o, str):
        yield o
    elif isinstance(o, dict):
        for k, v in o.items():
            yield from cac_chuoi(k); yield from cac_chuoi(v)
    elif isinstance(o, list):
        for v in o:
            yield from cac_chuoi(v)


def cac_component(n):
    yield n["component"]
    for c in n.get("children", []):
        yield from cac_component(c)


def kiem(a):
    import jsonschema
    p = json.load(open(a.pack, encoding="utf-8"))
    kq = []
    ghi = lambda ma, dat, tb: kq.append({"code": ma, "passed": dat, "message": tb})
    loi = sorted(jsonschema.Draft202012Validator(LUOC_DO).iter_errors(p), key=lambda e: list(e.path))
    ghi("LUOC_DO", not loi, "; ".join(f"/{'/'.join(map(str, e.path))}: {e.message}" for e in loi[:10]) or "đúng lược đồ")
    if loi:
        return p, kq
    reg = set()
    for g in a.registry_src:
        for f in glob.glob(g, recursive=True):
            reg |= set(re.findall(r"name:\s*'(vt\.[A-Za-z0-9.-]+)'", open(f, encoding="utf-8").read()))
    dung = {c for n in p["layouts"].values() for c in cac_component(n)}
    thieu = sorted(dung - reg)
    ghi("REGISTRY", bool(reg) and not thieu, f"component ngoài registry: {', '.join(thieu)}" if thieu
        else f"{len(dung)} component đều có trong registry ({len(reg)} mục)" if reg else "không đọc được registry")
    xau = sorted({s[:80] for s in cac_chuoi({k: v for k, v in p.items() if k != "compiledCss"}) if NGUY_HIEM.search(s)})
    pl = [x["remoteEntry"] for x in p.get("plugins") or [] if not re.match(r"^(https://|/)", x["remoteEntry"])]
    ghi("AN_TOAN", not xau and not pl, "; ".join(xau + [f"plugin không https: {u}" for u in pl]) or "không có mã chạy được")
    el = (p.get("modes") or {}).get("elderly") or {}
    ghi("NGUOI_CAO_TUOI", (el.get("flags") or {}).get("reducedSteps") is True,
        "có chế độ elderly với reducedSteps" if el else "thiếu modes.elderly hoặc cờ reducedSteps")
    css = p.get("compiledCss") or bien_dich(p)
    if a.css_ra:
        open(a.css_ra, "w", encoding="utf-8").write(css)
    kb = len(gzip.compress(css.encode(), 9)) / 1024
    ghi("KICH_THUOC", kb <= a.nguong_kb, f"CSS {kb:.1f} KB gzip, ngưỡng {a.nguong_kb} KB")
    return p, kq


def trang_mau(a):
    p = json.load(open(a.pack, encoding="utf-8"))
    css = p.get("compiledCss") or bien_dich(p)
    ten = {k: v for k, v in phang(p["tokens"]).items()}
    mau = lambda tk, mac_dinh: f"var({tk},{mac_dinh})" if tk in ten else mac_dinh
    # Trang mẫu dựng từ các vai trò màu của gói theo cách preset @vt/design-system dùng (nút, liên kết lấy
    # primary-strong, chữ phụ lấy text-muted); axe đo tương phản, nhãn, tiêu đề trên chính các token đó.
    html = f"""<!doctype html><html lang="vi" data-tenant="mau"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Trang mẫu UI Pack {p['pack']} {p['version']}</title>
<style>{css}
body{{margin:0;font-family:{mau('--vt-font-family','sans-serif')};font-size:{mau('--vt-font-size-body','14px')};
background:{mau('--vt-color-background','#fff')};color:{mau('--vt-color-text','#000')}}}
main{{max-width:960px;margin:auto;padding:16px}} .the{{background:{mau('--vt-color-surface','#fff')};padding:16px;
border-radius:{mau('--vt-radius-card','8px')};margin-bottom:16px}} .phu{{color:{mau('--vt-color-text-muted','#555')}}}
button{{background:{mau('--vt-color-primary-strong',mau('--vt-color-primary','#00f'))};color:{mau('--vt-color-on-primary','#fff')};border:0;padding:10px 16px;
min-height:{mau('--vt-touch-target','44px')};font:inherit;border-radius:4px}}
button.vien{{background:transparent;color:{mau('--vt-color-primary-strong','#00f')};border:1px solid currentColor}}
a{{color:{mau('--vt-color-primary-strong','#00f')}}} label{{display:block;margin:8px 0 4px}}
input{{font:inherit;padding:8px;border:1px solid {mau('--vt-color-text-muted','#555')};color:inherit;background:{mau('--vt-color-surface','#fff')}}}
</style></head><body><header class="the"><h1>Nhiệm vụ của tôi</h1><p class="phu">Cập nhật lúc 08:00</p></header>
<main><section class="the" aria-labelledby="t1"><h2 id="t1">Việc cần làm hôm nay</h2>
<p>Hoàn thiện báo cáo kết quả quý III và gửi lãnh đạo phòng duyệt.</p><p class="phu">Hạn: 25/10/2026</p>
<button type="button">Cập nhật tiến độ</button> <button type="button" class="vien">Xem chi tiết</button>
<p><a href="#ds">Xem toàn bộ nhiệm vụ</a></p></section>
<section class="the" aria-labelledby="t2"><h2 id="t2">Tự đánh giá</h2><form>
<label for="kq">Kết quả đạt được</label><input id="kq" name="kq" type="text" value="Đạt 95% chỉ tiêu">
<label for="gc">Ghi chú</label><input id="gc" name="gc" type="text"></form></section></main></body></html>"""
    open(a.ra, "w", encoding="utf-8").write(html)


def than_api(a):
    p = json.load(open(a.pack, encoding="utf-8"))
    el = (p.get("modes") or {}).get("elderly") or {}
    than = {"tokens": p["tokens"], "layouts": p["layouts"],
            "componentRegistryVersion": re.sub(r"^[^0-9]*", "", p["requires"]["registry"])[:32],
            "elderMode": bool(el), "fewSteps": bool((el.get("flags") or {}).get("reducedSteps"))}
    json.dump(than, open(a.ra, "w", encoding="utf-8"), ensure_ascii=False)


ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="lenh", required=True)
k = sp.add_parser("kiem"); k.add_argument("pack"); k.add_argument("--registry-src", nargs="+", required=True)
k.add_argument("--nguong-kb", type=float, default=30); k.add_argument("--css-ra"); k.add_argument("--bao-cao")
t = sp.add_parser("trang-mau"); t.add_argument("pack"); t.add_argument("ra")
b = sp.add_parser("than-api"); b.add_argument("pack"); b.add_argument("ra")
a = ap.parse_args()
if a.lenh == "than-api":
    than_api(a); sys.exit(0)
if a.lenh == "trang-mau":
    trang_mau(a); sys.exit(0)
_, kq = kiem(a)
for x in kq:
    print(f"  [{'ĐẠT' if x['passed'] else 'HỎNG'}] {x['code']}: {x['message']}")
if a.bao_cao:
    json.dump(kq, open(a.bao_cao, "w"), ensure_ascii=False, indent=2)
sys.exit(0 if all(x["passed"] for x in kq) and len(kq) == 5 else 1)

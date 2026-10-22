"""Kiểm tra UI Pack mặc định trước khi nạp cho tenant mới (DL-06, QT-18).

Chạy:  python3 -I data/seed/ui-pack/kiem_ui_pack.py [tệp.json ...]
(không truyền tệp thì kiểm mọi `mac-dinh-*.json` trong thư mục này).

Tệp là nội dung `updateUiPackVersion` kèm trường `version`. Tập lệnh dựng tài liệu UI Pack 1.0 giống
`UiPackService.document` của mission-core rồi kiểm cùng các quy tắc mà máy chủ chạy khi gửi duyệt,
để lỗi lộ ra trước khi gọi API:
- lược đồ `ui-pack-1.0.schema.json` của mission-core (không HTML, không JavaScript);
- component có trong manifest registry (`data/seed/component-registry/<phiên bản>.json`), props đúng
  `propsSchema`, trang chủ tối đa 12 widget (BR-18-02);
- tương phản chữ và nền ≥ 4,5 : 1 (BR-18-01); chế độ cao tuổi ≥ 7 : 1, chữ cơ sở 17–19 px, vùng chạm
  ≥ 48 px, viền 2 px (BR-18-09);
- cỡ chữ thường 12–16 px, bo góc ≤ 16 px;
- kích thước gzip ≤ 30 KB (BR-18-08).
Cặp chữ và nền suy ra từ tên token như `UiPackStaticChecks` (wiki trang 03).
"""
from __future__ import annotations

import copy
import gzip
import json
import re
import sys
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
GOC_REPO = THU_MUC.parent.parent.parent
LUOC_DO = GOC_REPO / "backend/mission-core/src/main/resources/config/ui-pack/ui-pack-1.0.schema.json"
REGISTRY = THU_MUC.parent / "component-registry"

NGAN_SACH_BYTE = 30 * 1024
TOI_DA_WIDGET = 12
AA, CAO_TUOI = 4.5, 7.0
ALIAS = re.compile(r"^\{([A-Za-z0-9_.-]+)\}$")
KICH_THUOC = re.compile(r"^(\d+(?:\.\d+)?)(px|rem)?$")


# ----- Dựng tài liệu như máy chủ -----

def tai_lieu(noi_dung: dict, pack: str = "default/ui") -> dict:
    """Tài liệu UI Pack 1.0 dựng từ nội dung phiên bản (theo `UiPackService.document`)."""
    tokens = noi_dung["tokens"]
    doc: dict = {
        "pack": pack,
        "version": noi_dung["version"],
        "requires": {"registry": noi_dung["componentRegistryVersion"]},
        "tokens": tokens,
    }
    if noi_dung.get("elderMode"):
        ghi_de = copy.deepcopy(tokens.get("$extensions", {}).get("modes", {}).get("elderly"))
        co = {"reducedSteps": bool(noi_dung.get("fewSteps"))}
        cao_tuoi: dict = {}
        if isinstance(ghi_de, dict):
            khai_bao = ghi_de.pop("flags", None) or {}
            if "minTouchTarget" in khai_bao:
                co["minTouchTarget"] = khai_bao["minTouchTarget"]
            cao_tuoi["tokens"] = ghi_de
        cao_tuoi["flags"] = co
        doc["modes"] = {"elderly": cao_tuoi}
    doc["layouts"] = noi_dung["layouts"]
    return doc


def gop(goc: dict, ghi_de: dict) -> dict:
    """Ghép token chế độ lên token gốc (giữ `$type` của gốc khi chế độ chỉ đổi `$value`)."""
    for k, v in ghi_de.items():
        if isinstance(v, dict) and isinstance(goc.get(k), dict):
            gop(goc[k], v)
        else:
            goc[k] = copy.deepcopy(v)
    return goc


def kich_thuoc_gzip(doc: dict) -> int:
    du_lieu = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return len(gzip.compress(du_lieu, mtime=0))


# ----- Token -----

def _giai(gia_tri, goc: dict, sau: int = 0):
    s = gia_tri if isinstance(gia_tri, str) else json.dumps(gia_tri)
    m = ALIAS.match(s.strip())
    if not m or sau > 8:
        return s
    dich = goc
    for phan in m.group(1).split("."):
        dich = dich.get(phan, {}) if isinstance(dich, dict) else {}
    return _giai(dich["$value"], goc, sau + 1) if isinstance(dich, dict) and "$value" in dich else s


def mau(tokens: dict) -> dict[str, str]:
    """Mọi token màu `đường.dẫn → #RRGGBB` (đã giải bí danh)."""
    ra: dict[str, str] = {}

    def duyet(nhom: dict, duong: str) -> None:
        for k, v in nhom.items():
            if k.startswith("$") or not isinstance(v, dict):
                continue
            p = f"{duong}.{k}" if duong else k
            if "$value" in v:
                ra[p] = _giai(v["$value"], tokens)
            else:
                duyet(v, p)

    duyet(tokens.get("color", {}), "")
    return ra


def _la(p: str) -> str:
    return p.rsplit(".", 1)[-1]


def _camel(ten: str, tien_to: str) -> bool:
    return len(ten) > len(tien_to) and ten.startswith(tien_to) and ten[len(tien_to)].isupper()


def _nen_cua(ten: str) -> str | None:
    if ten.startswith("on-") and len(ten) > 3:
        return ten[3:]
    if _camel(ten, "on"):
        return ten[2].lower() + ten[3:]
    return None


def cap_mau(duong: list[str]) -> list[tuple[str, str]]:
    """Cặp (chữ, nền) theo quy ước tên token trong cùng nhóm, như `UiPackStaticChecks.pairs`."""
    nhom: dict[str, list[str]] = {}
    for p in duong:
        nhom.setdefault(p.rsplit(".", 1)[0] if "." in p else "", []).append(p)
    cap: list[tuple[str, str]] = []
    for thanh_vien in nhom.values():
        for chu in thanh_vien:
            ten = _la(chu)
            tien_to = chu[: len(chu) - len(ten)]
            nen = _nen_cua(ten)
            if nen is not None and tien_to + nen in thanh_vien:
                cap.append((chu, tien_to + nen))
            elif ten == "text" or ten.startswith("text-") or _camel(ten, "text"):
                for b in thanh_vien:
                    if _la(b).startswith(("surface", "background")):
                        cap.append((chu, b))
    return cap


def _do_sang(hex_: str) -> float:
    def kenh(c: int) -> float:
        x = c / 255
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4

    r, g, b = (int(hex_[i : i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * kenh(r) + 0.7152 * kenh(g) + 0.0722 * kenh(b)


def ti_le_tuong_phan(a: str, b: str) -> float:
    la, lb = sorted((_do_sang(a), _do_sang(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def kiem_tuong_phan(tokens: dict, nguong: float, nhan: str) -> list[str]:
    mau_ = mau(tokens)
    loi: list[str] = []
    cap = cap_mau(list(mau_))
    if not cap:
        loi.append(f"{nhan}: không có cặp màu chữ và nền nào để kiểm")
    for chu, nen in cap:
        a, b = mau_[chu], mau_[nen]
        if not (re.fullmatch(r"#[0-9A-Fa-f]{6}", a) and re.fullmatch(r"#[0-9A-Fa-f]{6}", b)):
            loi.append(f"{nhan}: {chu} trên {nen} không phải mã #RRGGBB")
            continue
        r = ti_le_tuong_phan(a, b)
        if r < nguong:
            loi.append(f"{nhan}: {chu} trên {nen} chỉ đạt {r:.2f} : 1, cần ≥ {nguong} : 1")
    return loi


def px(token, goc: dict) -> float | None:
    v = token.get("$value") if isinstance(token, dict) else token
    m = KICH_THUOC.match(str(_giai(v, goc)).strip().lower())
    if not m:
        return None
    n = float(m.group(1))
    if m.group(2) is None and n != 0:
        return None
    return n * 16 if m.group(2) == "rem" else n


# ----- Bố cục -----

def kiem_bo_cuc(layouts: dict, registry_version: str) -> list[str]:
    import jsonschema

    tep = REGISTRY / f"{registry_version}.json"
    if not tep.exists():
        return [f"không có manifest registry {registry_version} trong data/seed/component-registry"]
    manifest = {c["name"]: c for c in json.loads(tep.read_text(encoding="utf-8"))["components"]}
    loi: list[str] = []

    def duyet(nut: dict, duong: str) -> None:
        ten = nut.get("component")
        c = manifest.get(ten)
        if c is None:
            loi.append(f"{duong}: component {ten} không có trong registry {registry_version}")
        else:
            for e in jsonschema.Draft202012Validator(c.get("propsSchema", {})).iter_errors(nut.get("props", {})):
                loi.append(f"{duong}.props: {e.message}")
            if nut.get("children") and not c.get("container"):
                loi.append(f"{duong}: component {ten} không được chứa nút con")
        for i, con in enumerate(nut.get("children", [])):
            duyet(con, f"{duong}.children[{i}]")

    for ten, goc in layouts.items():
        duyet(goc, f"layouts.{ten}")
    widget = len(layouts.get("home", {}).get("children", []))
    if widget > TOI_DA_WIDGET:
        loi.append(f"trang chủ có {widget} widget, tối đa {TOI_DA_WIDGET} (BR-18-02)")
    ma = [n.get("id", n.get("component")) for n in layouts.get("home", {}).get("children", [])]
    trung = {m for m in ma if ma.count(m) > 1}
    if trung:
        loi.append(f"trang chủ có widget trùng mã {sorted(trung)}; đặt `id` riêng cho từng widget")
    return loi


# ----- Tổng hợp -----

def kiem(noi_dung: dict) -> tuple[list[str], int]:
    """Trả (danh sách lỗi, kích thước gzip theo byte)."""
    import jsonschema

    doc = tai_lieu(noi_dung)
    loi = [
        f"lược đồ: {'/'.join(map(str, e.absolute_path)) or '$'}: {e.message}"
        for e in jsonschema.Draft202012Validator(json.loads(LUOC_DO.read_text(encoding="utf-8"))).iter_errors(doc)
    ]
    tokens = noi_dung["tokens"]
    loi += kiem_tuong_phan(tokens, AA, "Bộ thường (AA 4,5 : 1)")
    than = tokens.get("font", {}).get("size", {}).get("body")
    if than is not None and not (12 <= (px(than, tokens) or 0) <= 16):
        loi.append("Bộ thường: cỡ chữ cơ sở phải từ 12 px đến 16 px")
    for ten, r in tokens.get("radius", {}).items():
        if not ten.startswith("$") and (px(r, tokens) is None or px(r, tokens) > 16):
            loi.append(f"Bộ thường: bo góc radius.{ten} phải từ 0 px đến 16 px")

    cao_tuoi = doc.get("modes", {}).get("elderly")
    if not noi_dung.get("elderMode") or cao_tuoi is None:
        loi.append("Thiếu bộ token chế độ cao tuổi (elderMode phải bật, NFR-11)")
    else:
        gop_ = gop(copy.deepcopy(tokens), cao_tuoi.get("tokens", {}))
        loi += kiem_tuong_phan(gop_, CAO_TUOI, "Chế độ cao tuổi (7 : 1)")
        than = px(gop_.get("font", {}).get("size", {}).get("body", {}), gop_)
        if than is None or not (17 <= than <= 19):
            loi.append("Chế độ cao tuổi: chữ cơ sở phải từ 17 px đến 19 px")
        cham = [px(cao_tuoi["flags"].get("minTouchTarget", "0px"), gop_)]
        if "min-touch" in gop_:
            cham.append(px(gop_["min-touch"], gop_))
        if any(c is None or c < 48 for c in cham):
            loi.append("Chế độ cao tuổi: vùng chạm phải từ 48 px")
        vien = px(gop_.get("border", {}).get("width", {}), gop_)
        if vien is None or vien < 2:
            loi.append("Chế độ cao tuổi: viền phải dày từ 2 px")

    loi += kiem_bo_cuc(noi_dung["layouts"], noi_dung["componentRegistryVersion"])
    kich_thuoc = kich_thuoc_gzip(doc)
    if kich_thuoc > NGAN_SACH_BYTE:
        loi.append(f"UI Pack {kich_thuoc} byte gzip, vượt ngân sách 30 KB (BR-18-08)")
    return loi, kich_thuoc


def main(args: list[str]) -> int:
    try:
        import jsonschema  # noqa: F401
    except ImportError:
        print("Cần thư viện jsonschema: python3 -m pip install jsonschema", file=sys.stderr)
        return 2
    tep = [Path(a) for a in args] or sorted(THU_MUC.glob("mac-dinh-*.json"))
    tong_loi = 0
    for t in tep:
        loi, kich_thuoc = kiem(json.loads(t.read_text(encoding="utf-8")))
        print(f"{t.name}: {kich_thuoc} byte gzip ({kich_thuoc / 1024:.1f} KB / 30 KB)")
        for dong in loi:
            print(f"  LỖI {dong}")
        if not loi:
            print("  Đạt: lược đồ, registry, tương phản AA và 7 : 1, cỡ chữ, vùng chạm, viền, kích thước")
        tong_loi += len(loi)
    return 1 if tong_loi else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

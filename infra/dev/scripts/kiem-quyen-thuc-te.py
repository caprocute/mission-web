#!/usr/bin/env python3
"""Đối chiếu quyền giao diện với quyền máy chủ thật trên server dev, theo từng tài khoản thử (YC-PT-21).

Giao diện quyết định hiện hay ẩn một màn hình, mục menu, nút theo vai trò của phân công đang chọn và ma trận
chức năng `docs/phan-quyen/ma-tran.json`. Máy chủ quyết định lại bằng OPA theo token (realm role, acr, tenant_id,
unit_id), bảng API theo module và dữ liệu phạm vi (cây tổ chức). Hai bên lệch nhau thì người dùng thấy tính năng
nhưng vào lại nhận "Đồng chí không có quyền thực hiện thao tác này.". Script này đo đúng chỗ lệch đó:

  1. Với mỗi tài khoản người dùng của realm dev (`realm-mission.json`, `tai-khoan-da-tenant.json`): đăng nhập
     như trình duyệt (PKCE, OTP nếu realm còn hỏi), đọc claim (vai trò, acr, tenant_id, unit_id), gọi
     `GET /org/me` để biết phân công và suy ra vai trò mà giao diện sẽ dùng (như `SessionService.loadContext`).
  2. Gọi mọi thao tác GET không tham số bắt buộc của hợp đồng (`kiem-tuyen.du-lieu.json`) cùng các tuyến GET mà
     cột `api` của ma trận chức năng nêu (không tham số đường dẫn), ghi mã trạng thái và mã lỗi Problem.
  3. Phân loại từng cặp (tài khoản, tuyến) theo HAI mức:
       - mức màn hình (`LECH-MAN-HINH`): có chức năng web của ma trận mà vai trò giao diện có ô quyền, mở màn hình
         (`mo_man_hinh` khác false) và cột `api` của chức năng đó nêu tuyến này, nhưng máy chủ trả 403: người dùng
         chắc chắn thấy menu/màn hình rồi gặp lỗi;
       - mức module (`LECH-MODULE`): bảng API theo module cho vai trò giao diện GET nhưng máy chủ trả 403 (giao diện
         có thể gọi tuyến này từ ô tìm kiếm, hộp chọn hay widget).
     `AN-THUA`: cả hai bảng đều không cho nhưng máy chủ trả 2xx (giao diện đang ẩn thừa).

APISIX dev giới hạn 600 yêu cầu/phút nên script giãn nhịp (`--nhip`, mặc định 8 yêu cầu/giây) và thử lại 429 với
thời gian chờ tăng dần; `--aal2` lấy token có `acr_values=aal2` (bước lên) để so với phiên thường. Kết quả: bảng
tóm tắt ra stdout và JSON chi tiết ra tệp `--out` (mặc định kiem-quyen-<thời gian>.json). Chỉ dùng thư viện chuẩn,
chạy được với Python 3.6 của server dev, không in token, mật khẩu hay thân phản hồi chứa dữ liệu.

    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py                 # mọi tài khoản
    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py canbo tu.bithu  # một số tài khoản
    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py --aal2 --nhip 5

Biến môi trường như seed-da-tenant-api.py: SEED_API, KEYCLOAK_PUBLIC_URL (hoặc KC_URL), KC_TEST_USER_PASSWORD,
KC_TEST_OTP_SECRET (đọc thêm từ infra/dev/.env).
"""
import argparse
import base64
import collections
import hashlib
import http.cookiejar
import importlib.util
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]

_spec = importlib.util.spec_from_file_location("lay_token_thu", str(HERE / "lay-token-thu.py"))
LT = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(LT)

# Tuyến GET giao diện gọi khi mở màn hình mà hợp đồng OpenAPI hoặc ma trận chưa nêu dạng không tham số.
TUYEN_BO_SUNG = [
    "/periods", "/plans", "/catalog/items", "/evidences", "/notifications", "/workflow/templates",
    "/workflow/instances", "/workflow/tasks", "/analytics/dashboards", "/analytics/semantic-model", "/ui/packs",
    "/ui/feature-flags", "/config/me", "/config/me/saved-filters", "/registry/tenants", "/registry/cells",
    "/esign/certificates", "/esign/requests", "/search?q=a", "/config/access-history", "/tasks/source-docs",
    "/ai/suggestions", "/tasks/mine", "/okr/tree", "/org/units/tree", "/org/me",
]
API_PATH_RE = re.compile(r"/api/v1/([a-z][a-z0-9/_-]*)")
METHOD_PREFIX_RE = re.compile(r"(GET|POST|PUT|PATCH|DELETE)(?:/(?:GET|POST|PUT|PATCH|DELETE))*\s+`?$")


def doc_env():
    tep = ROOT / "infra/dev/.env"
    if not tep.is_file():
        return
    for dong in tep.read_text(encoding="utf-8").splitlines():
        dong = dong.strip()
        if dong and not dong.startswith("#") and "=" in dong:
            k, v = dong.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def claims(tok):
    p = tok.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)).decode("utf-8"))


class Nhip:
    """Giãn nhịp dùng chung cho mọi luồng: tối đa `moi_giay` yêu cầu mỗi giây."""

    def __init__(self, moi_giay):
        self.khoang = 1.0 / max(moi_giay, 0.1)
        self.khoa = threading.Lock()
        self.ke_tiep = 0.0

    def cho(self):
        with self.khoa:
            bay_gio = time.time()
            luc = max(bay_gio, self.ke_tiep)
            self.ke_tiep = luc + self.khoang
        if luc > bay_gio:
            time.sleep(luc - bay_gio)


def dang_nhap(user, acr_values=None):
    """Như lay-token-thu.py nhưng có thể xin acr_values (bước lên); trả access token."""
    kc = (os.environ.get("KC_URL") or os.environ.get("KEYCLOAK_PUBLIC_URL") or "http://127.0.0.1:35080").rstrip("/")
    realm = "mission"
    with urllib.request.urlopen("%s/realms/%s/.well-known/openid-configuration" % (kc, realm), timeout=30) as r:
        issuer = json.load(r)["issuer"].rstrip("/")
    kc = issuer[: -len("/realms/" + realm)]
    redirect_uri = os.environ.get("KC_REDIRECT_URI", "http://localhost:4200/")
    password = os.environ.get("KC_TEST_USER_PASSWORD", "")
    otp_secret = os.environ.get("KC_TEST_OTP_SECRET", "")
    base = "%s/realms/%s/protocol/openid-connect" % (kc, realm)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    stop = LT._StopAtRedirect(redirect_uri)
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy=LT._LoopbackPolicy())), stop)
    q = {"client_id": "mission-web", "redirect_uri": redirect_uri, "response_type": "code", "scope": "openid",
         "state": secrets.token_urlsafe(16), "nonce": secrets.token_urlsafe(16), "code_challenge": challenge,
         "code_challenge_method": "S256"}
    if acr_values:
        q["acr_values"] = acr_values

    def post(url, fields):
        try:
            with opener.open(url, urllib.parse.urlencode(fields).encode()) as resp:
                return resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if stop.location:
                return None
            raise RuntimeError("Keycloak trả %s khi gửi biểu mẫu" % e.code)

    with opener.open("%s/auth?%s" % (base, urllib.parse.urlencode(q))) as resp:
        page = resp.read().decode("utf-8", "replace")
    page = post(LT._form_action(page), {"username": user, "password": password, "credentialId": ""})
    if stop.location is None and page is not None:
        if 'name="otp"' not in page:
            raise RuntimeError("đăng nhập không qua")
        if not otp_secret:
            raise RuntimeError("realm hỏi OTP nhưng thiếu KC_TEST_OTP_SECRET")
        post(LT._form_action(page), {"otp": LT.totp(otp_secret)})
    if stop.location is None:
        raise RuntimeError("không nhận được mã ủy quyền")
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(stop.location).query)
    if "code" not in params:
        raise RuntimeError("Keycloak trả lỗi: %s" % params.get("error", ["?"])[0])
    body = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "mission-web",
                                   "redirect_uri": redirect_uri, "code": params["code"][0],
                                   "code_verifier": verifier}).encode()
    with urllib.request.urlopen(base + "/token", body) as resp:
        return json.load(resp)["access_token"]


def goi(api, tok, path, nhip, lan_thu=5):
    """GET một tuyến; 429 thì chờ theo Retry-After (hoặc tăng dần 2, 4, 8... giây) rồi gọi lại."""
    for lan in range(lan_thu):
        nhip.cho()
        req = urllib.request.Request(api + path)
        req.add_header("Authorization", "Bearer " + tok)
        req.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            if e.code == 429 and lan < lan_thu - 1:
                cho = e.headers.get("Retry-After")
                time.sleep(float(cho) if cho and cho.isdigit() else 2.0 * (2 ** lan))
                continue
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001
            return 0, str(e).encode()
    return 429, b""


def problem(body):
    try:
        d = json.loads(body.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    if not isinstance(d, dict):
        return {}
    return {k: d.get(k) for k in ("code", "title", "detail") if d.get(k)}


def ma_tran():
    """Bảng API theo module và bảng chức năng theo tuyến GET, đọc từ ma-tran.json (ô có * coi là cho phép)."""
    m = json.loads((ROOT / "docs/phan-quyen/ma-tran.json").read_text(encoding="utf-8"))
    ac_role = {v["ac"]: v["role"] for v in m["vai_tro"]}
    api = {}
    for dong in m["api"]:
        api[dong["module"]] = {
            ac_role[ac]: {t.rstrip("*") for t in o.split() if t != "-"} for ac, o in dong["quyen"].items()}
    # tuyến GET (không tham số đường dẫn) -> [(khoá chức năng, tên màn hình, tập vai trò có ô)]
    theo_tuyen = collections.defaultdict(list)
    for f in m["chuc_nang"]:
        if not f.get("web") or f.get("mo_man_hinh") is False:
            continue
        roles = {ac_role[k] for k, v in f["quyen"].items()
                 if k in ac_role and v not in ("-", "H") and not v.startswith("A")}
        if not roles:
            continue
        for mm in API_PATH_RE.finditer(f["api"]):
            truoc = f["api"][max(0, mm.start() - 40):mm.start()]
            pre = METHOD_PREFIX_RE.search(truoc)
            if pre and pre.group(0).split()[0].split("/")[0] != "GET":
                continue
            path = "/" + mm.group(1).rstrip("/")
            if "{" in f["api"][mm.start():mm.end() + 1]:
                continue
            theo_tuyen[path].append((f["key"], m["man_hinh"].get(f["scr"], f["scr"]), roles))
    return api, theo_tuyen


def tai_khoan(chon):
    ds = []
    for tep in ("infra/dev/keycloak/realm-mission.json", "infra/dev/keycloak/tai-khoan-da-tenant.json"):
        for u in json.loads((ROOT / tep).read_text(encoding="utf-8")).get("users", []):
            if u["username"].startswith("service-account-") or not u.get("realmRoles"):
                continue
            ds.append(u["username"])
    return [u for u in ds if not chon or u in chon]


def vai_tro_giao_dien(me, realm_roles):
    """Như SessionService.loadContext: một phân công thì dùng ngay; nhiều thì phân công chính; không có thì token."""
    ds = (me or {}).get("assignments") or []
    if not ds:
        return list(realm_roles), "token"
    if len(ds) == 1:
        return list(ds[0].get("roles") or []), "phan-cong-duy-nhat"
    chinh = next((a for a in ds if a.get("kind") == "CHINH"), ds[0])
    return list(chinh.get("roles") or []), "phan-cong-chinh(%d)" % len(ds)


def main():
    doc_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("users", nargs="*")
    ap.add_argument("--aal2", action="store_true", help="xin acr_values=aal2 khi đăng nhập")
    ap.add_argument("--nhip", type=float, default=8.0, help="số yêu cầu mỗi giây (APISIX dev: 600/phút)")
    ap.add_argument("--out", default="kiem-quyen-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
    a = ap.parse_args()
    api = os.environ.get("SEED_API", "http://127.0.0.1:35000/api/v1").rstrip("/")
    ops = json.loads((HERE / "kiem-tuyen.du-lieu.json").read_text(encoding="utf-8"))["operations"]
    bang_api, theo_tuyen = ma_tran()
    paths = sorted({o["path"][len("/api/v1"):] for o in ops} | set(TUYEN_BO_SUNG) | set(theo_tuyen))
    nhip = Nhip(a.nhip)
    ket_qua = []
    for user in tai_khoan(set(a.users)):
        try:
            tok = dang_nhap(user, "aal2" if a.aal2 else None)
        except Exception as e:  # noqa: BLE001
            print("%-22s KHÔNG ĐĂNG NHẬP ĐƯỢC: %s" % (user, e))
            ket_qua.append({"user": user, "loi_dang_nhap": str(e)})
            continue
        c = claims(tok)
        realm_roles = [r for r in (c.get("realm_access") or {}).get("roles", [])
                       if not r.startswith("default-roles") and r not in ("offline_access", "uma_authorization")]
        st, body = goi(api, tok, "/org/me", nhip)
        me = json.loads(body.decode("utf-8")) if st == 200 else None
        fe_roles, nguon_vt = vai_tro_giao_dien(me, realm_roles)
        fe_set = set(fe_roles)

        goi_ds = []
        for path in paths:
            s, b = goi(api, tok, path, nhip)
            sach = path.split("?")[0]
            mod = sach.strip("/").split("/")[0]
            quyen = bang_api.get(mod)
            module_cho = None if quyen is None else any("GET" in quyen.get(r, ()) for r in fe_set)
            man_hinh = sorted({"%s (%s)" % (ten, key) for key, ten, roles in theo_tuyen.get(sach, []) if roles & fe_set})
            if man_hinh and s == 403:
                loai = "LECH-MAN-HINH"
            elif module_cho and s == 403:
                loai = "LECH-MODULE"
            elif module_cho is False and not man_hinh and 200 <= s < 300:
                loai = "AN-THUA"
            else:
                loai = "KHOP"
            goi_ds.append({"path": path, "status": s, "module_cho": module_cho, "man_hinh": man_hinh, "loai": loai,
                           "problem": problem(b) if s >= 400 else {}})
        r = {"user": user, "acr": c.get("acr"), "tenant_id": c.get("tenant_id"), "unit_id": c.get("unit_id"),
             "realm_roles": realm_roles, "org_me": st, "phan_cong": [
                 {"unit": x.get("orgUnitName"), "roles": x.get("roles"), "kind": x.get("kind")}
                 for x in (me or {}).get("assignments", [])],
             "fe_roles": fe_roles, "nguon_vai_tro": nguon_vt, "goi": goi_ds}
        ket_qua.append(r)
        dem = collections.Counter(g["loai"] for g in goi_ds)
        print("%-22s acr=%-5s org/me=%s vai trò giao diện=%s (%s) | LỆCH màn hình %d | LỆCH module %d | 403 %d | 429 %d | ẨN THỪA %d" % (
            user, c.get("acr"), st, ",".join(fe_roles), nguon_vt, dem["LECH-MAN-HINH"], dem["LECH-MODULE"],
            sum(1 for g in goi_ds if g["status"] == 403), sum(1 for g in goi_ds if g["status"] == 429), dem["AN-THUA"]))
        for g in goi_ds:
            if g["loai"] == "LECH-MAN-HINH":
                print("    LỆCH %-40s %s | %s | %s" % (g["path"], "; ".join(g["man_hinh"])[:70],
                                                      g["problem"].get("code", ""), (g["problem"].get("detail") or "")[:60]))

    Path(a.out).write_text(json.dumps(ket_qua, ensure_ascii=False, indent=1), encoding="utf-8")
    for loai, tieu_de in (("LECH-MAN-HINH", "Màn hình giao diện hiện nhưng máy chủ 403"),
                          ("LECH-MODULE", "Bảng API cho vai trò đọc nhưng máy chủ 403")):
        dem = collections.Counter()
        for r in ket_qua:
            for g in r.get("goi", []):
                if g["loai"] == loai:
                    dem[(g["path"], g["problem"].get("code", ""), (g["problem"].get("detail") or "")[:50])] += 1
        print("\n%s (số tài khoản):" % tieu_de)
        for (p, code, detail), n in dem.most_common(40):
            print("  %3d  %-42s %s %s" % (n, p, code, detail))
    print("\nChi tiết: %s" % a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

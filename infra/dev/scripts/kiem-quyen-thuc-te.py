#!/usr/bin/env python3
"""Đối chiếu quyền giao diện với quyền máy chủ thật trên server dev, theo từng tài khoản thử (YC-PT-21).

Giao diện quyết định hiện hay ẩn một tính năng theo vai trò của phân công đang chọn và ma trận
`docs/phan-quyen/ma-tran.json` (phần API: module × phương thức). Máy chủ quyết định lại bằng OPA theo token
(realm role, acr, tenant_id, unit_id) và dữ liệu tổ chức. Hai bên lệch nhau thì người dùng thấy tính năng nhưng
vào lại nhận "Đồng chí không có quyền thực hiện thao tác này.". Script này đo đúng chỗ lệch đó:

  1. Với mỗi tài khoản người dùng của realm dev (`realm-mission.json`, `tai-khoan-da-tenant.json`): đăng nhập
     như trình duyệt (PKCE, OTP nếu realm còn hỏi), đọc claim (vai trò, acr, tenant_id, unit_id), gọi
     `GET /org/me` để biết phân công và vai trò giao diện sẽ dùng.
  2. Gọi mọi thao tác GET không tham số của hợp đồng (`kiem-tuyen.du-lieu.json`) cùng vài tuyến giao diện dùng mà
     hợp đồng chưa khai, ghi mã trạng thái và mã lỗi Problem.
  3. Phân loại từng cặp (tài khoản, thao tác): `LECH` khi ma trận cho vai trò giao diện đọc module nhưng máy chủ
     trả 403 (người dùng thấy lỗi); `AN-THUA` khi ma trận không cho nhưng máy chủ trả 2xx; còn lại `KHOP`.

`--aal2` lấy token có `acr_values=aal2` (bước lên) để so với phiên thường. Kết quả: bảng tóm tắt ra stdout và
JSON chi tiết ra tệp `--out` (mặc định kiem-quyen-<thời gian>.json). Chỉ dùng thư viện chuẩn, chạy được với
Python 3.6 của server dev, không in token, mật khẩu hay thân phản hồi chứa dữ liệu.

    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py                 # mọi tài khoản
    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py canbo tu.bithu  # một số tài khoản
    python3 -I infra/dev/scripts/kiem-quyen-thuc-te.py --aal2

Biến môi trường như seed-da-tenant-api.py: SEED_API, KEYCLOAK_PUBLIC_URL (hoặc KC_URL), KC_TEST_USER_PASSWORD,
KC_TEST_OTP_SECRET (đọc thêm từ infra/dev/.env).
"""
import argparse
import base64
import collections
import concurrent.futures
import hashlib
import http.cookiejar
import importlib.util
import json
import os
import secrets
import sys
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

# Tuyến GET giao diện gọi khi mở màn hình mà hợp đồng OpenAPI chưa khai (frontend/projects/api-client).
TUYEN_BO_SUNG = [
    "/periods", "/plans", "/catalog/items", "/evidences", "/notifications", "/workflow/templates",
    "/workflow/instances", "/workflow/tasks", "/analytics/dashboards", "/ui/packs", "/ui/feature-flags",
    "/config/me", "/config/me/saved-filters", "/registry/tenants", "/registry/cells", "/esign/certificates",
    "/esign/requests", "/search?q=a", "/config/access-history", "/tasks/source-docs", "/ai/suggestions",
]
VAI_TRO_AAL2 = {"appraiser", "decider", "second_approver", "tenant_admin", "platform_admin"}


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


def goi(api, tok, path):
    req = urllib.request.Request(api + path)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # noqa: BLE001
        return 0, str(e).encode()


def problem(body):
    try:
        d = json.loads(body.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    if not isinstance(d, dict):
        return {}
    return {k: d.get(k) for k in ("code", "title", "detail") if d.get(k)}


def bang_api():
    """{module: {role: set(phương thức)}} từ ma trận (ô có * coi là cho phép, như allowInferred mặc định)."""
    m = json.loads((ROOT / "docs/phan-quyen/ma-tran.json").read_text(encoding="utf-8"))
    ac_role = {v["ac"]: v["role"] for v in m["vai_tro"]}
    out = {}
    for dong in m["api"]:
        out[dong["module"]] = {
            ac_role[ac]: {t.rstrip("*") for t in o.split() if t != "-"} for ac, o in dong["quyen"].items()}
    return out


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
    ap.add_argument("--out", default="kiem-quyen-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
    a = ap.parse_args()
    api = os.environ.get("SEED_API", "http://127.0.0.1:35000/api/v1").rstrip("/")
    ops = json.loads((HERE / "kiem-tuyen.du-lieu.json").read_text(encoding="utf-8"))["operations"]
    paths = sorted({o["path"][len("/api/v1"):] for o in ops} | set(TUYEN_BO_SUNG))
    bang = bang_api()
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
        st, body = goi(api, tok, "/org/me")
        me = json.loads(body.decode("utf-8")) if st == 200 else None
        fe_roles, nguon_vt = vai_tro_giao_dien(me, realm_roles)

        def mot(path):
            s, b = goi(api, tok, path)
            mod = path.strip("/").split("/")[0].split("?")[0]
            quyen = bang.get(mod)
            if quyen is None:
                fe = None
            else:
                fe = any("GET" in quyen.get(r, ()) for r in fe_roles)
            if fe and s == 403:
                loai = "LECH"
            elif fe is False and 200 <= s < 300:
                loai = "AN-THUA"
            else:
                loai = "KHOP"
            return {"path": path, "status": s, "fe_cho": fe, "loai": loai, "problem": problem(b) if s >= 400 else {}}

        with concurrent.futures.ThreadPoolExecutor(8) as ex:
            goi_ds = list(ex.map(mot, paths))
        r = {"user": user, "acr": c.get("acr"), "tenant_id": c.get("tenant_id"), "unit_id": c.get("unit_id"),
             "realm_roles": realm_roles, "org_me": st, "phan_cong": [
                 {"unit": x.get("orgUnitName"), "roles": x.get("roles"), "kind": x.get("kind")}
                 for x in (me or {}).get("assignments", [])],
             "fe_roles": fe_roles, "nguon_vai_tro": nguon_vt, "goi": goi_ds}
        ket_qua.append(r)
        lech = [g for g in goi_ds if g["loai"] == "LECH"]
        print("%-22s acr=%-5s org/me=%s vai trò giao diện=%s (%s) | LỆCH %d | 403 %d | AN-THUA %d" % (
            user, c.get("acr"), st, ",".join(fe_roles), nguon_vt, len(lech),
            sum(1 for g in goi_ds if g["status"] == 403), sum(1 for g in goi_ds if g["loai"] == "AN-THUA")))
        for g in lech:
            print("    LỆCH %-45s %s %s" % (g["path"], g["problem"].get("code", ""), g["problem"].get("title", "")[:70]))

    Path(a.out).write_text(json.dumps(ket_qua, ensure_ascii=False, indent=1), encoding="utf-8")
    dem = collections.Counter()
    for r in ket_qua:
        for g in r.get("goi", []):
            if g["loai"] == "LECH":
                dem[(g["path"], g["problem"].get("code", ""))] += 1
    print("\nTuyến lệch nhiều nhất (giao diện cho, máy chủ 403):")
    for (p, code), n in dem.most_common(40):
        print("  %3d  %-45s %s" % (n, p, code))
    print("\nChi tiết: %s" % a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

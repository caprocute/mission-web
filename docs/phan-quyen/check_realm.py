#!/usr/bin/env python3
"""Đối chiếu realm Keycloak dev với ma trận phân quyền (WEB-056).

Kiểm tra `infra/dev/keycloak/realm-mission.json` so với `docs/phan-quyen/ma-tran.json`:
  - realm role đúng bằng tập vai trò của ma trận (bỏ AC-11 vì là client credentials): không thiếu, không thừa;
  - mô tả mỗi realm role bắt đầu bằng đúng mã AC của vai trò đó;
  - không có composite role (nguồn không định nghĩa vai trò gộp);
  - mỗi vai trò người dùng AC-01..AC-10 có ít nhất một tài khoản thử, tài khoản nào cũng có `tenant`;
  - mật khẩu và khoá OTP của tài khoản thử chỉ là biến `${...}`, không có giá trị thật trong tệp commit;
  - vai trò AAL2 của BR-00-01 đều có nhánh OTP bắt buộc trong luồng đăng nhập của realm;
  - mô tả của realm role, client và client scope không quá 255 ký tự (cột varchar(255) của Keycloak 26,
    dài hơn thì nhập realm hỏng "value too long");
  - biến `${...}` trong redirectUris, webOrigins có giá trị mặc định `${BIEN:mặc-định}`, vì thiếu biến
    thì URI không hợp lệ và Keycloak không khởi động được.

Dùng: python3 docs/phan-quyen/check_realm.py [đường-dẫn-realm.json]   (mã thoát 1 nếu lệch)
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DATA = HERE / "ma-tran.json"
REALM = ROOT / "infra" / "dev" / "keycloak" / "realm-mission.json"

# BR-00-01: vai trò chỉ dùng trong phiên AAL2
AAL2_ROLES = {"appraiser", "decider", "second_approver", "tenant_admin", "platform_admin"}
PLACEHOLDER = re.compile(r"^\$\{[A-Z0-9_]+\}$")
# Độ dài tối đa của cột description (keycloak_role, client, client_scope) trong Keycloak 26
DESCRIPTION_MAX = 255
URI_VAR_NO_DEFAULT = re.compile(r"\$\{[A-Za-z0-9_.]+\}")


def check(realm_path: Path) -> list[str]:
    m = json.loads(DATA.read_text(encoding="utf-8"))
    r = json.loads(realm_path.read_text(encoding="utf-8"))
    expected = {v["role"]: v["ac"] for v in m["vai_tro"] if re.fullmatch(r"[a-z_]+", v["role"])}
    # Vai trò con khai báo trong cột keycloak của vai trò, ví dụ "vai trò con `wf_template_author`" (BS-01)
    for v in m["vai_tro"]:
        for sub in re.findall(r"vai trò con `([a-z_]+)`", v.get("keycloak", "")):
            expected[sub] = v["ac"]
    errors = []

    roles = {x["name"]: x for x in r.get("roles", {}).get("realm", [])}
    for name in sorted(set(expected) - set(roles)):
        errors.append(f"Thiếu realm role `{name}` ({expected[name]})")
    # Vai trò `svc_*` chỉ gán cho tài khoản dịch vụ (client credentials, như AC-11) nên không có trong ma trận.
    service_users = [u for u in r.get("users", []) if u.get("serviceAccountClientId")]
    service_roles = {x for u in service_users for x in u.get("realmRoles", []) if x.startswith("svc_")}
    for name in sorted(set(roles) - set(expected) - service_roles):
        errors.append(f"Realm role lạ `{name}` không có trong ma-tran.json")
    for name, role in roles.items():
        if name in expected and not str(role.get("description", "")).startswith(expected[name] + " "):
            errors.append(f"Mô tả realm role `{name}` phải bắt đầu bằng mã {expected[name]}")
        if role.get("composite") or role.get("composites"):
            errors.append(f"Realm role `{name}` là composite; nguồn không định nghĩa vai trò gộp (ghi đề xuất vào wiki/14)")

    users = r.get("users", [])
    human = {k for k, ac in expected.items() if ac not in ("AC-12",)}
    covered = {x for u in users for x in u.get("realmRoles", [])}
    for name in sorted(human - covered):
        errors.append(f"Chưa có tài khoản thử cho vai trò `{name}` ({expected[name]})")
    for u in users:
        if not u.get("serviceAccountClientId") and not u.get("attributes", {}).get("tenant"):
            errors.append(f"Tài khoản `{u['username']}` thiếu thuộc tính tenant")
        for c in u.get("credentials", []):
            secret = c.get("value") if c.get("type") == "password" else json.loads(c.get("secretData", "{}")).get("value")
            if secret is not None and not PLACEHOLDER.match(str(secret)):
                errors.append(f"Tài khoản `{u['username']}`: {c.get('type')} phải lấy từ biến ${{...}}, không ghi giá trị thật")

    cfg = {c["alias"]: c.get("config", {}) for c in r.get("authenticatorConfig", [])}
    flows = {f["alias"]: f for f in r.get("authenticationFlows", [])}
    otp_roles = set()
    for f in flows.values():
        ex = f.get("authenticationExecutions", [])
        if any(e.get("authenticator") == "auth-otp-form" and e.get("requirement") == "REQUIRED" for e in ex):
            for e in ex:
                if e.get("authenticator") == "conditional-user-role":
                    c = cfg.get(e.get("authenticatorConfig"), {})
                    if c.get("negate", "false") != "true":
                        otp_roles.add(c.get("condUserRole"))
    for name in sorted(AAL2_ROLES - otp_roles):
        errors.append(f"Vai trò AAL2 `{name}` (BR-00-01) chưa có nhánh OTP bắt buộc trong luồng đăng nhập")
    described = [("realm role", x.get("name"), x) for x in r.get("roles", {}).get("realm", [])]
    described += [("client", x.get("clientId"), x) for x in r.get("clients", [])]
    described += [("client scope", x.get("name"), x) for x in r.get("clientScopes", [])]
    for kind, name, x in described:
        n = len(str(x.get("description", "")))
        if n > DESCRIPTION_MAX:
            errors.append(f"Mô tả {kind} `{name}` dài {n} ký tự, vượt {DESCRIPTION_MAX} của Keycloak")
    for c in r.get("clients", []):
        for uri in c.get("redirectUris", []) + c.get("webOrigins", []):
            if URI_VAR_NO_DEFAULT.search(uri):
                errors.append(f"Client `{c.get('clientId')}`: `{uri}` cần giá trị mặc định dạng ${{BIEN:mặc-định}}")
    if r.get("browserFlow") not in flows:
        errors.append(f"browserFlow `{r.get('browserFlow')}` không có trong authenticationFlows của realm")
    return errors


def main(argv):
    path = Path(argv[0]) if argv else REALM
    errors = check(path)
    for e in errors:
        print(e, file=sys.stderr)
    if errors:
        print(f"{path.name} lệch với ma-tran.json: {len(errors)} lỗi", file=sys.stderr)
        return 1
    print(f"{path.name} khớp ma-tran.json: vai trò, tài khoản thử, OTP theo vai trò AAL2, độ dài mô tả và URI.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

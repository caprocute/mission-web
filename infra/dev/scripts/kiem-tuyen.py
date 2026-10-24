#!/usr/bin/env python3
"""Chẩn đoán tuyến API trên server dev: mỗi thao tác GET không có tham số bắt buộc trong
`contracts/openapi/modules/*.yaml` được gọi hai lần, một lần qua APISIX và một lần thẳng tới service mà bảng
tuyến `infra/dev/apisix/apisix.yaml` chọn (cổng công bố trong `infra/dev/docker-compose.yml`), rồi phân loại:

  2xx          thành công
  401/403      bị chặn xác thực hoặc phân quyền (token, OPA)
  404-tuyen    tuyến không tồn tại (Spring "No static resource", APISIX "Route Not Found", Go "404 page not found")
  404-problem  service có tuyến nhưng trả Problem 404 (thiếu dữ liệu, ngoài phạm vi)
  404-chan     tuyến internal-only của APISIX chặn có chủ đích
  roi-web      đường /api/... rơi về tuyến /* của web (trả HTML), tức APISIX không có tuyến API nào khớp
  ket-noi      không kết nối được (service chưa chạy, sai cổng)
  khac         mã khác (4xx, 5xx)

Cuối cùng in bảng tóm tắt theo service và danh sách tuyến hỏng, kèm ảnh, nhãn commit, thời điểm dựng của từng
container `mission-dev-*` khi có docker CLI. Chỉ dùng thư viện chuẩn; PyYAML không bắt buộc: thiếu PyYAML (máy chủ dev
Python 3.6 chưa cài) thì đọc bản rút gọn `kiem-tuyen.du-lieu.json` (danh sách thao tác, bảng tuyến, cổng compose) sinh
sẵn trong repo bằng `--sinh-du-lieu` trên máy có PyYAML. Bản này mang băm SHA-256 của mọi tệp nguồn; nguồn đã đổi mà
chưa sinh lại thì script dừng và báo, không chạy với bảng tuyến cũ.

Đọc infra/dev/.env (không commit) như smoke-backend.sh: DEV_HOST, APISIX_PORT, cổng từng service. Token lấy từ
SMOKE_TOKEN, nếu không có thì chạy lay-token-thu.py (tài khoản thử, PKCE và OTP); --no-token để gọi không token.
Không in token, mật khẩu hay thân phản hồi chứa dữ liệu. Mã thoát 0 khi không có tuyến hỏng (404-tuyen, roi-web,
ket-noi qua APISIX), 1 khi có.
"""
import argparse
import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - máy chủ dev chưa có PyYAML, dùng bản rút gọn sinh sẵn
    yaml = None
import hashlib

SCRIPTS = Path(__file__).resolve().parent
DEV = SCRIPTS.parent
REPO = DEV.parent.parent
MODULES = REPO / "contracts" / "openapi" / "modules"
APISIX = DEV / "apisix" / "apisix.yaml"
COMPOSE = DEV / "docker-compose.yml"
BROKEN = {"404-tuyen", "roi-web", "ket-noi"}
DU_LIEU = SCRIPTS / "kiem-tuyen.du-lieu.json"


def load_env() -> None:
    env = DEV / ".env"
    if not env.is_file():
        return
    for line in env.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*([A-Z0-9_]+)=(.*)$", line)
        if m and m.group(1) not in os.environ:
            os.environ[m.group(1)] = m.group(2).strip().strip('"').strip("'")


def expand(value: str) -> str:
    """Thay `${X:-mặc định}` và `${X}` theo biến môi trường, như docker compose."""
    return re.sub(
        r"\$\{([A-Z0-9_]+)(?::?-([^}]*))?\}",
        lambda m: os.environ.get(m.group(1)) or (m.group(2) or ""),
        value,
    )


# ---------------------------------------------------------------- hợp đồng


class Refs:
    """Giải `$ref` của tham số, kể cả sang tệp khác (`../common/common.yaml#/...`)."""

    def __init__(self) -> None:
        self.docs = {}

    def doc(self, path: Path):
        key = path.resolve()
        if key not in self.docs:
            self.docs[key] = yaml.safe_load(key.read_text(encoding="utf-8"))
        return self.docs[key]

    def resolve(self, node, base: Path):
        while isinstance(node, dict) and "$ref" in node:
            target, _, pointer = node["$ref"].partition("#")
            base = (base.parent / target) if target else base
            node = self.doc(base)
            for part in filter(None, pointer.split("/")):
                node = node[part.replace("~1", "/").replace("~0", "~")]
        return node


def operations():
    refs = Refs()
    for spec in sorted(MODULES.glob("*.yaml")):
        doc = refs.doc(spec)
        prefix = (doc.get("servers") or [{"url": "/api/v1"}])[0]["url"].rstrip("/")
        for path, item in (doc.get("paths") or {}).items():
            op = item.get("get")
            if not op:
                continue
            params = [refs.resolve(p, spec) for p in (item.get("parameters") or []) + (op.get("parameters") or [])]
            if "{" in path or any(p.get("required") for p in params):
                continue
            owner = (op.get("x-mission") or {}).get("owner", "")
            yield {"module": spec.stem, "id": op.get("operationId", "?"), "owner": owner, "path": prefix + path}


# ---------------------------------------------------------------- bảng tuyến


def uri_match(pattern: str, uri: str) -> bool:
    return uri.startswith(pattern[:-1]) if pattern.endswith("*") else uri == pattern


def load_routes():
    doc = yaml.safe_load(APISIX.read_text(encoding="utf-8"))
    upstreams = {}
    for u in doc.get("upstreams") or []:
        node = next(iter(u["nodes"]))
        upstreams[u["id"]] = node.split(":")[0]
    routes = []
    for r in doc.get("routes") or []:
        uris = r.get("uris") or [r["uri"]]
        regex = [v[2] for v in r.get("vars") or [] if len(v) == 3 and v[0] == "uri" and v[1] == "~~"]
        routes.append(
            {
                "id": r["id"],
                "uris": uris,
                "methods": r.get("methods"),
                "regex": [re.compile(x) for x in regex],
                "priority": r.get("priority", 0),
                "service": upstreams.get(r.get("upstream_id", ""), ""),
            }
        )
    return routes


def route_for(routes, method: str, uri: str):
    hits = [
        r
        for r in routes
        if any(uri_match(p, uri) for p in r["uris"])
        and (not r["methods"] or method in r["methods"])
        and all(x.search(uri) for x in r["regex"])
    ]
    return max(hits, key=lambda r: r["priority"]) if hits else None


def compose_ports():
    """Khai báo `ports` chưa giải biến của từng service compose."""
    doc = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    return {name: [str(p) for p in (svc.get("ports") or [])] for name, svc in (doc.get("services") or {}).items()}


def published_ports(raw):
    """Cổng máy chủ của từng service compose (mapping tới cổng 8080 trong container)."""
    out = {}
    for name, ports in raw.items():
        for p in ports:
            parts = expand(p).split(":")
            if len(parts) >= 2 and parts[-1] == "8080":
                out[name] = parts[-2]
    return out


# ---------------------------------------------------------------- bản rút gọn cho máy không có PyYAML


def nguon():
    tep = [APISIX, COMPOSE] + sorted((REPO / "contracts" / "openapi").rglob("*.yaml"))
    return {str(t.relative_to(REPO)): hashlib.sha256(t.read_bytes()).hexdigest() for t in tep}


def sinh_du_lieu():
    routes = [dict(r, regex=[x.pattern for x in r["regex"]]) for r in load_routes()]
    return {"nguon": nguon(), "operations": list(operations()), "routes": routes, "ports": compose_ports()}


def nap_du_lieu():
    """(operations, routes, cổng compose chưa giải biến) từ YAML nếu có PyYAML, nếu không từ bản rút gọn."""
    if yaml is not None:
        return list(operations()), load_routes(), compose_ports()
    if not DU_LIEU.is_file():
        sys.exit(f"Thiếu PyYAML và thiếu {DU_LIEU.name}; chạy kiem-tuyen.py --sinh-du-lieu trên máy có PyYAML")
    d = json.loads(DU_LIEU.read_text(encoding="utf-8"))
    lech = sorted(k for k, v in nguon().items() if d["nguon"].get(k) != v) + sorted(set(d["nguon"]) - set(nguon()))
    if lech:
        sys.exit(f"{DU_LIEU.name} cũ hơn nguồn ({', '.join(lech[:5])}); chạy kiem-tuyen.py --sinh-du-lieu trên máy có PyYAML")
    routes = [dict(r, regex=[re.compile(x) for x in r["regex"]]) for r in d["routes"]]
    return d["operations"], routes, d["ports"]


# ---------------------------------------------------------------- gọi và phân loại


def call(url: str, token: str, timeout: float):
    req = urllib.request.Request(url, headers={"Accept": "application/json, application/problem+json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.headers.get("Content-Type", ""), res.read(2048)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read(2048)
    except (urllib.error.URLError, OSError) as e:
        return None, "", str(getattr(e, "reason", e)).encode()


def classify(status, ctype: str, body: bytes, route_id: str = "") -> str:
    text = body.decode("utf-8", "replace")
    if status is None:
        return "ket-noi"
    if "text/html" in ctype:
        return "roi-web"
    if 200 <= status < 300:
        return "2xx"
    if status in (401, 403):
        return "401/403"
    if status == 404:
        if route_id == "internal-only":
            return "404-chan"
        if re.search(r"No static resource|No endpoint|No handler found|Route Not Found|404 page not found", text):
            return "404-tuyen"
        if "problem+json" in ctype or '"type"' in text:
            return "404-problem"
        return "404-tuyen"
    return "khac"


def docker_images():
    if not shutil.which("docker"):
        return []
    fmt = (
        '{{.Name}}|{{.Config.Image}}|{{index .Config.Labels "org.opencontainers.image.revision"}}'
        "|{{.Created}}|{{.State.Status}}"
    )
    try:
        names = subprocess.run(
            ["docker", "ps", "-a", "--filter", "name=mission-dev-", "--format", "{{.Names}}"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=20, check=True,
        ).stdout.split()
        if not names:
            return []
        out = subprocess.run(
            ["docker", "inspect", "--format", fmt, *names], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=20, check=True
        ).stdout
    except (subprocess.SubprocessError, OSError):
        return []
    return [line.lstrip("/").split("|") for line in out.splitlines() if line]


def get_token(no_token: bool) -> str:
    if no_token:
        return ""
    if os.environ.get("SMOKE_TOKEN"):
        return os.environ["SMOKE_TOKEN"]
    host = os.environ.get("DEV_HOST", "127.0.0.1")
    os.environ.setdefault("KC_URL", os.environ.get("KEYCLOAK_PUBLIC_URL") or f"http://{host}:{os.environ.get('KEYCLOAK_PORT', '35080')}")
    try:
        res = subprocess.run(
            [sys.executable, str(SCRIPTS / "lay-token-thu.py")], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=60
        )
    except (subprocess.SubprocessError, OSError):
        return ""
    if res.returncode != 0:
        print("Không lấy được token thử, gọi không token (đặt SMOKE_TOKEN để dùng token sẵn).", file=sys.stderr)
        return ""
    return res.stdout.strip()


def table(rows, headers):
    widths = [max(len(str(x)) for x in col) for col in zip(headers, *rows)]
    line = lambda r: "  ".join(str(x).ljust(w) for x, w in zip(r, widths))
    print(line(headers))
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print(line(r))


def main() -> int:
    load_env()
    host = os.environ.get("DEV_HOST", "127.0.0.1")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--gateway", default=f"http://{host}:{os.environ.get('APISIX_PORT', '35000')}")
    ap.add_argument("--host", default=host, help="máy để gọi thẳng cổng công bố của service")
    ap.add_argument("--module", action="append", help="chỉ kiểm mô-đun hợp đồng này (lặp được), vd okr")
    ap.add_argument("--no-token", action="store_true", help="gọi không token")
    ap.add_argument("--no-direct", action="store_true", help="bỏ lời gọi thẳng service")
    ap.add_argument("--all", action="store_true", help="in mọi thao tác, không chỉ dòng có vấn đề")
    ap.add_argument("--json", action="store_true", help="in kết quả dạng JSON")
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--sinh-du-lieu", action="store_true", help=f"ghi {DU_LIEU.name} cho máy không có PyYAML rồi thoát")
    ap.add_argument("--kiem-du-lieu", action="store_true", help=f"mã thoát 1 khi {DU_LIEU.name} lệch nguồn")
    args = ap.parse_args()

    if args.sinh_du_lieu or args.kiem_du_lieu:
        if yaml is None:
            sys.exit("Cần PyYAML để sinh hoặc kiểm bản rút gọn")
        moi = json.dumps(sinh_du_lieu(), ensure_ascii=False, indent=1, sort_keys=True) + "\n"
        if args.kiem_du_lieu:
            dung = DU_LIEU.is_file() and DU_LIEU.read_text(encoding="utf-8") == moi
            print(f"{DU_LIEU.name}: {'khớp nguồn' if dung else 'lệch nguồn, chạy --sinh-du-lieu'}")
            return 0 if dung else 1
        DU_LIEU.write_text(moi, encoding="utf-8")
        print(f"Đã ghi {DU_LIEU.relative_to(REPO)}")
        return 0

    all_ops, routes, raw_ports = nap_du_lieu()
    ports = published_ports(raw_ports)
    token = get_token(args.no_token)
    ops = [dict(o) for o in all_ops if not args.module or o["module"] in args.module]

    def check(op):
        route = route_for(routes, "GET", op["path"])
        op["route"] = route["id"] if route else "(không có)"
        op["service"] = route["service"] if route else ""
        op["gateway"] = classify(*call(args.gateway + op["path"], token, args.timeout), op["route"])
        port = ports.get(op["service"])
        if args.no_direct or not port or op["service"] == "web":
            op["direct"] = "-"
        else:
            op["direct"] = classify(*call(f"http://{args.host}:{port}{op['path']}", token, args.timeout))
        return op

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(check, ops))
    images = docker_images()

    if args.json:
        print(json.dumps({"operations": results, "containers": images}, ensure_ascii=False, indent=2))
    else:
        print(f"Cổng: {args.gateway}  Token: {'có' if token else 'không'}  Thao tác GET kiểm: {len(results)}\n")
        if images:
            print("Container:")
            table([[n, img, rev or "-", created[:19], st] for n, img, rev, created, st in images],
                  ["container", "ảnh", "commit", "dựng lúc", "trạng thái"])
            print()
        summary = {}
        for r in results:
            s = summary.setdefault(r["service"] or "(không tuyến)", {})
            s[r["gateway"]] = s.get(r["gateway"], 0) + 1
        kinds = ["2xx", "401/403", "404-tuyen", "404-problem", "404-chan", "roi-web", "ket-noi", "khac"]
        print("Tóm tắt qua APISIX theo service:")
        table([[svc] + [c.get(k, 0) for k in kinds] for svc, c in sorted(summary.items())], ["service"] + kinds)
        print()
        shown = [r for r in results if args.all or r["gateway"] not in ("2xx", "404-chan") or r["direct"] in BROKEN]
        if shown:
            print("Chi tiết:" if args.all else "Thao tác có vấn đề:")
            table([[r["module"], r["id"], r["path"], r["service"], r["gateway"], r["direct"]] for r in shown],
                  ["mô-đun", "thao tác", "đường dẫn", "service", "qua APISIX", "gọi thẳng"])
    return 1 if any(r["gateway"] in BROKEN for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())

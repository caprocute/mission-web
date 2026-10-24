"""Bộ áp migration nhẹ cho kho phân tích ClickHouse của một cell (DV-15-01, SVC-15/ADR-14).

Mỗi tệp `migrations/V<số>__<tên>.sql` được áp đúng một lần theo thứ tự số; bảng `<db>._schema_migrations` ghi số
phiên bản, tên và tổng kiểm SHA-256 của tệp sau khi thế tham số. Tệp đã áp mà nội dung đổi thì dừng với lỗi (sửa
lược đồ bằng tệp mới, không sửa tệp cũ). Tham số dạng `{{ten}}` lấy từ biến môi trường; mật khẩu chỉ đi vào câu lệnh
gửi ClickHouse, không in ra nhật ký và không tham gia tổng kiểm.

Chỉ dùng thư viện chuẩn để chạy được trong Job Kubernetes tối giản (suy luận) và trong kiểm thử.

    python3 migrate.py                 # áp các tệp chưa áp
    python3 migrate.py --dry-run       # in danh sách tệp sẽ áp
    python3 migrate.py --lam-moi [--tat-ca] [--lap]   # chỉ ở cell đặt ANALYTICS_REFRESHABLE_MV=false (xem dưới)

Refreshable materialized view cần nhân Linux 3.15 trở lên. Cell chạy trên nhân cũ hơn (máy chủ dev CentOS 7, nhân
3.10) đặt `ANALYTICS_REFRESHABLE_MV=false`: mỗi câu `CREATE MATERIALIZED VIEW … REFRESH EVERY … [APPEND] TO <đích>`
được dựng thành view thường cùng tên, kèm một dòng trong `<db>._lam_moi_dinh_ky`; `--lam-moi` tính lại các view đến
hạn bằng INSERT … SELECT vào bảng đích (ghi đè thì TRUNCATE trước, APPEND thì chỉ ghi thêm). Tổng kiểm vẫn tính trên
tệp gốc nên đổi chế độ không làm lịch sử migration bị coi là đã sửa. Production giữ mặc định `true`.
"""

import argparse
import base64
import hashlib
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, List, Mapping, NamedTuple, Optional, Tuple

MIGRATIONS = pathlib.Path(__file__).resolve().parent / "migrations"
FILE_RE = re.compile(r"^V(\d{3})__([a-z0-9_]+)\.sql$")
PARAM_RE = re.compile(r"\{\{([a-z_]+)\}\}")
SECRET_PARAMS = frozenset({"cube_password", "anomaly_password", "dict_password"})
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,40}$")
REFRESH_MV_RE = re.compile(
    r"^CREATE MATERIALIZED VIEW (?P<db>[a-z0-9_]+)\.(?P<view>[a-z0-9_]+) (?P<cluster>ON CLUSTER '[^']+')\s+"
    r"REFRESH EVERY (?P<n>\d+) (?P<unit>SECOND|MINUTE|HOUR|DAY)(?P<append> APPEND)?\s+"
    r"TO [a-z0-9_]+\.(?P<target>[a-z0-9_]+)\s+AS\s+(?P<select>.+)$", re.S)
UNIT_SECONDS = {"SECOND": 1, "MINUTE": 60, "HOUR": 3600, "DAY": 86400}


# NamedTuple thay dataclass để chạy được trên Python 3.6 của máy chủ dev (CentOS 7).
class Migration(NamedTuple):
    version: int
    name: str
    path: pathlib.Path


def discover(folder: pathlib.Path = MIGRATIONS) -> List[Migration]:
    out = []
    for p in sorted(folder.glob("*.sql")):
        m = FILE_RE.match(p.name)
        if not m:
            raise SystemExit(f"tên tệp migration không hợp lệ: {p.name}")
        out.append(Migration(int(m.group(1)), m.group(2), p))
    versions = [m.version for m in out]
    if len(set(versions)) != len(versions):
        raise SystemExit("trùng số phiên bản migration")
    return sorted(out, key=lambda m: m.version)


def params_from_env(env: Mapping[str, str]) -> Dict[str, str]:
    """Tham số của một cell. Tên CSDL, cụm, nhóm tiêu thụ phải là định danh an toàn vì được ghép vào DDL."""
    cell = env.get("ANALYTICS_CELL", "dev")
    p = {
        "db": env.get("ANALYTICS_DB", f"analytics_{cell}"),
        "cluster": env.get("ANALYTICS_CLUSTER", f"cell_{cell}"),
        "kafka_brokers": env.get("ANALYTICS_KAFKA_BROKERS", "kafka:9092"),
        "kafka_group": env.get("ANALYTICS_KAFKA_GROUP", f"clickhouse-analytics-{cell}"),
        "kafka_consumers": env.get("ANALYTICS_KAFKA_CONSUMERS", "1"),
        "kafka_flush_ms": env.get("ANALYTICS_KAFKA_FLUSH_MS", "5000"),
        "topic_core": "cdc." + env.get("ANALYTICS_CORE_DB", "tenant_data"),
        "topic_org": "cdc." + env.get("ANALYTICS_ORG_DB", "org"),
        "topic_workflow": "cdc." + env.get("ANALYTICS_WORKFLOW_DB", "flowable"),
        "cube_password": env.get("ANALYTICS_CUBE_PASSWORD", ""),
        "anomaly_password": env.get("ANALYTICS_ANOMALY_PASSWORD", ""),
        "dict_password": env.get("ANALYTICS_DICT_PASSWORD", ""),
    }
    p["refreshable_mv"] = env.get("ANALYTICS_REFRESHABLE_MV", "true").strip().lower()
    if p["refreshable_mv"] not in ("true", "false"):
        raise SystemExit("ANALYTICS_REFRESHABLE_MV phải là true hoặc false")
    for k in ("db", "cluster"):
        if not NAME_RE.match(p[k]):
            raise SystemExit(f"{k} không hợp lệ: {p[k]!r}")
    if not re.fullmatch(r"[A-Za-z0-9_.\-]+", p["kafka_group"]):
        raise SystemExit("ANALYTICS_KAFKA_GROUP không hợp lệ")
    if not re.fullmatch(r"[A-Za-z0-9_.\-:,]+", p["kafka_brokers"]):
        raise SystemExit("ANALYTICS_KAFKA_BROKERS không hợp lệ")
    for k in ("kafka_consumers", "kafka_flush_ms"):
        if not p[k].isdigit():
            raise SystemExit(f"{k} phải là số nguyên dương")
    for k in SECRET_PARAMS:
        if len(p[k]) < 16 or "'" in p[k] or "\\" in p[k]:
            raise SystemExit(f"thiếu hoặc sai mật khẩu {k}: cần ít nhất 16 ký tự, không có ' hoặc \\")
    return p


def render(sql: str, params: Mapping[str, str]) -> Tuple[str, str]:
    """Trả về (câu lệnh đã thế đủ tham số, tổng kiểm). Tổng kiểm tính trên bản thế tham số không bí mật để đổi mật
    khẩu không làm tệp bị coi là đã sửa."""
    missing = sorted({k for k in PARAM_RE.findall(sql) if k not in params})
    if missing:
        raise SystemExit(f"thiếu tham số: {', '.join(missing)}")
    full = PARAM_RE.sub(lambda m: params[m.group(1)], sql)
    masked = PARAM_RE.sub(lambda m: "***" if m.group(1) in SECRET_PARAMS else params[m.group(1)], sql)
    return full, hashlib.sha256(masked.encode()).hexdigest()


def statements(sql: str) -> List[str]:
    """Tách câu lệnh theo dấu ';' ở cuối dòng, bỏ dòng chú thích. Câu lệnh trong migrations không chứa ';' giữa dòng."""
    out, cur = [], []
    for line in sql.splitlines():
        if line.lstrip().startswith("--") and not cur:
            continue
        cur.append(line)
        if line.rstrip().endswith(";"):
            stmt = "\n".join(cur).strip().rstrip(";").strip()
            if stmt:
                out.append(stmt)
            cur = []
    if "\n".join(cur).strip():
        raise SystemExit("câu lệnh cuối không kết thúc bằng ';'")
    return out


def without_refresh(stmt: str) -> Tuple[str, Optional[Tuple[str, str, int, int]]]:
    """Đổi refreshable MV thành view thường. Trả về (câu lệnh, (view, bảng đích, chu kỳ giây, 1 nếu APPEND)) hoặc
    (câu lệnh giữ nguyên, None) khi không phải refreshable MV."""
    lines = stmt.splitlines()
    while lines and (not lines[0].strip() or lines[0].lstrip().startswith("--")):
        lines.pop(0)
    m = REFRESH_MV_RE.match("\n".join(lines))
    if not m:
        return stmt, None
    view = f"CREATE VIEW {m.group('db')}.{m.group('view')} {m.group('cluster')}\nAS\n{m.group('select')}"
    every = int(m.group("n")) * UNIT_SECONDS[m.group("unit")]
    return view, (m.group("view"), m.group("target"), every, 1 if m.group("append") else 0)


class ClickHouse:
    def __init__(self, url: str, user: str, password: str, timeout: float = 120, **settings: str) -> None:
        if not url.startswith(("http://", "https://")):
            raise SystemExit("CLICKHOUSE_URL phải là http:// hoặc https://")
        self.url = url.rstrip("/") + "/"
        self.settings = settings
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {"Authorization": "Basic " + token}
        self.timeout = timeout

    def run(self, sql: str, **settings: str) -> str:
        query = urllib.parse.urlencode({**self.settings, **settings})
        req = urllib.request.Request(  # noqa: S310 - chỉ http(s), kiểm ở __init__
            self.url + "?" + query, data=sql.encode(), headers=self.headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:  # noqa: S310 - URL do người vận hành cấu hình
                return r.read().decode()
        except urllib.error.HTTPError as e:
            raise RuntimeError(e.read().decode(errors="replace")[:2000]) from None


def migrate(ch: ClickHouse, params: Mapping[str, str], folder: pathlib.Path = MIGRATIONS,
            dry_run: bool = False) -> List[str]:
    db, cluster = params["db"], params["cluster"]
    ch.run(f"CREATE DATABASE IF NOT EXISTS {db} ON CLUSTER '{cluster}'")
    ch.run(f"""CREATE TABLE IF NOT EXISTS {db}._schema_migrations ON CLUSTER '{cluster}'
               (version UInt32, name String, checksum String, applied_at DateTime64(3) DEFAULT now64(3))
               ENGINE = ReplicatedReplacingMergeTree(applied_at) ORDER BY version""")
    plain = params.get("refreshable_mv", "true") == "false"
    if plain:
        ch.run(f"""CREATE TABLE IF NOT EXISTS {db}._lam_moi_dinh_ky ON CLUSTER '{cluster}'
                   (view String, target String, every_s UInt32, append UInt8,
                    refreshed_at DateTime64(3) DEFAULT toDateTime64(0, 3))
                   ENGINE = ReplicatedReplacingMergeTree(refreshed_at) ORDER BY view""")
    done: Dict[int, str] = {}
    for line in ch.run(f"SELECT version, checksum FROM {db}._schema_migrations FINAL FORMAT TSV").splitlines():
        v, c = line.split("\t")
        done[int(v)] = c
    applied = []
    for m in discover(folder):
        sql, checksum = render(m.path.read_text(encoding="utf-8"), params)
        if m.version in done:
            if done[m.version] != checksum:
                raise SystemExit(f"V{m.version:03d}__{m.name} đã áp nhưng nội dung đã đổi; thêm tệp mới thay vì sửa")
            continue
        applied.append(m.path.name)
        if dry_run:
            continue
        for stmt in statements(sql):
            job = None
            if plain:
                stmt, job = without_refresh(stmt)
            ch.run(stmt)
            if job:
                ch.run(f"INSERT INTO {db}._lam_moi_dinh_ky (view, target, every_s, append) VALUES "
                       f"('{job[0]}', '{job[1]}', {job[2]}, {job[3]})")
        ch.run(f"INSERT INTO {db}._schema_migrations (version, name, checksum) VALUES "
               f"({m.version}, '{m.name}', '{checksum}')")
        print(f"đã áp {m.path.name}", file=sys.stderr)
    return applied


def refresh(ch: ClickHouse, db: str, everything: bool = False) -> List[str]:
    """Tính lại các view đến hạn trong `<db>._lam_moi_dinh_ky` (thay refreshable MV). Ghi đè: TRUNCATE rồi INSERT,
    nên bảng đích trống trong chốc lát giữa hai câu; chấp nhận được ở dev, nơi duy nhất dùng chế độ này."""
    exists = ch.run(f"SELECT count() FROM system.tables WHERE database = '{db}' AND name = '_lam_moi_dinh_ky'")
    if exists.strip() != "1":
        return []
    due = "" if everything else " WHERE refreshed_at + toIntervalSecond(every_s) <= now64(3)"
    rows = ch.run(f"SELECT view, target, every_s, append FROM {db}._lam_moi_dinh_ky FINAL{due} "
                  f"ORDER BY append, view FORMAT TSV")
    done = []
    for line in rows.splitlines():
        view, target, every_s, append = line.split("\t")
        if not (NAME_RE.match(view) and NAME_RE.match(target)):
            raise SystemExit(f"tên không hợp lệ trong {db}._lam_moi_dinh_ky: {view!r}, {target!r}")
        cols = ", ".join(f"`{c}`" for c in ch.run(
            f"SELECT name FROM system.columns WHERE database = '{db}' AND table = '{view}' "
            f"ORDER BY position FORMAT TSV").split())
        if append == "0":
            ch.run(f"TRUNCATE TABLE {db}.{target}")
        ch.run(f"INSERT INTO {db}.{target} ({cols}) SELECT {cols} FROM {db}.{view}")
        ch.run(f"INSERT INTO {db}._lam_moi_dinh_ky (view, target, every_s, append, refreshed_at) VALUES "
               f"('{view}', '{target}', {every_s}, {append}, now64(3))")
        done.append(view)
    return done


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--lam-moi", action="store_true", help="tính lại các view đến hạn (ANALYTICS_REFRESHABLE_MV=false)")
    ap.add_argument("--tat-ca", action="store_true", help="cùng --lam-moi: tính lại mọi view, không xét hạn")
    ap.add_argument("--lap", action="store_true", help="cùng --lam-moi: chạy mãi, kiểm hạn mỗi 15 giây")
    args = ap.parse_args()
    env = os.environ
    ch = ClickHouse(env.get("CLICKHOUSE_URL", "http://localhost:8123"), env.get("CLICKHOUSE_USER", "default"),
                    env.get("CLICKHOUSE_PASSWORD", ""), distributed_ddl_task_timeout="300")
    if args.lam_moi:
        db = env.get("ANALYTICS_DB", "analytics_" + env.get("ANALYTICS_CELL", "dev"))
        if not NAME_RE.match(db):
            raise SystemExit(f"db không hợp lệ: {db!r}")
        while True:
            try:
                for view in refresh(ch, db, everything=args.tat_ca):
                    print(f"đã làm mới {db}.{view}", file=sys.stderr, flush=True)
            except (RuntimeError, OSError) as e:
                if not args.lap:
                    raise SystemExit(f"làm mới lỗi: {e}") from None
                print(f"làm mới lỗi, thử lại sau: {str(e)[:300]}", file=sys.stderr, flush=True)
            if not args.lap:
                return
            time.sleep(15)
    for name in migrate(ch, params_from_env(env), dry_run=args.dry_run):
        print(name)


if __name__ == "__main__":
    main()

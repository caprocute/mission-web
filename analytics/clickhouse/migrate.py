"""Bộ áp migration nhẹ cho kho phân tích ClickHouse của một cell (DV-15-01, SVC-15/ADR-14).

Mỗi tệp `migrations/V<số>__<tên>.sql` được áp đúng một lần theo thứ tự số; bảng `<db>._schema_migrations` ghi số
phiên bản, tên và tổng kiểm SHA-256 của tệp sau khi thế tham số. Tệp đã áp mà nội dung đổi thì dừng với lỗi (sửa
lược đồ bằng tệp mới, không sửa tệp cũ). Tham số dạng `{{ten}}` lấy từ biến môi trường; mật khẩu chỉ đi vào câu lệnh
gửi ClickHouse, không in ra nhật ký và không tham gia tổng kiểm.

Chỉ dùng thư viện chuẩn để chạy được trong Job Kubernetes tối giản (suy luận) và trong kiểm thử.

    python3 migrate.py                 # áp các tệp chưa áp
    python3 migrate.py --dry-run       # in danh sách tệp sẽ áp
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass

MIGRATIONS = pathlib.Path(__file__).resolve().parent / "migrations"
FILE_RE = re.compile(r"^V(\d{3})__([a-z0-9_]+)\.sql$")
PARAM_RE = re.compile(r"\{\{([a-z_]+)\}\}")
SECRET_PARAMS = frozenset({"cube_password", "anomaly_password", "dict_password"})
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,40}$")


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    path: pathlib.Path


def discover(folder: pathlib.Path = MIGRATIONS) -> list[Migration]:
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


def params_from_env(env: Mapping[str, str]) -> dict[str, str]:
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
        "cube_password": env.get("ANALYTICS_CUBE_PASSWORD", ""),
        "anomaly_password": env.get("ANALYTICS_ANOMALY_PASSWORD", ""),
        "dict_password": env.get("ANALYTICS_DICT_PASSWORD", ""),
    }
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


def render(sql: str, params: Mapping[str, str]) -> tuple[str, str]:
    """Trả về (câu lệnh đã thế đủ tham số, tổng kiểm). Tổng kiểm tính trên bản thế tham số không bí mật để đổi mật
    khẩu không làm tệp bị coi là đã sửa."""
    missing = sorted({k for k in PARAM_RE.findall(sql) if k not in params})
    if missing:
        raise SystemExit(f"thiếu tham số: {', '.join(missing)}")
    full = PARAM_RE.sub(lambda m: params[m.group(1)], sql)
    masked = PARAM_RE.sub(lambda m: "***" if m.group(1) in SECRET_PARAMS else params[m.group(1)], sql)
    return full, hashlib.sha256(masked.encode()).hexdigest()


def statements(sql: str) -> list[str]:
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
            dry_run: bool = False) -> list[str]:
    db, cluster = params["db"], params["cluster"]
    ch.run(f"CREATE DATABASE IF NOT EXISTS {db} ON CLUSTER '{cluster}'")
    ch.run(f"""CREATE TABLE IF NOT EXISTS {db}._schema_migrations ON CLUSTER '{cluster}'
               (version UInt32, name String, checksum String, applied_at DateTime64(3) DEFAULT now64(3))
               ENGINE = ReplicatedReplacingMergeTree(applied_at) ORDER BY version""")
    done: dict[int, str] = {}
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
            ch.run(stmt)
        ch.run(f"INSERT INTO {db}._schema_migrations (version, name, checksum) VALUES "
               f"({m.version}, '{m.name}', '{checksum}')")
        print(f"đã áp {m.path.name}", file=sys.stderr)
    return applied


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    env = os.environ
    ch = ClickHouse(env.get("CLICKHOUSE_URL", "http://localhost:8123"), env.get("CLICKHOUSE_USER", "default"),
                    env.get("CLICKHOUSE_PASSWORD", ""), distributed_ddl_task_timeout="300")
    for name in migrate(ch, params_from_env(env), dry_run=args.dry_run):
        print(name)


if __name__ == "__main__":
    main()

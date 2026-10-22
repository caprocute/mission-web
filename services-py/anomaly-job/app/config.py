"""Cấu hình job từ biến môi trường. Bí mật (mật khẩu ClickHouse, khoá client dịch vụ) lấy từ OpenBao qua Secret của
CronJob; không có giá trị mặc định trong mã."""

from __future__ import annotations

import os
import pathlib
from collections.abc import Mapping
from dataclasses import dataclass

REPO_SCHEMA = (pathlib.Path(__file__).resolve().parents[3] / "contracts" / "events" / "avro" / "analytics"
               / "DauHieuBatThuong.avsc")


class ConfigError(SystemExit):
    pass


@dataclass(frozen=True)
class Settings:
    cell: str
    clickhouse_url: str
    clickhouse_user: str
    clickhouse_password: str
    db: str
    kafka_bootstrap: str
    registry_url: str
    config_api_url: str
    token_url: str
    client_id: str
    client_secret: str
    schema_path: pathlib.Path
    kafka_extra: Mapping[str, str]

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env

        def need(name: str) -> str:
            v = e.get(name, "").strip()
            if not v:
                raise ConfigError(f"thiếu biến môi trường {name}")
            return v

        cell = need("ANALYTICS_CELL")
        url = need("CLICKHOUSE_URL")
        if not url.startswith(("http://", "https://")):
            raise ConfigError("CLICKHOUSE_URL phải là http:// hoặc https://")
        extra = {k[len("KAFKA_"):].lower().replace("_", "."): v for k, v in e.items()
                 if k.startswith("KAFKA_") and k not in ("KAFKA_BOOTSTRAP_SERVERS",)}
        return Settings(
            cell=cell,
            clickhouse_url=url.rstrip("/"),
            clickhouse_user=e.get("CLICKHOUSE_USER", "").strip() or f"analytics_{cell}_anomaly",
            clickhouse_password=need("CLICKHOUSE_PASSWORD"),
            db=e.get("ANALYTICS_DB", "").strip() or f"analytics_{cell}",
            kafka_bootstrap=need("KAFKA_BOOTSTRAP_SERVERS"),
            registry_url=need("REGISTRY_URL").rstrip("/"),
            config_api_url=need("CONFIG_API_URL").rstrip("/"),
            token_url=need("OIDC_TOKEN_URL"),
            client_id=need("OIDC_CLIENT_ID"),
            client_secret=need("OIDC_CLIENT_SECRET"),
            schema_path=pathlib.Path(e.get("EVT20_SCHEMA_PATH", "").strip() or REPO_SCHEMA),
            kafka_extra=extra,
        )

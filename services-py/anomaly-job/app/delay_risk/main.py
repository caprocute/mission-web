"""Điểm vào dịch vụ dự báo trễ hạn (Deployment chạy liên tục theo cell, `deploy/delay-risk.yaml`)."""

from __future__ import annotations

import logging
import os
import pathlib
import signal
import sys
from types import FrameType

import httpx
from confluent_kafka import Consumer

from app.clickhouse import ClickHouse
from app.config import ConfigError
from app.delay_risk.calendar import CalendarClient
from app.delay_risk.model import Params
from app.delay_risk.service import TOPIC, DelayRiskService, Evt05Decoder
from app.delay_risk.sink import DelayRiskSink
from app.thresholds import ServiceToken

REPO_SCHEMA = (pathlib.Path(__file__).resolve().parents[4] / "contracts" / "events" / "avro" / "task"
               / "TienDoDaCapNhat.avsc")


def _need(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        raise ConfigError(f"thiếu biến môi trường {name}")
    return v


def params_from_env() -> Params:
    d = Params()
    return Params(window_days=int(os.environ.get("DELAY_RISK_WINDOW_DAYS", d.window_days)),
                  min_days=int(os.environ.get("DELAY_RISK_MIN_DAYS", d.min_days)),
                  vua_ratio=float(os.environ.get("DELAY_RISK_VUA_RATIO", d.vua_ratio)),
                  cao_ratio=float(os.environ.get("DELAY_RISK_CAO_RATIO", d.cao_ratio)))


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cell = _need("ANALYTICS_CELL")
    ch = ClickHouse(_need("CLICKHOUSE_URL").rstrip("/"),
                    os.environ.get("CLICKHOUSE_USER", "").strip() or f"analytics_{cell}_anomaly",
                    _need("CLICKHOUSE_PASSWORD"), os.environ.get("ANALYTICS_DB", "").strip() or f"analytics_{cell}",
                    timeout=60)
    extra = {k[len("KAFKA_"):].lower().replace("_", "."): v for k, v in os.environ.items()
             if k.startswith("KAFKA_") and k != "KAFKA_BOOTSTRAP_SERVERS"}
    consumer = Consumer({"bootstrap.servers": _need("KAFKA_BOOTSTRAP_SERVERS"), "group.id": f"svc15-delay-risk-{cell}",
                         "enable.auto.commit": False, "auto.offset.reset": "earliest", **extra})
    consumer.subscribe([TOPIC])
    core = _need("CORE_API_URL").rstrip("/")
    stop = False

    def on_signal(_sig: int, _frame: FrameType | None) -> None:
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    with httpx.Client(timeout=30) as http:
        token = ServiceToken(http, _need("OIDC_TOKEN_URL"), _need("OIDC_CLIENT_ID"), _need("OIDC_CLIENT_SECRET"))
        svc = DelayRiskService(consumer, ch, CalendarClient(http, core, token), DelayRiskSink(http, core, token),
                               Evt05Decoder(pathlib.Path(os.environ.get("EVT05_SCHEMA_PATH", "") or REPO_SCHEMA)),
                               params_from_env(), max_wait=float(os.environ.get("DELAY_RISK_MAX_WAIT_S", "240")))
        while not stop:
            svc.step()
    consumer.close()
    ch.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Điểm vào của CronJob 02:00 theo cell: phát hiện, rồi phát EVT-20 cho mọi dòng chưa phát (kể cả dòng còn sót từ
lượt trước). Mã thoát khác 0 khi có tenant lỗi hoặc phát không hết, để vận hành nhận cảnh báo kỹ thuật (TL-04)."""

from __future__ import annotations

import argparse
import logging
import sys

import httpx
from confluent_kafka import Producer

from app.clickhouse import ClickHouse
from app.config import Settings
from app.delay_risk.calendar import CalendarClient
from app.detector import detect
from app.producer import Evt20Codec, Publisher, PublishError, registry_global_id
from app.thresholds import ServiceToken, ThresholdClient


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="anomaly-job", description="Job phát hiện bất thường (QT-14)")
    ap.add_argument("--tenant", action="append", help="chỉ chạy cho tenant này (lặp lại được)")
    ap.add_argument("--chi-phat", action="store_true", help="chỉ phát các dòng chưa phát, không chạy luật")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    s = Settings.from_env()
    ch = ClickHouse(s.clickhouse_url, s.clickhouse_user, s.clickhouse_password, s.db)
    ok = True
    with httpx.Client(timeout=30) as http:
        if not args.chi_phat:
            token = ServiceToken(http, s.token_url, s.client_id, s.client_secret)
            result = detect(ch, ThresholdClient(http, s.config_api_url, token), args.tenant,
                            CalendarClient(http, s.config_api_url, token))
            ok = not result.failed_tenants
        codec = Evt20Codec(s.schema_path)
        producer = Producer({"bootstrap.servers": s.kafka_bootstrap, "enable.idempotence": True, "acks": "all",
                             "client.id": f"anomaly-job-{s.cell}", **s.kafka_extra})
        try:
            n = Publisher(ch, producer, codec, registry_global_id(http, s.registry_url, codec.schema_text),
                          publish_resolved=s.publish_resolved).publish_pending()
            logging.getLogger("anomaly-job").info("đã phát %d EVT-20", n)
        except PublishError as e:
            logging.getLogger("anomaly-job").error("%s", e)
            ok = False
    ch.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

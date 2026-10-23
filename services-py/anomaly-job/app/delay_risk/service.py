"""Vòng tiêu thụ EVT-05 `task.task.progressed.v1` (khoá `tenant:taskId`): gom sự kiện theo nhiệm vụ, chờ bản ghi tiến
độ của sự kiện tới kho qua CDC, tính lại dự báo từ trạng thái hiện tại của kho rồi ghi qua cổng MOD-02.

Vì luôn tính từ trạng thái mới nhất của kho, sự kiện phát lại hay đến lệch thứ tự không làm lùi kết quả. Khi CDC chậm
quá `max_wait` giây thì bỏ sự kiện và giữ giá trị cũ (không xoá). Offset chỉ được ghi tới sự kiện sớm nhất còn chờ của
mỗi phân vùng, nên dừng giữa chừng thì sự kiện chưa xử lý được nhận lại (at-least-once)."""

from __future__ import annotations

import datetime as dt
import io
import json
import logging
import pathlib
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import fastavro
from confluent_kafka import TopicPartition

from app.clickhouse import ClickHouse
from app.delay_risk import source
from app.delay_risk.calendar import CalendarClient, CalendarError
from app.delay_risk.model import Params, forecast
from app.delay_risk.sink import DelayRiskSink, SinkError

log = logging.getLogger(__name__)

TOPIC = "task.task.progressed.v1"
VN = dt.timezone(dt.timedelta(hours=7))


@dataclass
class Pending:
    progress_ids: set[str] = field(default_factory=set)
    offsets: list[tuple[int, int]] = field(default_factory=list)
    first_seen: float = 0.0


class Evt05Decoder:
    def __init__(self, schema_path: pathlib.Path) -> None:
        self.schema = fastavro.parse_schema(json.loads(schema_path.read_text(encoding="utf-8")))

    def decode(self, value: bytes) -> dict[str, Any]:
        rec = fastavro.schemaless_reader(io.BytesIO(value), self.schema, None)
        if not isinstance(rec, dict):
            raise ValueError("EVT-05 không phải bản ghi")
        return rec


class DelayRiskService:
    def __init__(self, consumer: Any, ch: ClickHouse, calendars: CalendarClient, sink: DelayRiskSink,
                 decoder: Evt05Decoder, params: Params, max_wait: float = 240,
                 today: Callable[[], dt.date] = lambda: dt.datetime.now(VN).date(),
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._consumer, self._ch, self._cal, self._sink = consumer, ch, calendars, sink
        self._decoder, self._params, self._max_wait, self._today, self._clock = decoder, params, max_wait, today, clock
        self._pending: dict[tuple[str, str], Pending] = {}
        self._seen: dict[int, int] = {}
        self._committed: dict[int, int] = {}
        self.stats = {"ghi": 0, "cu": 0, "khong_co": 0, "khong_du_lieu": 0, "cdc_cham": 0, "loi": 0}

    def step(self, timeout: float = 1.0) -> None:
        for m in self._consumer.consume(500, timeout):
            if m.error() is not None:
                log.warning("lỗi Kafka: %s", m.error())
                continue
            self._seen[m.partition()] = max(self._seen.get(m.partition(), -1), m.offset())
            try:
                headers = dict(m.headers() or [])
                tenant = headers["tenantid"].decode()
                ev = self._decoder.decode(m.value())
                key = (tenant, str(ev["taskId"]))
                progress_id = str(ev["progressId"])
            except Exception as e:  # sự kiện hỏng: ghi nhật ký rồi bỏ, không chặn phân vùng
                log.error("bỏ EVT-05 không đọc được ở %s/%d: %s", m.partition(), m.offset(), type(e).__name__)
                continue
            p = self._pending.setdefault(key, Pending(first_seen=self._clock()))
            p.progress_ids.add(progress_id)
            p.offsets.append((m.partition(), m.offset()))
        if self._pending:
            self._process()
        self._commit()

    def _process(self) -> None:
        today = self._today()
        by_tenant: dict[str, list[str]] = {}
        for tenant, task in self._pending:
            by_tenant.setdefault(tenant, []).append(task)
        for tenant, tasks in by_tenant.items():
            try:
                loaded = source.load(self._ch, tenant, tasks)
            except Exception as e:
                log.error("không đọc được kho cho tenant %s: %s", tenant, e)
                continue
            for task in tasks:
                key = (tenant, task)
                p = self._pending[key]
                got = loaded.get(task)
                if got is None or not p.progress_ids <= got.progress_ids:
                    if self._clock() - p.first_seen > self._max_wait:
                        log.warning("CDC chậm quá %ss cho nhiệm vụ %s, giữ giá trị cũ", self._max_wait, task)
                        self.stats["cdc_cham"] += 1
                        del self._pending[key]
                    continue
                try:
                    years = range(min(today, got.task.due).year, max(today, got.task.due).year + 1)
                    f = forecast(got.task, today, self._cal.get(tenant, years), self._params)
                    if f is None:
                        self.stats["khong_du_lieu"] += 1
                    else:
                        self.stats[self._sink.put(tenant, task, f, today, got.latest_progress_id)] += 1
                except (CalendarError, SinkError) as e:
                    self.stats["loi"] += 1
                    log.error("chưa ghi được dự báo cho nhiệm vụ %s: %s", task, e)
                    if self._clock() - p.first_seen <= self._max_wait:
                        continue  # thử lại ở vòng sau
                del self._pending[key]

    def _commit(self) -> None:
        low: dict[int, int] = {}
        for p in self._pending.values():
            for part, off in p.offsets:
                low[part] = min(low.get(part, off), off)
        offsets = []
        for part, last in self._seen.items():
            nxt = low.get(part, last + 1)
            if self._committed.get(part) != nxt:
                offsets.append(TopicPartition(TOPIC, part, nxt))
                self._committed[part] = nxt
        if offsets:
            self._consumer.commit(offsets=offsets, asynchronous=False)

    @property
    def pending(self) -> int:
        return len(self._pending)

"""Unit and async integration tests for services-py/vtplatform (NEN-018)."""

from __future__ import annotations

import asyncio
import base64
import io
import json
import pathlib
import unittest
from typing import Any, Dict, List

from vtplatform import db, kafka, logging as vt_logging, otel, problem, tenant


def _make_jwt(claims: Dict[str, Any]) -> str:
    header = base64.urlsafe_b64encode(json.dumps({"alg": "RS256", "typ": "JWT"}).encode("utf-8")).decode("ascii").rstrip("=")
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode("utf-8")).decode("ascii").rstrip("=")
    return f"{header}.{payload}.sig"


class TestProblemModule(unittest.TestCase):
    def test_catalog_matches_canonical_error_codes(self) -> None:
        canonical_path = (
            pathlib.Path(__file__).resolve().parent.parent.parent.parent
            / "backend"
            / "libs"
            / "vt-kernel"
            / "src"
            / "main"
            / "resources"
            / "error-codes.json"
        )
        canonical = json.loads(canonical_path.read_text(encoding="utf-8"))
        self.assertEqual(problem.catalog_size(), len(canonical["codes"]))
        entry = problem.lookup_code("COMMON-403-FORBIDDEN")
        self.assertIsNotNone(entry)
        assert entry is not None
        self.assertEqual(entry.http_status, 403)
        self.assertTrue(entry.type_uri.startswith("https://okrkpi.vn/problems/"))

    def test_sanitize_detail_masks_sql_and_stacktrace(self) -> None:
        raw_sql = 'ERROR: duplicate key value violates unique constraint "uq_task" on relation "core.task" (SQLSTATE 23505)'
        p = problem.from_code("COMMON-500-INTERNAL-ERROR", detail=raw_sql, instance="/api/v1/analytics", trace_id="ab" * 16)
        payload = p.to_dict()
        self.assertNotIn("core.task", payload["detail"])
        self.assertNotIn("SQLSTATE", payload["detail"])
        self.assertEqual(payload["detail"], payload["title"])
        self.assertEqual(payload["traceId"], "ab" * 16)


class TestOtelAndLoggingModules(unittest.TestCase):
    def test_otel_config_validation_and_w3c_traceparent(self) -> None:
        with self.assertRaises(ValueError):
            otel.init_otel(otel.Config(service_name="", cell="cell-hn"))
        with self.assertRaises(ValueError):
            otel.init_otel(otel.Config(service_name="ai-analytics-service", cell=""))

        provider = otel.init_otel(otel.Config(service_name="ai-analytics-service", cell="cell-hn"))
        attrs = provider.resource_attributes()
        self.assertEqual(attrs["service.name"], "ai-analytics-service")
        self.assertEqual(attrs["cell"], "cell-hn")

        incoming_tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        sc = otel.extract_headers({"Traceparent": incoming_tp})
        self.assertEqual(sc.trace_id, "4bf92f3577b34da6a3ce929d0e0e4736")
        self.assertEqual(sc.parent_id, "00f067aa0ba902b7")
        self.assertNotEqual(sc.span_id, "00f067aa0ba902b7")

    def test_json_logger_injects_trace_span_tenant_and_scrubs_pii(self) -> None:
        buf = io.StringIO()
        logger = vt_logging.configure_logger(
            name="test_vtplatform_logger",
            service_name="ai-analytics-service",
            cell="cell-hn",
            stream=buf,
        )
        provider = otel.init_otel(otel.Config(service_name="ai-analytics-service", cell="cell-hn"))
        parent_sc = otel.parse_traceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        tenant_a = "11111111-1111-4111-8111-111111111111"

        with tenant.tenant_context(tenant_a, actor_id="actor-99"):
            with provider.start_span("analyze", parent=parent_sc) as span:
                logger.info(
                    "Phát hiện bất thường cho CCCD 001090012345 SĐT 0987654321 và +84912345678 email canbo@gov.vn token Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
                    extra={
                        "vt_fields": {
                            "password": "my-secret-password",
                            "nested": {"cccd": "038095001122", "note": "mail a@b.vn"},
                        }
                    },
                )
                expected_span_id = span.context.span_id

        raw_line = buf.getvalue().strip()
        parsed = json.loads(raw_line)
        self.assertEqual(parsed["trace.id"], "4bf92f3577b34da6a3ce929d0e0e4736")
        self.assertEqual(parsed["span.id"], expected_span_id)
        self.assertEqual(parsed["tenant.id"], tenant_a)
        self.assertEqual(parsed["service.name"], "ai-analytics-service")
        self.assertEqual(parsed["cell"], "cell-hn")
        self.assertEqual(parsed["password"], vt_logging.REDACTED_SECRET)

        for leaked in [
            "001090012345",
            "0987654321",
            "+84912345678",
            "canbo@gov.vn",
            "eyJhbGciOiJIUzI1NiJ9",
            "my-secret-password",
            "038095001122",
            "a@b.vn",
        ]:
            self.assertNotIn(leaked, raw_line)


class TestTenantDbAndKafkaAsync(unittest.TestCase):
    def test_tenant_asgi_middleware_and_configurable_claim(self) -> None:
        async def _run() -> None:
            captured_tenants: List[str] = []

            async def dummy_app(scope: Dict[str, Any], receive: Any, send: Any) -> None:
                captured_tenants.append(tenant.must_get_tenant_id())
                await send({"type": "http.response.start", "status": 200, "headers": []})
                await send({"type": "http.response.body", "body": b"ok"})

            mw = tenant.TenantMiddleware(dummy_app, claim_name="custom_tid")

            # 1. Missing token -> 403 Problem Details
            messages: List[Dict[str, Any]] = []

            async def capture_send(msg: Dict[str, Any]) -> None:
                messages.append(msg)

            await mw({"type": "http", "path": "/api/v1/analytics", "headers": []}, None, capture_send)
            self.assertEqual(messages[0]["status"], 403)
            body = json.loads(messages[1]["body"].decode("utf-8"))
            self.assertEqual(body["code"], "COMMON-403-FORBIDDEN")

            # 2. Invalid UUID in claim -> 403 Problem Details
            messages.clear()
            bad_token = _make_jwt({"custom_tid": "not-a-uuid"})
            await mw(
                {
                    "type": "http",
                    "path": "/api/v1/analytics",
                    "headers": [(b"authorization", f"Bearer {bad_token}".encode("ascii"))],
                },
                None,
                capture_send,
            )
            self.assertEqual(messages[0]["status"], 403)

            # 3. Valid custom claim -> 200 OK and tenant bound in contextvars
            messages.clear()
            tenant_a = "11111111-1111-4111-8111-111111111111"
            good_token = _make_jwt({"custom_tid": tenant_a.upper(), "sub": "user-1"})
            await mw(
                {
                    "type": "http",
                    "path": "/api/v1/analytics",
                    "headers": [(b"authorization", f"Bearer {good_token}".encode("ascii"))],
                },
                None,
                capture_send,
            )
            self.assertEqual(messages[0]["status"], 200)
            self.assertEqual(captured_tenants, [tenant_a])

        asyncio.run(_run())

    def test_db_in_tx_pgbouncer_single_conn_zero_cross_tenant_leak(self) -> None:
        async def _run() -> None:
            pool = db.SimulatedAsyncPool(pool_size=1)
            tenant_a = "11111111-1111-4111-8111-111111111111"
            tenant_b = "22222222-2222-4222-8222-222222222222"

            # Seed 25 rows for tenant A and 25 rows for tenant B over single physical connection
            for i in range(25):
                async with db.in_tenant_tx(pool, tenant_a) as tx:
                    await tx.execute("INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)", f"a-{i}", tenant_a, f"A {i}")
                async with db.in_tenant_tx(pool, tenant_b) as tx:
                    await tx.execute("INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)", f"b-{i}", tenant_b, f"B {i}")

            async def worker(iteration: int) -> None:
                want_tenant = tenant_a if iteration % 2 == 0 else tenant_b
                async with db.in_tenant_tx(pool, want_tenant) as tx:
                    guc = await tx.fetchval("SELECT current_setting('app.tenant_id', true)")
                    self.assertEqual(guc, want_tenant)
                    rows = await tx.fetch("SELECT id, tenant_id, title FROM rls_items")
                    self.assertEqual(len(rows), 25)
                    for row in rows:
                        self.assertEqual(row["tenant_id"], want_tenant)

            await asyncio.gather(*(worker(i) for i in range(200)))

            # Verify session-level SET app.tenant_id is forbidden
            with self.assertRaises(db.SessionTenantForbiddenError):
                async with db.in_tenant_tx(pool, tenant_a) as tx:
                    await tx.execute("SET app.tenant_id = '22222222-2222-4222-8222-222222222222'")

            # Verify cross-tenant INSERT is rejected by FORCE RLS
            with self.assertRaises(db.RLSViolationError):
                async with db.in_tenant_tx(pool, tenant_a) as tx:
                    await tx.execute("INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)", "spoof", tenant_b, "Spoof")

        asyncio.run(_run())

    def test_kafka_inbox_guard_and_direct_producer_evt20(self) -> None:
        async def _run() -> None:
            pool = db.SimulatedAsyncPool(pool_size=1)
            guard = kafka.InboxGuard(schema="analytics", consumer="svc-14-anomaly-consumer")
            tenant_a = "11111111-1111-4111-8111-111111111111"

            evt_v2 = kafka.IncomingEvent(
                event_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
                tenant_id=tenant_a,
                aggregate_type="TASK",
                aggregate_id="task-500",
                aggregate_version=2,
                source_topic="task.progress.updated.v1",
                partition=0,
                offset=10,
                payload=b'{"taskId":"task-500"}',
            )
            calls = 0

            async def handler(tx: db.SafeAsyncTx, ev: kafka.IncomingEvent) -> None:
                nonlocal calls
                calls += 1
                await tx.execute(
                    "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
                    ev.event_id,
                    ev.tenant_id,
                    "Processed " + ev.aggregate_id,
                )

            # 1. First delivery -> PROCESSED
            outcome = await guard.process(pool, evt_v2, handler)
            self.assertEqual(outcome, kafka.InboxOutcome.PROCESSED)
            self.assertEqual(calls, 1)

            # 2. Duplicate delivery -> DUPLICATE_EVENT
            outcome = await guard.process(pool, evt_v2, handler)
            self.assertEqual(outcome, kafka.InboxOutcome.DUPLICATE_EVENT)
            self.assertEqual(calls, 1)

            # 3. Stale version (v1 arrives after v2) -> STALE_VERSION
            evt_v1 = kafka.IncomingEvent(
                event_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
                tenant_id=tenant_a,
                aggregate_type="TASK",
                aggregate_id="task-500",
                aggregate_version=1,
                source_topic="task.progress.updated.v1",
                payload=b'{"taskId":"task-500"}',
            )
            outcome = await guard.process(pool, evt_v1, handler)
            self.assertEqual(outcome, kafka.InboxOutcome.STALE_VERSION)
            self.assertEqual(calls, 1)

            # 4. DirectProducer for SVC-14 EVT-20: deterministic event_id from alertKey + C2/C3 rejection
            producer = kafka.DirectProducer()
            alert = kafka.AnomalyAlertPayload(
                alert_key="TU_CHAM_CAO:CA_NHAN:0192f000-0000-7000-8000-0000000000b1:2026-Q4",
                signal_code="TU_CHAM_CAO",
                severity="CAO",
                subject_type="CA_NHAN",
                subject_id="0192f000-0000-7000-8000-0000000000b1",
                evidence_chain=["ky:0192f000-0000-7000-8000-0000000000a4", "so_lieu:CHENH_DIEM=6.5"],
                model_version="ew-v1.2.0",
            )
            with tenant.tenant_context(tenant_a):
                for forbidden_cls in ("C2", "C3"):
                    with self.assertRaises(kafka.InvalidClassificationError):
                        await producer.publish_anomaly_alert(alert, classification=forbidden_cls)

                rec1 = await producer.publish_anomaly_alert(alert, classification="C1")
                rec2 = await producer.publish_anomaly_alert(alert, classification="C1")

            self.assertEqual(rec1.topic, "analytics.anomaly.detected.v1")
            self.assertEqual(rec1.headers["ce_type"], "EVT-20")
            self.assertEqual(rec1.headers["ce_id"], rec2.headers["ce_id"])
            self.assertTrue(tenant.is_valid_uuid(rec1.headers["ce_id"]))
            self.assertEqual(
                json.loads(alert.to_avro_dict()["evidenceChain"]),
                ["ky:0192f000-0000-7000-8000-0000000000a4", "so_lieu:CHENH_DIEM=6.5"],
            )
            # HET_DAU_HIEU (CORE-07-10): distinct event id so the consumer inbox does not drop the resolution.
            alert.severity = "HET_DAU_HIEU"
            with tenant.tenant_context(tenant_a):
                rec3 = await producer.publish_anomaly_alert(alert, classification="C1")
            self.assertNotEqual(rec3.headers["ce_id"], rec1.headers["ce_id"])
            for bad in ({"signal_code": "CB_01"}, {"severity": "HIGH"}, {"subject_type": "TASK"}, {"subject_id": "task-500"}):
                with self.assertRaises(ValueError):
                    kafka.AnomalyAlertPayload(**{**alert.__dict__, **bad}).validate()

        asyncio.run(_run())


if __name__ == "__main__":
    unittest.main()

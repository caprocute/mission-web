"""Kiểm thử tích hợp DV-15-01 trên ClickHouse 25.8 và Kafka 3.9 thật: bản ghi CDC mẫu (tạo, sửa, xoá) của từng bảng
nguồn đi qua Kafka engine và materialized view vào đúng bảng đích; gắn đơn vị qua từ điển; độ trễ p99; row policy
theo tenant và quyền theo cột của tài khoản Cube."""

from __future__ import annotations

import hashlib
import json
import pathlib
import statistics
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

import pytest
from confluent_kafka import Producer

from tests.conftest import Analytics

pytestmark = pytest.mark.integration

TENANT_A = "0192f000-0000-7000-8000-00000000000a"
TENANT_B = "0192f000-0000-7000-8000-00000000000b"
EPOCH_DAYS = (datetime(2026, 10, 22) - datetime(1970, 1, 1)).days  # io.debezium.time.Date
INFINITY_DAYS = str(2147483647)


def key(person: str, tenant: str = TENANT_A) -> str:
    """Giả lập column.mask.hash.SHA-256.with.salt.<muối_tenant> của connector DV-15-02."""
    return hashlib.sha256((f"muoi-{tenant}" + person).encode()).hexdigest()


def ts(offset_s: int = 0) -> str:
    return datetime.fromtimestamp(time.time() + offset_s, UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


class Cdc:
    """Ghi bản ghi Debezium đã làm phẳng; khoá Kafka là id như connector (giữ thứ tự theo dòng)."""

    def __init__(self, bootstrap: str) -> None:
        self.p = Producer({"bootstrap.servers": bootstrap, "linger.ms": 5})
        self.lsn = 1_000_000

    def send(self, topic: str, row: dict[str, Any], op: str = "c", lsn: int | None = None) -> int:
        if lsn is None:
            self.lsn += 10
            lsn = self.lsn
        msg = {**row, "__op": op, "__lsn": lsn, "__source_ts_ms": int(time.time() * 1000),
               "__deleted": "true" if op == "d" else "false"}
        self.p.produce(topic, key=str(row.get("id", "")), value=json.dumps(msg))
        return lsn

    def raw(self, topic: str, value: bytes) -> None:
        self.p.produce(topic, value=value)

    def flush(self) -> None:
        assert self.p.flush(30) == 0


@pytest.fixture(scope="module")
def cdc(analytics: Analytics) -> Cdc:
    return Cdc(analytics.bootstrap)


def wait(fn: Callable[[], bool], timeout: float = 90, what: str = "") -> float:
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        if fn():
            return time.monotonic() - t0
        time.sleep(0.5)
    raise AssertionError(f"quá {timeout} s chưa thấy {what}")


def count(a: Analytics, table: str, where: str = "1") -> int:
    return int(a.query(f"SELECT count() FROM {a.db}.{table} WHERE {where}").strip())


def u() -> str:
    return str(uuid.uuid4())


def common(period_bucket: int = 202604) -> dict[str, Any]:
    return {"tenant_id": TENANT_A, "period_id": u(), "period_bucket": period_bucket,
            "created_at": ts(), "updated_at": ts()}


# Mẫu một dòng nguồn cho từng topic: (bảng đích, dòng, cột đổi khi sửa, giá trị mới ở nguồn, cột đích, giá trị đích).
def samples() -> dict[str, tuple[str, dict[str, Any], str, Any, str, Any]]:
    c = common()
    unit, person = u(), u()
    return {
        "cdc.tenant_data.task.task": ("fact_task", {
            **c, "id": u(), "code": "NV-0001", "task_group": "THUONG_XUYEN", "is_key_task": False, "source": "GIAO",
            "assigner_id": key(person), "assigned_at": ts(), "due_date": EPOCH_DAYS + 30, "planned_quantity": "1.0000",
            "done_quantity": "0.0000", "progress_pct": "0.2500", "status": "DA_GIAO", "is_overdue": False,
            "is_restricted": False, "title": "không được nạp"}, "status", "CHO_NGHIEM_THU", "status", "CHO_NGHIEM_THU"),
        "cdc.tenant_data.task.task_assignee": ("fact_task_assignee", {
            "tenant_id": TENANT_A, "period_bucket": 202604, "id": u(), "task_id": u(), "assignee_type": "DON_VI",
            "org_unit_id": unit, "role": "PHOI_HOP", "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS},
            "role", "CHU_TRI", "role", "CHU_TRI"),
        "cdc.tenant_data.task.task_progress": ("fact_task_progress", {
            "tenant_id": TENANT_A, "period_bucket": 202604, "id": u(), "task_id": u(), "created_by": key(person),
            "reported_at": ts(), "progress_pct": "0.3000", "channel": "WEB", "note": "không được nạp"},
            "progress_pct", "0.4000", "progress_pct", "0.4"),
        "cdc.tenant_data.product.evidence": ("fact_evidence", {
            **c, "id": u(), "deliverable_id": u(), "task_id": u(), "created_by": key(person),
            "current_status": "DA_NOP", "source_type": "TAI_LEN"}, "current_status", "DA_XAC_NHAN",
            "current_status", "DA_XAC_NHAN"),
        "cdc.tenant_data.okr.objective": ("fact_okr_objective", {
            **c, "id": u(), "owner_type": "DON_VI", "owner_unit_id": unit, "tier": 1, "status": "NHAP",
            "progress": "0.0000", "title_enc": "AAEC"}, "progress", "0.4500", "progress", "0.45"),
        "cdc.tenant_data.okr.key_result": ("fact_okr_key_result", {
            **c, "id": u(), "objective_id": u(), "metric_type": "SO_LUONG", "direction": "TANG",
            "baseline_value": "10.0000", "target_value": "20.0000", "weight": "0.5000", "due_date": EPOCH_DAYS + 60,
            "status": "DA_DUYET", "title": "không được nạp"}, "target_value", "25.0000", "target_value", "25"),
        "cdc.tenant_data.task.task_change_request": ("fact_task_change_request", {
            **c, "id": u(), "task_id": u(), "request_type": "GIA_HAN", "status": "CHO_DUYET",
            "is_objective_reason": True, "cause_claimed": "KHACH_QUAN", "cause_decided": None,
            "created_by": key(person), "decided_by": None, "decided_at": None, "reason": "không được nạp"},
            "status", "DA_DUYET", "status", "DA_DUYET"),
        "cdc.tenant_data.okr.kr_checkin": ("fact_okr_checkin", {
            **c, "id": u(), "key_result_id": u(), "created_by": key(person), "checkin_at": ts(), "value": "12.5000",
            "confidence": "CAO", "channel": "WEB", "status": "NHAP"}, "status", "DA_GUI", "status", "DA_GUI"),
        "cdc.tenant_data.appraisal.appraisal_profile": ("fact_appraisal_profile", {
            **c, "id": u(), "person_id": key(person), "org_unit_id": unit, "flow": "DANG", "is_head": False,
            "is_leadership": False, "status": "DANG_TU_DANH_GIA"}, "status", "CHO_THAM_DINH", "status",
            "CHO_THAM_DINH"),
        "cdc.tenant_data.appraisal.score_snapshot": ("fact_score", {
            **c, "id": u(), "profile_id": u(), "flow": "DANG", "snapshot_kind": "TAM_TINH",
            "policy_pack_version_id": u(), "policy_version": "KHOI_DANG v2.0", "total_score": "86.500",
            "auto_rating_code": "HTTNV", "computed_at": ts(), "superseded_by_id": None},
            "total_score", "91.250", "total_score", "91.25"),
        "cdc.tenant_data.appraisal.rating_proposal": ("fact_rating", {
            **c, "id": u(), "profile_id": u(), "level_kind": "TU_DE_XUAT", "rating_code": "HTTNV",
            "score_snapshot_id": u(), "actor_id": key(person), "actor_role": "staff", "is_current": True,
            "differs_from_prev": False, "explanation_required": False, "is_bulk_decision": False,
            "proposed_at": ts(), "reason_enc": "AAEC"}, "rating_code", "HTXSNV", "rating_code", "HTXSNV"),
        "cdc.tenant_data.org_replica.org_unit_v": ("dim_org_unit", {
            "tenant_id": TENANT_A, "id": u(), "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS, "code": "DV01",
            "name": "Ban Tổ chức", "unit_type": "BAN", "source_version": 1}, "name", "Ban Tổ chức Tỉnh uỷ",
            "name", "Ban Tổ chức Tỉnh uỷ"),
        "cdc.tenant_data.org_replica.person_v": ("dim_person", {
            "tenant_id": TENANT_A, "id": key(u()), "is_party_member": True, "employment_status": "DANG_CONG_TAC",
            "full_name": "không được nạp"}, "employment_status", "NGHI_HUU", "employment_status", "NGHI_HUU"),
        "cdc.tenant_data.org_replica.assignment_v": ("lk_assignment", {
            "tenant_id": TENANT_A, "id": u(), "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS,
            "person_id": key(person), "org_unit_id": unit, "job_title_id": u(), "kind": "KIEM_NHIEM",
            "branch": "DANG", "is_head": False}, "kind", "CHINH", "kind", "CHINH"),
        "cdc.tenant_data.org_replica.unit_closure_v": ("dim_org_closure", {
            "tenant_id": TENANT_A, "tree": "DANG", "ancestor_id": unit, "descendant_id": u(), "depth": 1},
            "depth", 2, "depth", 2),
        "cdc.tenant_data.config.period": ("dim_period", {
            **c, "id": u(), "code": "2026-Q4", "name": "Quý IV năm 2026", "kind": "QUY", "flow": "DANG",
            "start_date": EPOCH_DAYS, "end_date": EPOCH_DAYS + 70, "status": "MO"}, "status", "DA_KHOA",
            "status", "DA_KHOA"),
        "cdc.tenant_data.policy.policy_pack_version": ("dim_policy_version", {
            "tenant_id": TENANT_A, "id": u(), "policy_pack_id": u(), "version": "2.0", "status": "NHAP"},
            "status", "DA_PHAT_HANH", "status", "DA_PHAT_HANH"),
        "cdc.org.org.subject_group": ("dim_subject_group", {
            "tenant_id": TENANT_A, "id": u(), "code": "K1", "name": "Khối Đảng", "flow": "DANG",
            "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS}, "name", "Khối cơ quan Đảng", "name",
            "Khối cơ quan Đảng"),
    }


def _key_column(table: str) -> str:
    return {"dim_person": "person_key", "dim_org_closure": "descendant_id"}.get(table, "id")


def _key_value(table: str, row: dict[str, Any]) -> str:
    if table == "dim_person":
        return f"unhex('{row['id']}')"
    return f"toUUID('{row[_key_column(table)]}')"


def test_create_update_delete_each_source(analytics: Analytics, cdc: Cdc) -> None:
    """Mỗi bảng nguồn: dòng 1 tạo rồi sửa, dòng 2 tạo rồi xoá. Sau OPTIMIZE FINAL bảng đích còn đúng một phiên bản
    mỗi dòng: dòng 1 mang giá trị đã sửa, dòng 2 mang _is_deleted = 1 và không hiện khi đọc bằng FINAL."""
    cases = samples()
    seconds: dict[str, tuple[dict[str, Any], dict[str, Any]]] = {}
    for topic, (table, row, col, new, _, _) in cases.items():
        other = {**row, "id": key(u()) if table == "dim_person" else u()}
        if table == "dim_org_closure":
            other["descendant_id"] = u()
        seconds[topic] = (row, other)
        cdc.send(topic, row, "c")
        cdc.send(topic, other, "c")
        cdc.send(topic, {**row, col: new}, "u")
        cdc.send(topic, other, "d")
    cdc.flush()
    # Dòng của cùng khoá nằm trong một khối nạp có thể đã gộp ngay khi ghi (optimize_on_insert), nên chờ theo trạng
    # thái cuối thay vì theo số dòng.
    for topic, (table, _, _, _, tcol, tval) in cases.items():
        k = _key_column(table)
        r1, r2 = seconds[topic]
        wait(lambda t=table, a=r1, b=r2, kk=k, c=tcol, v=tval, db=analytics.db: analytics.rows(
                 f"SELECT toString({c}) AS v FROM {db}.{t} FINAL WHERE {kk} = {_key_value(t, a)}") == [{"v": str(v)}]
             and count(analytics, t, f"{kk} = {_key_value(t, b)} AND _is_deleted = 1") >= 1,
             what=f"{topic} trong {table}")
    for topic, (table, _, _, _, tcol, tval) in cases.items():
        db = analytics.db
        analytics.query(f"OPTIMIZE TABLE {db}.{table} FINAL")
        k = _key_column(table)
        row1, row2 = seconds[topic]
        final = analytics.rows(f"SELECT toString({tcol}) AS v, _is_deleted FROM {db}.{table} FINAL "
                               f"WHERE {k} = {_key_value(table, row1)}")
        assert final == [{"v": str(tval), "_is_deleted": 0}], (topic, final)
        assert analytics.rows(f"SELECT 1 FROM {db}.{table} FINAL WHERE {k} = {_key_value(table, row2)}") == [], topic
        gone = analytics.rows(f"SELECT _is_deleted FROM {db}.{table} WHERE {k} = {_key_value(table, row2)}")
        assert gone == [{"_is_deleted": 1}], (topic, gone)
    # Cột tự do, C2 không có chỗ trong bảng đích; mã người chỉ còn dạng băm 32 byte.
    db = analytics.db
    cols = {r["name"] for r in analytics.rows(
        f"SELECT name FROM system.columns WHERE database = '{db}' AND table NOT LIKE 'src\\_%'")}
    assert not cols & {"title", "note", "full_name", "cbccvc_code", "reason_enc", "comment_enc", "created_by",
                       "person_id", "actor_id", "assigner_id", "verified_by",
                       "decided_by", "reason", "decision_note"}
    assert count(analytics, "cdc_error") == 0


def test_units_resolved_through_dictionaries(analytics: Analytics, cdc: Cdc) -> None:
    db = analytics.db
    unit_a, unit_b, person, task_a, task_b = u(), u(), u(), u(), u()
    # Cá nhân chủ trì: đơn vị lấy từ phân công chính đang hiệu lực.
    assignment = u()
    cdc.send("cdc.tenant_data.org_replica.assignment_v", {
        "tenant_id": TENANT_A, "id": assignment, "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS,
        "person_id": key(person), "org_unit_id": unit_b, "job_title_id": u(), "kind": "CHINH", "branch": "DANG",
        "is_head": False})
    # Phân công có từ trước khi giao việc (đơn vị của cá nhân chủ trì tra lúc nạp dòng chủ trì).
    cdc.flush()
    wait(lambda: count(analytics, "lk_assignment", f"id = '{assignment}'") == 1, what="phân công")
    analytics.query("SYSTEM RELOAD DICTIONARIES")
    cdc.send("cdc.tenant_data.task.task_assignee", {
        "tenant_id": TENANT_A, "period_bucket": 202604, "id": u(), "task_id": task_a, "assignee_type": "DON_VI",
        "org_unit_id": unit_a, "role": "CHU_TRI", "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS})
    cdc.send("cdc.tenant_data.task.task_assignee", {
        "tenant_id": TENANT_A, "period_bucket": 202604, "id": u(), "task_id": task_b, "assignee_type": "CA_NHAN",
        "person_id": key(person), "role": "CHU_TRI", "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS})
    objective, kr, profile, sg = u(), u(), u(), u()
    cdc.send("cdc.tenant_data.okr.objective", {
        **common(), "id": objective, "owner_type": "DON_VI", "owner_unit_id": unit_a, "tier": 1, "status": "DA_DUYET",
        "progress": "0.2000"})
    cdc.send("cdc.tenant_data.okr.key_result", {"tenant_id": TENANT_A, "id": kr, "objective_id": objective})
    cdc.send("cdc.tenant_data.appraisal.appraisal_profile", {
        **common(), "id": profile, "person_id": key(person), "org_unit_id": unit_b, "subject_group_id": sg,
        "flow": "NHA_NUOC", "is_head": False, "is_leadership": False, "status": "CHO_THAM_DINH"})
    cdc.flush()
    wait(lambda: count(analytics, "fact_task_assignee", f"task_id = '{task_b}'") == 1
         and count(analytics, "lk_key_result", f"id = '{kr}'") == 1
         and count(analytics, "fact_appraisal_profile", f"id = '{profile}'") == 1, what="bảng tra cứu")
    analytics.query("SYSTEM RELOAD DICTIONARIES")

    p1, p2, ev, ck, sc, rt = u(), u(), u(), u(), u(), u()
    for pid, task in ((p1, task_a), (p2, task_b)):
        cdc.send("cdc.tenant_data.task.task_progress", {
            "tenant_id": TENANT_A, "period_bucket": 202604, "id": pid, "task_id": task, "created_by": key(person),
            "reported_at": ts(), "progress_pct": "0.5000", "channel": "MOBILE"})
    cdc.send("cdc.tenant_data.product.evidence", {
        **common(), "id": ev, "deliverable_id": u(), "task_id": task_a, "created_by": key(person),
        "current_status": "DA_NOP", "source_type": "TAI_LEN"})
    cdc.send("cdc.tenant_data.okr.kr_checkin", {
        **common(), "id": ck, "key_result_id": kr, "created_by": key(person), "checkin_at": ts(), "value": "3",
        "confidence": "VUA", "channel": "WEB", "status": "DA_GUI", "verified_by": key(u())})
    cdc.send("cdc.tenant_data.appraisal.score_snapshot", {
        **common(), "id": sc, "profile_id": profile, "flow": "NHA_NUOC", "snapshot_kind": "CHOT_KY",
        "policy_pack_version_id": u(), "policy_version": "NHA_NUOC v1.0", "total_score": "72.000",
        "general_criteria_score": "25.000", "kpi_score": "47.000", "auto_rating_code": "HTNV", "computed_at": ts()})
    cdc.send("cdc.tenant_data.appraisal.rating_proposal", {
        **common(), "id": rt, "profile_id": profile, "level_kind": "QUYET_DINH", "rating_code": "HTNV",
        "score_snapshot_id": sc, "actor_id": key(u()), "actor_role": "decider", "is_current": True,
        "differs_from_prev": True, "explanation_required": True, "is_bulk_decision": False, "proposed_at": ts(),
        "signed_at": ts()})
    cdc.flush()
    wait(lambda: count(analytics, "fact_rating", f"id = '{rt}'") == 1
         and count(analytics, "fact_task_progress", f"id IN ('{p1}', '{p2}')") == 2
         and count(analytics, "fact_okr_checkin", f"id = '{ck}'") == 1
         and count(analytics, "fact_evidence", f"id = '{ev}'") == 1, what="bảng sự kiện")

    units = {r["id"]: r["org_unit_id"] for r in analytics.rows(
        f"SELECT toString(id) AS id, toString(org_unit_id) AS org_unit_id FROM {db}.fact_task_progress "
        f"WHERE id IN ('{p1}', '{p2}')")}
    assert units == {p1: unit_a, p2: unit_b}
    assert analytics.rows(f"SELECT toString(org_unit_id) AS u FROM {db}.fact_evidence WHERE id = '{ev}'") == [
        {"u": unit_a}]
    assert analytics.rows(f"SELECT toString(org_unit_id) AS u, toString(objective_id) AS o FROM {db}.fact_okr_checkin "
                          f"WHERE id = '{ck}'") == [{"u": unit_a, "o": objective}]
    expected_key = key(person).upper()
    for table, extra in (("fact_score", "is_current"), ("fact_rating", "is_signed")):
        got = analytics.rows(
            f"SELECT hex(person_key) AS k, toString(org_unit_id) AS u, toString(subject_group_id) AS sg, flow, "
            f"{extra} AS x FROM {db}.{table} WHERE profile_id = '{profile}'")
        assert got == [{"k": expected_key, "u": unit_b, "sg": sg, "flow": "NHA_NUOC", "x": 1}], (table, got)


def test_bad_record_goes_to_cdc_error(analytics: Analytics, cdc: Cdc) -> None:
    before = count(analytics, "cdc_error", "topic = 'cdc.tenant_data.config.period'")
    cdc.raw("cdc.tenant_data.config.period", b"{khong phai json")
    good = {**common(), "id": u(), "code": "2026-Q3", "name": "Quý III năm 2026", "kind": "QUY", "flow": "DANG",
            "start_date": EPOCH_DAYS - 90, "end_date": EPOCH_DAYS - 1, "status": "MO"}
    cdc.send("cdc.tenant_data.config.period", good)
    cdc.flush()
    wait(lambda: count(analytics, "dim_period", f"id = '{good['id']}'") == 1, what="dòng hợp lệ sau dòng lỗi")
    wait(lambda: count(analytics, "cdc_error", "topic = 'cdc.tenant_data.config.period'") == before + 1,
         what="bản ghi lỗi")


def test_latency_p99_under_60_seconds(analytics: Analytics, cdc: Cdc) -> None:
    """Tải thử cục bộ: 5.000 bản ghi tiến độ ghi liên tục vào topic. Độ trễ từng dòng = thời điểm ClickHouse nạp
    (_ingested_at) trừ thời điểm ghi vào topic (__source_ts_ms do bộ ghi đặt ngay lúc gửi)."""
    db, n, batch = analytics.db, 5000, f"{uuid.uuid4().hex[:8]}"
    task = u()
    t0 = time.monotonic()
    for i in range(n):
        cdc.send("cdc.tenant_data.task.task_progress", {
            "tenant_id": TENANT_A, "period_bucket": 202604, "id": u(), "task_id": task, "created_by": key(batch),
            "reported_at": ts(), "progress_pct": f"{(i % 100) / 100:.4f}", "channel": "WEB"})
        if i % 500 == 0:
            cdc.p.poll(0)
    cdc.flush()
    elapsed = wait(lambda: count(analytics, "fact_task_progress", f"task_id = '{task}'") == n, timeout=120,
                   what="toàn bộ bản ghi tải thử")
    lat = [float(r["l"]) for r in analytics.rows(
        f"SELECT dateDiff('millisecond', _source_ts, _ingested_at) / 1000 AS l FROM {db}.fact_task_progress "
        f"WHERE task_id = '{task}'")]
    p99 = statistics.quantiles(lat, n=100)[98]
    print(f"\nDV-15-01 độ trễ CDC: n={n}, p50={statistics.median(lat):.2f}s, p99={p99:.2f}s, "
          f"max={max(lat):.2f}s, thấy đủ sau {time.monotonic() - t0:.1f}s (chờ {elapsed:.1f}s sau khi ghi xong)")
    assert p99 <= 60


def test_row_policy_and_column_grants(analytics: Analytics) -> None:
    db, p = analytics.db, analytics.params
    cube, ano = f"{db}_cube", f"{db}_anomaly"
    ids = {TENANT_A: u(), TENANT_B: u()}
    for tenant, tid in ids.items():
        analytics.query(
            f"INSERT INTO {db}.fact_task (tenant_id, period_bucket, id, period_id, code, task_group, is_key_task, "
            f"source, due_date, planned_quantity, done_quantity, progress_pct, status, is_overdue, is_restricted, "
            f"created_at, updated_at, _version, _is_deleted, _source_ts) VALUES ('{tenant}', 202699, '{tid}', "
            f"generateUUIDv4(), 'NV-RP', 'THUONG_XUYEN', 0, 'GIAO', today(), 1, 0, 0, 'DA_GIAO', 0, 0, now64(3), "
            f"now64(3), 1, 0, now64(3))")
    q = f"SELECT toString(tenant_id) AS t FROM {db}.fact_task FINAL WHERE period_bucket = 202699 ORDER BY t"
    assert analytics.rows(q, user=cube, password=p["cube_password"], SQL_tenant_id=TENANT_A) == [{"t": TENANT_A}]
    assert analytics.rows(q, user=cube, password=p["cube_password"], SQL_tenant_id=TENANT_B) == [{"t": TENANT_B}]
    assert analytics.rows(q, user=cube, password=p["cube_password"]) == []  # không đặt tenant: không thấy gì
    assert analytics.rows(q, user=ano, password=p["anomaly_password"]) == [{"t": TENANT_A}, {"t": TENANT_B}]

    def denied(sql: str, user: str, pw: str) -> None:
        with pytest.raises(RuntimeError, match=r"ACCESS_DENIED|Not enough privileges|READONLY"):
            analytics.query(sql, user=user, password=pw, SQL_tenant_id=TENANT_A)

    for user, pw in ((cube, p["cube_password"]), (ano, p["anomaly_password"])):
        denied(f"SELECT _version FROM {db}.fact_task", user, pw)        # cột kỹ thuật ngoài danh sách cho phép
        denied(f"SELECT * FROM {db}.lk_assignment", user, pw)           # bảng tra cứu nội bộ
        denied(f"SELECT * FROM {db}.src_task__task", user, pw)          # bảng Kafka engine
        denied(f"SELECT * FROM {db}.cdc_error", user, pw)
        denied(f"INSERT INTO {db}.dim_period (tenant_id) VALUES ('{TENANT_A}')", user, pw)
    denied(f"SELECT tenant_id FROM {db}.agg_task_tinh", ano, p["anomaly_password"])
    analytics.query(f"SELECT tenant_id FROM {db}.agg_task_tinh", user=cube, password=p["cube_password"],
                    SQL_tenant_id=TENANT_A)
    # Từ điển chỉ do tài khoản nạp nội bộ đọc; Cube không gọi được dictGet.
    denied(f"SELECT dictGet('{db}.dict_profile', 'flow', tuple(generateUUIDv4()))", cube, p["cube_password"])


def test_migrate_is_idempotent_and_detects_edited_files(analytics: Analytics, tmp_path: pathlib.Path) -> None:
    import shutil

    import migrate

    ch = migrate.ClickHouse(analytics.http, "default", analytics.admin_password, distributed_ddl_task_timeout="300")
    assert migrate.migrate(ch, analytics.params) == []
    for f in migrate.discover():
        shutil.copy(f.path, tmp_path / f.path.name)
    edited = tmp_path / "V007__bang_tong_hop_tinh.sql"
    edited.write_text(edited.read_text(encoding="utf-8") + "\n-- sửa tệp đã áp\n", encoding="utf-8")
    with pytest.raises(SystemExit, match="đã áp nhưng nội dung đã đổi"):
        migrate.migrate(ch, analytics.params, folder=tmp_path)


def test_derived_tables_and_cube_pre_aggregation_grants(analytics: Analytics, cdc: Cdc) -> None:
    """DV-15-03: dim_org_scope ghép org_path theo cây từ bảng bao đóng; data_freshness theo nguồn; Cube chỉ ghi được
    vào CSDL pre-aggregation {db}_pa_*."""
    db, p = analytics.db, analytics.params
    root, mid, leaf = u(), u(), u()
    for uid, code in ((root, "TW"), (mid, "VPTW"), (leaf, "VU-TONG-HOP")):
        cdc.send("cdc.tenant_data.org_replica.org_unit_v", {
            "tenant_id": TENANT_A, "id": uid, "valid_from": EPOCH_DAYS, "valid_to": INFINITY_DAYS, "code": code,
            "name": code, "unit_type": "BAN", "source_version": 1})
    for anc, desc, depth in ((root, root, 0), (mid, mid, 0), (leaf, leaf, 0), (root, mid, 1), (mid, leaf, 1),
                             (root, leaf, 2)):
        cdc.send("cdc.tenant_data.org_replica.unit_closure_v", {
            "tenant_id": TENANT_A, "tree": "DANG", "ancestor_id": anc, "descendant_id": desc, "depth": depth})
    cdc.flush()
    wait(lambda: count(analytics, "dim_org_closure", f"descendant_id = '{leaf}'") == 3
         and count(analytics, "dim_org_unit", f"id = '{leaf}'") == 1, what="cây đơn vị")
    analytics.query(f"SYSTEM REFRESH VIEW {db}.mv_dim_org_scope")
    analytics.query(f"SYSTEM WAIT VIEW {db}.mv_dim_org_scope")
    analytics.query(f"SYSTEM REFRESH VIEW {db}.mv_data_freshness")
    analytics.query(f"SYSTEM WAIT VIEW {db}.mv_data_freshness")
    got = analytics.rows(f"SELECT toString(id) AS id, org_path_dang AS p, org_path_chuyen_mon AS c "
                         f"FROM {db}.dim_org_scope WHERE id IN ('{root}', '{leaf}') ORDER BY p")
    assert got == [{"id": root, "p": "/tw", "c": ""}, {"id": leaf, "p": "/tw/vptw/vu-tong-hop", "c": ""}]
    assert count(analytics, "data_freshness", f"tenant_id = '{TENANT_A}' AND source = 'task'") == 1

    cube, pw = f"{db}_cube", p["cube_password"]
    q = f"SELECT org_path_dang FROM {db}.dim_org_scope WHERE id = '{leaf}'"
    assert analytics.rows(q, user=cube, password=pw, SQL_tenant_id=TENANT_A) == [
        {"org_path_dang": "/tw/vptw/vu-tong-hop"}]
    assert analytics.rows(q, user=cube, password=pw, SQL_tenant_id=TENANT_B) == []
    pa = f"{db}_pa_kiemthu"
    analytics.query(f"CREATE DATABASE IF NOT EXISTS {pa}", user=cube, password=pw)
    analytics.query(f"CREATE TABLE {pa}.r ENGINE = MergeTree ORDER BY tenant_id AS SELECT tenant_id, count() AS n "
                    f"FROM {db}.fact_task GROUP BY tenant_id", user=cube, password=pw, SQL_tenant_id=TENANT_A)
    assert analytics.rows(f"SELECT DISTINCT toString(tenant_id) AS t FROM {pa}.r", user=cube, password=pw) in (
        [], [{"t": TENANT_A}])
    analytics.query(f"DROP TABLE {pa}.r", user=cube, password=pw)
    for sql in (f"CREATE DATABASE {db}_khac", f"CREATE TABLE {db}.x (a UInt8) ENGINE = Memory",
                f"INSERT INTO {db}.dim_period (tenant_id) VALUES ('{TENANT_A}')", f"DROP TABLE {db}.fact_task"):
        with pytest.raises(RuntimeError, match=r"ACCESS_DENIED|Not enough privileges"):
            analytics.query(sql, user=cube, password=pw, SQL_tenant_id=TENANT_A)

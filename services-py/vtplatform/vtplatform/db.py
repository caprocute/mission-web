"""Transaction-scoped PostgreSQL tenant context (`SELECT set_config('app.tenant_id', $1, true)`)
safe for PgBouncer transaction pooling (TL-03 L706-734)."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
import re
from typing import Any, AsyncIterator, Dict, List, Optional

from .tenant import must_get_tenant_id, normalize_uuid, tenant_context


SET_LOCAL_TENANT_SQL = "SELECT set_config('app.tenant_id', $1, true)"

_SESSION_TENANT_SET_RE = re.compile(
    r"(?is)\bset\s+(?:session\s+)?app\.tenant_id\b|\bset_config\s*\(\s*'app\.tenant_id'\s*,[^,]+,\s*false\s*\)"
)


class SessionTenantForbiddenError(RuntimeError):
    """Raised when code attempts to set app.tenant_id at session scope under PgBouncer transaction mode."""


class RLSViolationError(PermissionError):
    """Raised when a row operation violates PostgreSQL FORCE ROW LEVEL SECURITY on tenant_id."""


class TransactionClosedError(RuntimeError):
    """Raised when an operation is invoked on a committed or rolled-back transaction."""


def validate_sql_no_session_tenant_set(sql: str) -> None:
    if _SESSION_TENANT_SET_RE.search(sql or ""):
        raise SessionTenantForbiddenError(
            "db: setting app.tenant_id at session scope is forbidden with PgBouncer transaction pooling"
        )


class SafeAsyncTx:
    """Wraps an async transaction connection, blocking session-scope SET app.tenant_id."""

    def __init__(self, conn: Any, tenant_id: str) -> None:
        self._conn = conn
        self.tenant_id = tenant_id

    async def execute(self, sql: str, *args: Any) -> str:
        validate_sql_no_session_tenant_set(sql)
        return await self._conn.execute(sql, *args)

    async def fetchval(self, sql: str, *args: Any) -> Any:
        validate_sql_no_session_tenant_set(sql)
        return await self._conn.fetchval(sql, *args)

    async def fetch(self, sql: str, *args: Any) -> List[Dict[str, Any]]:
        validate_sql_no_session_tenant_set(sql)
        return await self._conn.fetch(sql, *args)


@asynccontextmanager
async def in_tx(pool: Any) -> AsyncIterator[SafeAsyncTx]:
    """Opens a transaction from pool and binds `app.tenant_id` locally via `SELECT set_config('app.tenant_id', $1, true)`.

    There is intentionally NO API to set `app.tenant_id` at session scope.
    """
    tenant_id = must_get_tenant_id()
    async with pool.acquire() as raw_conn:
        tx_cm = raw_conn.transaction()
        await tx_cm.__aenter__()
        try:
            await raw_conn.execute(SET_LOCAL_TENANT_SQL, tenant_id)
            safe_tx = SafeAsyncTx(raw_conn, tenant_id)
            yield safe_tx
        except BaseException as exc:
            await tx_cm.__aexit__(type(exc), exc, exc.__traceback__)
            raise
        else:
            await tx_cm.__aexit__(None, None, None)


@asynccontextmanager
async def in_tenant_tx(pool: Any, tenant_id: str) -> AsyncIterator[SafeAsyncTx]:
    """Binds `tenant_id` in contextvars and opens a transaction-scoped RLS session via `in_tx`."""
    norm = normalize_uuid(tenant_id)
    with tenant_context(norm):
        async with in_tx(pool) as tx:
            yield tx


def parse_rows_affected(command_tag: str) -> int:
    parts = (command_tag or "").strip().split()
    if parts and parts[-1].isdigit():
        return int(parts[-1])
    return 0


# =============================================================================
# Simulated PgBouncer Transaction-Mode Pool + PostgreSQL FORCE RLS Engine
# =============================================================================


@dataclass(frozen=True)
class RLSItem:
    id: str
    tenant_id: str
    title: str


@dataclass(frozen=True)
class InboxProcessedRow:
    schema: str
    event_id: str
    consumer: str
    processed_at: datetime
    tenant_id: str
    source_topic: str
    kafka_partition: int
    kafka_offset: int


@dataclass(frozen=True)
class InboxVersionRow:
    schema: str
    consumer: str
    aggregate_type: str
    aggregate_id: str
    tenant_id: str
    last_version: int
    last_event_id: str
    updated_at: datetime


@dataclass
class _StorageState:
    items: List[RLSItem] = field(default_factory=list)
    inbox: Dict[str, InboxProcessedRow] = field(default_factory=dict)
    inbox_versions: Dict[str, InboxVersionRow] = field(default_factory=dict)

    def clone(self) -> "_StorageState":
        return _StorageState(
            items=list(self.items),
            inbox=dict(self.inbox),
            inbox_versions=dict(self.inbox_versions),
        )


@dataclass
class _PhysicalConn:
    conn_id: int
    session_tenant: str = ""


class SimulatedAsyncPool:
    """Simulates a PostgreSQL 17 database behind PgBouncer in `pool_mode = transaction`
    (`default_pool_size = 1` by default) with FORCE ROW LEVEL SECURITY on all tables."""

    def __init__(self, pool_size: int = 1) -> None:
        size = max(1, int(pool_size))
        self._queue: asyncio.Queue[_PhysicalConn] = asyncio.Queue(maxsize=size)
        for i in range(size):
            self._queue.put_nowait(_PhysicalConn(conn_id=i + 1))
        self._state = _StorageState()

    @asynccontextmanager
    async def acquire(self) -> AsyncIterator["_SimulatedConn"]:
        phys = await self._queue.get()
        conn = _SimulatedConn(self, phys)
        try:
            yield conn
        finally:
            self._queue.put_nowait(phys)

    def inbox_processed_rows(self) -> List[InboxProcessedRow]:
        return list(self._state.inbox.values())

    def inbox_version_rows(self) -> List[InboxVersionRow]:
        return list(self._state.inbox_versions.values())


class _SimulatedTxContext:
    def __init__(self, conn: "_SimulatedConn") -> None:
        self._conn = conn

    async def __aenter__(self) -> "_SimulatedTxContext":
        self._conn._begin_tx()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> bool:
        if exc_type is not None:
            self._conn._rollback_tx()
        else:
            self._conn._commit_tx()
        return False


class _SimulatedConn:
    def __init__(self, pool: SimulatedAsyncPool, phys: _PhysicalConn) -> None:
        self._pool = pool
        self._phys = phys
        self._in_tx = False
        self._local_tenant = ""
        self._working: Optional[_StorageState] = None

    def transaction(self) -> _SimulatedTxContext:
        return _SimulatedTxContext(self)

    def _begin_tx(self) -> None:
        self._in_tx = True
        self._local_tenant = ""
        self._working = self._pool._state.clone()

    def _commit_tx(self) -> None:
        if not self._in_tx or self._working is None:
            raise TransactionClosedError("db: transaction is not active")
        self._pool._state = self._working
        self._working = None
        self._local_tenant = ""
        self._in_tx = False

    def _rollback_tx(self) -> None:
        self._working = None
        self._local_tenant = ""
        self._in_tx = False

    def _effective_tenant(self) -> str:
        if self._local_tenant:
            return self._local_tenant
        return self._phys.session_tenant

    def _active_state(self) -> _StorageState:
        if self._in_tx and self._working is not None:
            return self._working
        return self._pool._state

    async def execute(self, sql: str, *args: Any) -> str:
        norm = " ".join((sql or "").strip().lower().split())

        if "set_config('app.tenant_id'" in norm:
            if not args:
                raise ValueError("db: missing tenant_id argument to set_config")
            tid = str(args[0])
            is_local = "true" in norm
            if len(args) >= 2 and isinstance(args[1], bool):
                is_local = args[1]
            if is_local:
                self._local_tenant = tid
            else:
                self._phys.session_tenant = tid
            return "SELECT 1"

        state = self._active_state()
        eff_tenant = self._effective_tenant()

        if norm.startswith("insert into") and "rls_items" in norm:
            if len(args) != 3:
                raise ValueError("db: expected 3 args for rls_items insert")
            item = RLSItem(id=str(args[0]), tenant_id=str(args[1]), title=str(args[2]))
            if not eff_tenant or item.tenant_id != eff_tenant:
                raise RLSViolationError("db: new row violates row-level security policy for table rls_items")
            state.items.append(item)
            return "INSERT 0 1"

        if norm.startswith("insert into") and "inbox_processed" in norm:
            if len(args) != 7:
                raise ValueError(f"db: expected 7 args for inbox_processed insert, got {len(args)}")
            event_id = str(args[0])
            consumer = str(args[1])
            processed_at = args[2] if isinstance(args[2], datetime) else datetime.now(timezone.utc)
            tid = str(args[3])
            if not eff_tenant or tid != eff_tenant:
                raise RLSViolationError("db: new row violates row-level security policy for table inbox_processed")
            source_topic = str(args[4])
            part = int(args[5])
            offset = int(args[6])
            schema = _extract_schema(norm, "inbox_processed")
            key = f"{schema}|{consumer}|{event_id}"
            if key in state.inbox:
                return "INSERT 0 0"
            state.inbox[key] = InboxProcessedRow(
                schema=schema,
                event_id=event_id,
                consumer=consumer,
                processed_at=processed_at,
                tenant_id=tid,
                source_topic=source_topic,
                kafka_partition=part,
                kafka_offset=offset,
            )
            return "INSERT 0 1"

        if norm.startswith("insert into") and "inbox_aggregate_version" in norm:
            if len(args) != 7:
                raise ValueError(f"db: expected 7 args for inbox_aggregate_version upsert, got {len(args)}")
            consumer = str(args[0])
            agg_type = str(args[1])
            agg_id = str(args[2])
            tid = str(args[3])
            if not eff_tenant or tid != eff_tenant:
                raise RLSViolationError(
                    "db: new row violates row-level security policy for table inbox_aggregate_version"
                )
            ver = int(args[4])
            event_id = str(args[5])
            updated_at = args[6] if isinstance(args[6], datetime) else datetime.now(timezone.utc)
            schema = _extract_schema(norm, "inbox_aggregate_version")
            key = f"{schema}|{consumer}|{agg_type}|{agg_id}"
            if key in state.inbox_versions:
                existing = state.inbox_versions[key]
                if existing.tenant_id != eff_tenant:
                    raise RLSViolationError("db: RLS violation on inbox_aggregate_version update")
                if ver <= existing.last_version:
                    return "INSERT 0 0"
            state.inbox_versions[key] = InboxVersionRow(
                schema=schema,
                consumer=consumer,
                aggregate_type=agg_type,
                aggregate_id=agg_id,
                tenant_id=tid,
                last_version=ver,
                last_event_id=event_id,
                updated_at=updated_at,
            )
            return "INSERT 0 1"

        return "OK 1"

    async def fetchval(self, sql: str, *args: Any) -> Any:
        norm = " ".join((sql or "").strip().lower().split())
        if "current_setting('app.tenant_id'" in norm:
            return self._effective_tenant()
        if "count(*)" in norm and "rls_items" in norm:
            eff = self._effective_tenant()
            if not eff:
                return 0
            return sum(1 for item in self._active_state().items if item.tenant_id == eff)
        raise ValueError(f"db: unsupported query in fetchval: {sql}")

    async def fetch(self, sql: str, *args: Any) -> List[Dict[str, Any]]:
        norm = " ".join((sql or "").strip().lower().split())
        if "from rls_items" in norm:
            eff = self._effective_tenant()
            if not eff:
                return []
            return [
                {"id": it.id, "tenant_id": it.tenant_id, "title": it.title}
                for it in self._active_state().items
                if it.tenant_id == eff
            ]
        raise ValueError(f"db: unsupported query in fetch: {sql}")


def _extract_schema(norm_sql: str, table: str) -> str:
    marker = f".{table}"
    idx = norm_sql.find(marker)
    if idx <= 0:
        return "public"
    prefix = norm_sql[:idx].split()
    if not prefix:
        return "public"
    return prefix[-1].strip('"')

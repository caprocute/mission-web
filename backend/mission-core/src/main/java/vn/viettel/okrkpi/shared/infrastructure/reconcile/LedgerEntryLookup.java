package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc bản ghi sổ cái của một đối tượng (SVC-09, {@code GET /api/v1/ledger/entries}). */
public interface LedgerEntryLookup {

  /** Một bản ghi sổ cái: mã sự kiện nguồn, topic và {@code payload_hash} dạng hex. */
  record LedgerRef(UUID eventId, String eventType, String payloadHash, Instant recordedAt) {}

  /** Mọi bản ghi của đối tượng có {@code recorded_at} trong {@code [from, to]}. */
  List<LedgerRef> entries(TenantId tenant, UUID aggregateId, Instant from, Instant to);
}

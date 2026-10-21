package vn.viettel.okrkpi.replica.domain;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Siêu dữ liệu của sự kiện nguồn đang áp dụng. */
public record AppliedEvent(TenantId tenant, UUID eventId, Instant occurredAt) {}

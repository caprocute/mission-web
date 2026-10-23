package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng nhật ký sự kiện của saga chốt kỳ ({@code config.period_lock_event}, SCR-0801 trường 13,
 * BR-08-10): hành động điều khiển do MOD-08 ghi hoặc bản chiếu sổ cái của saga.
 *
 * @param kind loại, ví dụ {@link LockCommand#name()} hoặc {@code BUOC_SAGA}
 * @param evt mã EVT nếu có
 * @param actorId người thực hiện, {@code null} khi do hệ thống
 * @param ledgerEntryId dòng sổ cái khi đã có
 */
public record LockEvent(
    UUID id, Instant at, String kind, String event, String evt, UUID actorId, UUID ledgerEntryId) {}

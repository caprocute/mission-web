package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Hồ sơ trong hàng đợi lỗi của bước tính điểm, lưu ở {@code config.period_lock_failure} (SCR-0801
 * trường 11). Chỉ giữ mã người và đơn vị, không giữ họ tên.
 */
public record LockFailure(
    UUID profileId,
    UUID personId,
    UUID unitId,
    UUID batchId,
    String error,
    int attempts,
    Instant lastAttemptAt) {}

package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lần chuyển trạng thái kỳ, đủ để ghi một dòng {@code config.period_status_log}.
 *
 * @param causeRef saga chốt kỳ, saga bù trừ hoặc đề nghị mở lại hậu kiểm gây ra chuyển
 * @param rowVersion {@code row_version} của kỳ sau khi chuyển
 */
public record PeriodTransition(
    UUID logId,
    UUID periodId,
    PeriodState from,
    PeriodState to,
    PeriodChangeCause cause,
    UUID causeRef,
    UUID actorId,
    Instant changedAt,
    int rowVersion) {}

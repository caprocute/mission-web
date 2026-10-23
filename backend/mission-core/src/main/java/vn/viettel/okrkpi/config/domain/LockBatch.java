package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Bản chiếu một lô tính điểm ({@code scoring.score_job}) của saga chốt kỳ, lưu ở {@code
 * config.period_lock_batch} (SCR-0801 trường 10).
 *
 * @param status {@code CHO}, {@code DANG_CHAY}, {@code XONG}, {@code LOI}
 */
public record LockBatch(
    UUID id, UUID unitId, int profileCount, String status, int attempts, Instant finishedAt) {

  public boolean finished() {
    return "XONG".equals(status);
  }
}

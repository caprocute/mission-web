package vn.viettel.okrkpi.task.domain.lifecycle;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Dự báo nguy cơ trễ hạn do SVC-15 tính (CORE-02-26, lược đồ {@code DelayRisk}): mức {@code THAP},
 * {@code VUA} hoặc {@code CAO}, số ngày làm việc cần thêm, lời giải thích và thời điểm tính.
 */
public record DelayForecast(
    String level,
    int neededWorkingDays,
    String explanation,
    Instant computedAt,
    UUID basedOnProgressId) {

  public static final Set<String> LEVELS = Set.of("THAP", "VUA", "CAO");
  public static final int MAX_EXPLANATION = 1000;

  /** Trạng thái không còn dự báo: dự báo bị xoá khi chuyển vào và không nhận dự báo mới. */
  public static final Set<TaskStatus> CLOSED =
      Set.of(TaskStatus.HOAN_THANH, TaskStatus.HUY, TaskStatus.TAM_DUNG);

  public DelayForecast {
    Objects.requireNonNull(computedAt, "computedAt");
    if (level == null || !LEVELS.contains(level)) {
      throw new IllegalArgumentException("level");
    }
    if (neededWorkingDays < 0) {
      throw new IllegalArgumentException("neededWorkingDays");
    }
    if (explanation == null || explanation.isBlank() || explanation.length() > MAX_EXPLANATION) {
      throw new IllegalArgumentException("explanation");
    }
  }

  /** Nhiệm vụ ở trạng thái này còn nhận dự báo. */
  public static boolean accepts(TaskStatus status) {
    return !CLOSED.contains(status);
  }

  /** Kết quả này không cũ hơn kết quả đang lưu (tính lúc {@code storedAt}, có thể null). */
  public boolean newerThan(Instant storedAt) {
    return storedAt == null || !computedAt.isBefore(storedAt);
  }
}

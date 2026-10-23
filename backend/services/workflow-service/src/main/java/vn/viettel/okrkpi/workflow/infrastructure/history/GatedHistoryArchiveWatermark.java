package vn.viettel.okrkpi.workflow.infrastructure.history;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.workflow.application.history.port.HistoryArchiveWatermark;

/**
 * Cổng mốc chép lịch sử khi kho phân tích SVC-15 chưa nối (DV-15-01 chưa công bố mốc độ mới): chế
 * độ {@code CHUA_NOI} (mặc định) trả rỗng nên job dọn lịch sử không xoá gì; {@code GIA_LAP} (chỉ
 * dev) coi mọi dòng đã chép tới thời điểm hiện tại. Khi SVC-15 công bố mốc (bảng {@code
 * data_freshness}), thay bằng adapter đọc mốc đó (wiki trang 14, DV-06-11).
 */
public class GatedHistoryArchiveWatermark implements HistoryArchiveWatermark {

  /** Chế độ của cổng. */
  public enum Mode {
    CHUA_NOI,
    GIA_LAP
  }

  private final Mode mode;
  private final Clock clock;

  public GatedHistoryArchiveWatermark(Mode mode, Clock clock) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<Instant> archivedUpTo() {
    return mode == Mode.GIA_LAP ? Optional.of(clock.instant()) : Optional.empty();
  }

  public Mode mode() {
    return mode;
  }
}

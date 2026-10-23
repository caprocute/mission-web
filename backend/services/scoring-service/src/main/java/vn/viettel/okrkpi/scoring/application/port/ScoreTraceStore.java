package vn.viettel.okrkpi.scoring.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;

/** Đọc vết tính đã ghi ({@code scoring.score_result}, {@code scoring.score_trace}). */
public interface ScoreTraceStore {

  /**
   * Kết quả {@code OK} mới nhất của hồ sơ trong tenant hiện hành kèm các bước theo {@code step_no}.
   *
   * @param flow {@code DANG}, {@code NHA_NUOC} hoặc {@code null} cho mọi luồng
   */
  Optional<ScoreTrace> latest(UUID profileId, String flow);
}

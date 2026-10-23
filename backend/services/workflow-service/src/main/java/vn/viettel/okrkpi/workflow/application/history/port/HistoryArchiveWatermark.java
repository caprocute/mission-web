package vn.viettel.okrkpi.workflow.application.history.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Mốc đã chép lịch sử tiến trình sang kho phân tích (DV-06-11, TL-01 G2 "Lịch sử {@code ACT_HI_*}
 * được Debezium đọc sang ClickHouse trước khi dọn"): mọi dòng {@code ACT_HI_*} ghi trước mốc này đã
 * có trong {@code analytics_<cell>}. Job dọn lịch sử chỉ xoá tiến trình kết thúc trước mốc.
 */
public interface HistoryArchiveWatermark {

  /** Rỗng khi chưa biết mốc (chưa nối kho phân tích hoặc kho không trả lời): không dọn gì. */
  Optional<Instant> archivedUpTo();
}

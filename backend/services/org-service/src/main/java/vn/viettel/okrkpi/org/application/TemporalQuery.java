package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Câu hỏi hai trục: ngày trên trục hiệu lực và thời điểm trên trục ghi nhận. {@code recordedAsOf == null} nghĩa là
 * "như hiện biết" (dùng chỉ mục một phần {@code recorded_to = 'infinity'}).
 */
public record TemporalQuery(LocalDate asOf, OffsetDateTime recordedAsOf) {

  public boolean currentKnowledge() {
    return recordedAsOf == null;
  }
}

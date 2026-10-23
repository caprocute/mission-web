package vn.viettel.okrkpi.oversight.domain.alert;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Một tín hiệu EVT-20 ({@code DauHieuBatThuong}) đã đọc từ phong bì: mã sự kiện nguồn, thời điểm và
 * chuỗi bằng chứng đã tách thành danh sách mã.
 */
public record AlertSignal(
    UUID sourceEventId,
    Instant detectedAt,
    String alertKey,
    String signalCode,
    String severity,
    String subjectType,
    UUID subjectId,
    List<String> evidence,
    String modelVersion) {

  public AlertSignal {
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(detectedAt, "detectedAt");
    Objects.requireNonNull(subjectId, "subjectId");
    evidence =
        evidence == null
            ? List.of()
            : List.copyOf(evidence.stream().filter(Objects::nonNull).toList());
  }
}

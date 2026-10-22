package vn.viettel.okrkpi.dossier.domain.meeting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diễn biến sau khi họp của hội nghị kiểm điểm (CORE-06-04, QT-11 bước 7–9): tác vụ sinh biên bản ở
 * SVC-11, khoá đối tượng biên bản, việc chủ trì kết thúc hội nghị, hạn ký biên bản và các chữ ký đã
 * nhận (EVT-18). TL-03 chỉ có cột {@code minutes_object_key}, {@code minutes_sha256}, {@code
 * sign_request_id}; phần còn lại đi cùng {@code participants_json} (suy luận, wiki trang 14).
 */
public record Proceedings(
    UUID minutesJobId,
    String minutesObjectKey,
    Instant closedAt,
    UUID closedBy,
    LocalDate signDueDate,
    List<Signature> signatures) {

  public static final Proceedings NONE = new Proceedings(null, null, null, null, null, List.of());

  /** Một chữ ký biên bản đã hoàn tất. */
  public record Signature(UUID personId, UUID signRequestId, Instant signedAt) {

    public Signature {
      Objects.requireNonNull(personId, "personId");
    }
  }

  public Proceedings {
    signatures = signatures == null ? List.of() : List.copyOf(signatures);
  }

  /** Chủ trì đã kết thúc hội nghị. */
  public boolean closed() {
    return closedAt != null;
  }

  public boolean signedBy(UUID person) {
    return signatures.stream().anyMatch(s -> s.personId().equals(person));
  }

  public Proceedings withMinutesJob(UUID jobId) {
    return new Proceedings(jobId, minutesObjectKey, closedAt, closedBy, signDueDate, signatures);
  }

  public Proceedings withMinutesObject(String objectKey) {
    return new Proceedings(minutesJobId, objectKey, closedAt, closedBy, signDueDate, signatures);
  }

  public Proceedings close(Instant at, UUID by, LocalDate dueDate) {
    return new Proceedings(minutesJobId, minutesObjectKey, at, by, dueDate, signatures);
  }

  public Proceedings sign(Signature signature) {
    List<Signature> next = new ArrayList<>(signatures);
    next.add(signature);
    return new Proceedings(minutesJobId, minutesObjectKey, closedAt, closedBy, signDueDate, next);
  }
}

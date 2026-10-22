package vn.viettel.okrkpi.oversight.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexWriter;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Consumer {@code oversight.audit-indexer} (CORE-07-12): đổi một sự kiện {@code sys.audit.v1} thành
 * tài liệu chỉ mục kiểm toán, mỗi đối tượng C2 một tài liệu. Chỉ chép siêu dữ liệu đã liệt kê ở
 * {@link AuditDocument}; sự kiện kiểm toán vốn không mang nội dung dữ liệu được xem (CORE-00-05).
 */
public class AuditIndexingService {

  private final AuditIndexWriter writer;

  public AuditIndexingService(AuditIndexWriter writer) {
    this.writer = Objects.requireNonNull(writer, "writer");
  }

  /** Ghi chỉ mục cho một sự kiện; gọi lại với cùng sự kiện không tạo tài liệu mới. */
  public List<AuditDocument> index(AuditRecord event) {
    List<AuditDocument> docs = documents(event);
    writer.index(docs);
    return docs;
  }

  static List<AuditDocument> documents(AuditRecord e) {
    List<AuditDocument> docs = new ArrayList<>();
    if (e.targets().isEmpty()) {
      docs.add(doc(e, 0, e.objectType(), e.objectId(), null, e.purpose(), null));
      return docs;
    }
    for (int i = 0; i < e.targets().size(); i++) {
      Target t = e.targets().get(i);
      docs.add(
          doc(
              e,
              i,
              t.objectType(),
              t.objectId(),
              t.column(),
              e.purpose() != null ? e.purpose() : t.dekPurpose(),
              t.self()));
    }
    return docs;
  }

  private static AuditDocument doc(
      AuditRecord e,
      int index,
      String objectType,
      String objectId,
      String column,
      String purpose,
      Boolean self) {
    return new AuditDocument(
        e.eventId() + ":" + index,
        e.eventId(),
        e.tenantId(),
        e.occurredAt(),
        e.actorId(),
        e.actorAssignmentId(),
        e.actorRole(),
        e.action(),
        e.outcome(),
        objectType,
        objectId,
        column,
        purpose,
        self,
        e.system(),
        e.traceparent());
  }

  /** Sự kiện kiểm toán đã đọc từ phong bì và thân Avro. */
  public record AuditRecord(
      UUID eventId,
      TenantId tenantId,
      Instant occurredAt,
      UUID actorId,
      UUID actorAssignmentId,
      String actorRole,
      String action,
      String outcome,
      String objectType,
      String objectId,
      String purpose,
      boolean system,
      String traceparent,
      List<Target> targets) {

    public AuditRecord {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(action, "action");
      targets = targets == null ? List.of() : List.copyOf(targets);
    }
  }

  /** Một đối tượng C2 trong sự kiện ({@code AuditTarget} của {@code AuditEvent.avsc}). */
  public record Target(
      String objectType, String objectId, String column, String dekPurpose, Boolean self) {}
}

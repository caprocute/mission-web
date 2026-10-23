package vn.viettel.okrkpi.integ.application.rating;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phiên bản đã gửi ra theo thực thể của một kết nối ({@code integ.outbound_subject}). */
public interface SubjectChains {

  record Subject(UUID id, UUID currentEventId, UUID previousEventId, int aggregateVersion) {}

  /** Đọc và khoá dòng của thực thể. */
  Optional<Subject> lock(TenantId tenant, UUID connectorId, String subjectKey);

  void insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      String subjectKey,
      UUID eventId,
      int aggregateVersion);

  void advance(TenantId tenant, UUID id, UUID eventId, UUID previousEventId, int aggregateVersion);
}

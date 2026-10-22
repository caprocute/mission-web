package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ngữ cảnh làm việc lưu ở Keycloak (DV-02-02): thuộc tính người dùng do org-service ghi qua Admin API, protocol mapper
 * {@code mission-context} đọc để phát claim. Người dùng không bao giờ tự ghi được các thuộc tính này.
 */
public interface IdentityContextPort {

  /** Phân công đã chọn lần trước, nếu có. */
  Optional<UUID> lastAssignment(UUID personId);

  void write(UUID personId, StoredContext context);

  /** Ngữ cảnh ghi vào Keycloak; {@code validTo} rỗng là vô thời hạn. */
  record StoredContext(UUID assignmentId, UUID unitId, List<String> roles, List<String> flows, LocalDate validTo) {
  }
}

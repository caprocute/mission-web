package vn.viettel.okrkpi.workflow.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nhóm ứng viên của user task theo khuôn {@code <tenantId>:<role>:<orgUnitId>} (TL-01 chương 8,
 * {@code candidateGroups} của mẫu BPMN): vai trò baseline cùng đơn vị cụ thể trong một tenant.
 *
 * @param tenantId tenant
 * @param role mã vai trò baseline (ví dụ {@code leader}), tối đa 32 ký tự
 * @param unitId đơn vị ứng viên
 */
public record CandidateGroup(UUID tenantId, String role, UUID unitId) {

  private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]{0,31}");

  public CandidateGroup {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(unitId, "unitId");
    if (!ROLE.matcher(role).matches()) {
      throw new IllegalArgumentException("Mã vai trò ứng viên không hợp lệ");
    }
  }

  /** Đọc mã nhóm Flowable; rỗng khi không đúng khuôn. */
  public static Optional<CandidateGroup> parse(String groupId) {
    if (groupId == null) {
      return Optional.empty();
    }
    String[] parts = groupId.split(":", -1);
    if (parts.length != 3 || !ROLE.matcher(parts[1]).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new CandidateGroup(UUID.fromString(parts[0]), parts[1], UUID.fromString(parts[2])));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Mã nhóm dùng trong {@code candidateGroups} của Flowable. */
  public String groupId() {
    return tenantId + ":" + role + ":" + unitId;
  }
}

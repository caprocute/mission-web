package vn.viettel.okrkpi.workflow.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Người gọi đã xác thực: tenant và người dùng lấy từ token đã kiểm, vai trò và đơn vị đang chọn
 * trong ngữ cảnh làm việc. Máy chủ chỉ dùng các giá trị này để lọc hộp việc và đối chiếu nhóm ứng
 * viên; quyền chức năng do OPA quyết định.
 *
 * @param tenantId tenant
 * @param actorId người dùng ({@code sub})
 * @param roles mã vai trò baseline trong token
 * @param unitId đơn vị đang chọn, có thể {@code null}
 */
public record Approver(UUID tenantId, UUID actorId, Set<String> roles, UUID unitId) {

  public Approver {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(actorId, "actorId");
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** Đúng khi nhóm ứng viên thuộc cùng tenant, người gọi có vai trò và đang chọn đúng đơn vị. */
  public boolean belongsTo(CandidateGroup group) {
    return group.tenantId().equals(tenantId)
        && roles.contains(group.role())
        && group.unitId().equals(unitId);
  }
}

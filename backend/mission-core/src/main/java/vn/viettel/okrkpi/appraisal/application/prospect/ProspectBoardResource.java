package vn.viettel.okrkpi.appraisal.application.prospect;

import java.util.UUID;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;

/**
 * Tài nguyên danh sách phân nhóm gửi OPA cho SCR-1002 (phạm vi {@code Đv}, BR-10-10): đơn vị của
 * danh sách và kỳ năm.
 */
public record ProspectBoardResource(UUID tenantId, UUID periodId, UUID orgUnitId)
    implements AuthzResourceProvider {

  public static final String TYPE = "prospect_board";

  @Override
  public AuthzResource toAuthzResource() {
    return AuthzResource.builder()
        .type(TYPE)
        .id(periodId + ":" + orgUnitId)
        .tenantId(tenantId.toString())
        .ownerUnitId(orgUnitId.toString())
        .build();
  }
}

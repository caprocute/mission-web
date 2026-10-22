package vn.viettel.okrkpi.workflow.domain.approval;

import java.util.Objects;
import java.util.UUID;

/**
 * Bản đọc một đơn vị từ {@code org_replica.org_unit_v} tại một ngày hiệu lực (TL-03 "Schema
 * org_replica").
 *
 * @param id mã đơn vị
 * @param unitType loại đơn vị ({@code TO_CHUC_DANG}, {@code CHI_BO}, {@code CO_QUAN}…)
 * @param partyParentId cấp trên trên cây Đảng, có thể {@code null}
 * @param adminParentId cấp trên trên cây chuyên môn, có thể {@code null}
 */
public record OrgUnit(UUID id, String unitType, UUID partyParentId, UUID adminParentId) {

  /** Loại đơn vị là tổ chức Đảng có cấp uỷ (TL-03 {@code org.org_unit.unit_type}). */
  public static final String PARTY_ORGANIZATION = "TO_CHUC_DANG";

  public OrgUnit {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(unitType, "unitType");
  }

  /** Cấp trên trực tiếp trên cây của luồng: Đảng dùng cây Đảng, Nhà nước dùng cây chuyên môn. */
  public UUID parentOn(ApprovalFlow flow) {
    return flow == ApprovalFlow.DANG ? partyParentId : adminParentId;
  }

  /** Đúng khi đơn vị là tổ chức Đảng có cấp uỷ. */
  public boolean isPartyOrganization() {
    return PARTY_ORGANIZATION.equals(unitType);
  }
}

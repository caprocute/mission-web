package vn.viettel.okrkpi.appraisal.application.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;

/**
 * Tài nguyên hồ sơ đánh giá gửi OPA cho các chức năng của SCR-0902, SCR-0903: đơn vị của hồ sơ
 * (phạm vi {@code Cd}, {@code Đv}), luồng và năm vai đã tham gia của BR-09-14 ({@code appraisee},
 * {@code acceptor}, {@code appraiser}, {@code decider}). Ràng buộc 15.1 và 15.2 của ma trận chọn
 * nhóm nào áp cho từng chức năng.
 */
public record ReviewParticipants(
    UUID tenantId,
    UUID profileId,
    UUID orgUnitId,
    String flow,
    UUID appraisee,
    Set<UUID> acceptors,
    UUID appraiser,
    UUID decider)
    implements AuthzResourceProvider {

  public static final String TYPE = "appraisal_profile";

  public ReviewParticipants {
    acceptors = acceptors == null ? Set.of() : Set.copyOf(acceptors);
  }

  /**
   * BR-09-14 ở máy chủ, không phụ thuộc OPA: người thẩm định, trả lại khác người được đánh giá,
   * người đã nghiệm thu minh chứng của hồ sơ và người quyết định (ràng buộc 15.1).
   */
  public boolean mayAppraise(UUID actor) {
    return !actor.equals(appraisee) && !acceptors.contains(actor) && !actor.equals(decider);
  }

  /** Người đề xuất khác người được đánh giá, người thẩm định và người quyết định (15.2). */
  public boolean mayPropose(UUID actor) {
    return !actor.equals(appraisee) && !actor.equals(appraiser) && !actor.equals(decider);
  }

  @Override
  public AuthzResource toAuthzResource() {
    Map<String, List<String>> groups = new LinkedHashMap<>();
    groups.put("appraisee", ids(appraisee));
    List<String> accepted = new ArrayList<>();
    acceptors.forEach(a -> accepted.add(a.toString()));
    groups.put("acceptor", accepted);
    groups.put("appraiser", ids(appraiser));
    groups.put("decider", ids(decider));
    return AuthzResource.builder()
        .type(TYPE)
        .id(profileId.toString())
        .tenantId(tenantId.toString())
        .ownerUnitId(orgUnitId.toString())
        .ownerIds(ids(appraisee))
        .flow(flow)
        .participants(groups)
        .build();
  }

  private static List<String> ids(UUID id) {
    return id == null ? List.of() : List.of(id.toString());
  }
}

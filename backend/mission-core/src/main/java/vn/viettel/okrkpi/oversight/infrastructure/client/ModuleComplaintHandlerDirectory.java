package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người xử lý khiếu nại từ API công khai của MOD-01 (vai trò, cây đơn vị), MOD-04 (người đề xuất,
 * thẩm định, quyết định) và MOD-03 (người nghiệm thu minh chứng của người khiếu nại trong kỳ).
 * Thiếu bean cây đơn vị thì không nâng cấp trên; thiếu bean minh chứng thì nhóm {@code acceptor}
 * rỗng.
 */
public class ModuleComplaintHandlerDirectory implements ComplaintHandlerDirectory {

  static final String ROLE = "appraiser";

  private final RoleHolderApi roles;
  private final AccessDirectory units;
  private final ComplaintProfilesApi profiles;
  private final EvidenceQueryApi evidence;

  public ModuleComplaintHandlerDirectory(
      RoleHolderApi roles,
      AccessDirectory units,
      ComplaintProfilesApi profiles,
      EvidenceQueryApi evidence) {
    this.roles = Objects.requireNonNull(roles, "roles");
    this.units = units;
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.evidence = evidence;
  }

  @Override
  public List<UUID> appraisersAt(TenantId tenant, UUID orgUnitId, Instant at) {
    return roles.holders(tenant, ROLE, orgUnitId, at).stream()
        .map(RoleHolderApi.Holder::personId)
        .distinct()
        .toList();
  }

  @Override
  public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
    return units == null ? List.of() : units.ancestorsOf(tenant, orgUnitId);
  }

  @Override
  public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
    return units == null ? List.of() : units.subunitsOf(tenant, orgUnitId);
  }

  @Override
  public Map<String, Set<UUID>> participants(TenantId tenant, Complaint c) {
    ComplaintProfilesApi.Participants p = profiles.participants(tenant, c.profileId());
    Map<String, Set<UUID>> out = new LinkedHashMap<>();
    out.put(
        "acceptor",
        evidence == null
            ? Set.of()
            : Set.copyOf(evidence.acceptors(tenant, c.periodId(), c.complainantId())));
    out.put("appraiser", p.appraisers());
    out.put("proposer", p.proposers());
    out.put("decider", p.deciders());
    return out;
  }
}

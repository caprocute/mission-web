package vn.viettel.okrkpi.product.infrastructure.client;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.product.application.evidence.port.ReviewerSupervisors;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cấp trên của người nghiệm thu trên cây chuyên môn của {@code org_replica} (BR-07-12): trưởng đơn
 * vị công tác chính; người nghiệm thu là trưởng đơn vị thì lấy trưởng đơn vị cấp trên.
 */
public class ReplicaReviewerSupervisors implements ReviewerSupervisors {

  private final OrgDirectoryApi org;

  public ReplicaReviewerSupervisors(OrgDirectoryApi org) {
    this.org = Objects.requireNonNull(org, "org");
  }

  @Override
  public List<UUID> supervisorsOf(TenantId tenant, UUID personId, LocalDate on) {
    List<AssignmentView> assignments = org.assignmentsOf(tenant, personId, on);
    if (assignments.isEmpty()) {
      return List.of();
    }
    UUID unit = assignments.get(0).orgUnitId();
    Set<UUID> heads = new LinkedHashSet<>(org.headsOf(tenant, unit, on));
    heads.remove(personId);
    if (heads.isEmpty()) {
      Optional<UUID> parent = org.parentOf(tenant, OrgTree.CHUYEN_MON, unit);
      parent.ifPresent(p -> heads.addAll(org.headsOf(tenant, p, on)));
      heads.remove(personId);
    }
    return List.copyOf(heads);
  }
}

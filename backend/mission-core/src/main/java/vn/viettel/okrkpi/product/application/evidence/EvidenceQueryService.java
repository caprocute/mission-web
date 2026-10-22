package vn.viettel.okrkpi.product.application.evidence;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cài đặt {@link EvidenceQueryApi} trên schema product. */
public class EvidenceQueryService implements EvidenceQueryApi {

  private final EvidenceSubmissionRepository repository;

  public EvidenceQueryService(EvidenceSubmissionRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  @Transactional(value = EvidenceLifecycleService.TX, readOnly = true)
  public Set<UUID> confirmedEvidence(TenantId tenant, UUID personId, Collection<UUID> evidenceIds) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(personId, "personId");
    Set<UUID> ids = new LinkedHashSet<>(evidenceIds);
    ids.remove(null);
    if (ids.size() > MAX_IDS) {
      throw new IllegalArgumentException("Tối đa " + MAX_IDS + " mã minh chứng một lần hỏi");
    }
    return ids.isEmpty() ? Set.of() : repository.confirmedOf(tenant, personId, ids);
  }
}

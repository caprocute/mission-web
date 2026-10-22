package vn.viettel.okrkpi.appraisal.infrastructure.evidence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.ConfirmedEvidence;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Minh chứng {@code DA_XAC_NHAN} cho điều kiện HTXSNV, đọc qua cổng {@code product :: api} của
 * MOD-03; chia lần hỏi theo {@link EvidenceQueryApi#MAX_IDS} mã.
 */
public class ProductConfirmedEvidence implements ConfirmedEvidence {

  private final EvidenceQueryApi api;

  public ProductConfirmedEvidence(EvidenceQueryApi api) {
    this.api = api;
  }

  @Override
  public Set<UUID> confirmed(TenantId tenant, UUID personId, Collection<UUID> evidenceIds) {
    List<UUID> ids = List.copyOf(new HashSet<>(evidenceIds));
    Set<UUID> confirmed = new HashSet<>();
    for (int from = 0; from < ids.size(); from += EvidenceQueryApi.MAX_IDS) {
      List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + EvidenceQueryApi.MAX_IDS));
      confirmed.addAll(api.confirmedEvidence(tenant, personId, chunk));
    }
    return confirmed;
  }
}

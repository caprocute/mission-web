package vn.viettel.okrkpi.appraisal.infrastructure.evidence;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.port.EvidenceAcceptors;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nhóm {@code acceptor} của BR-09-14, đọc qua cổng {@code product :: api} của MOD-03. */
public class ProductEvidenceAcceptors implements EvidenceAcceptors {

  private final EvidenceQueryApi api;

  public ProductEvidenceAcceptors(EvidenceQueryApi api) {
    this.api = Objects.requireNonNull(api, "api");
  }

  @Override
  public Set<UUID> of(TenantId tenant, UUID periodId, UUID personId) {
    return api.acceptors(tenant, periodId, personId);
  }
}

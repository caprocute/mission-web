package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.oversight.api.AnomalyAlertQueryApi;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link AnomalyAlertQueryApi} đọc trong giao dịch chỉ đọc của schema oversight (RLS theo tenant).
 */
public class TransactionalAnomalyAlertQueries implements AnomalyAlertQueryApi {

  private final AnomalyAlertRepository alerts;
  private final TransactionTemplate tx;

  public TransactionalAnomalyAlertQueries(
      AnomalyAlertRepository alerts, PlatformTransactionManager tm) {
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.tx = new TransactionTemplate(tm);
    this.tx.setReadOnly(true);
  }

  @Override
  public Set<UUID> blockedEvidence(TenantId tenant, Collection<UUID> evidenceIds) {
    if (evidenceIds == null || evidenceIds.isEmpty()) {
      return Set.of();
    }
    return inTenant(
        tenant, () -> tx.execute(s -> alerts.blockedEvidence(tenant, List.copyOf(evidenceIds))));
  }

  @Override
  public List<OpenAlert> openDuplicateEvidenceAlerts(TenantId tenant, PeriodId period, int limit) {
    return inTenant(
        tenant,
        () ->
            tx.execute(
                s ->
                    alerts
                        .openAlerts(
                            tenant,
                            period.value(),
                            AlertRules.DUPLICATE_EVIDENCE,
                            Math.max(1, Math.min(limit, 1000)))
                        .stream()
                        .map(
                            a ->
                                new OpenAlert(
                                    a.id(),
                                    a.severity(),
                                    a.subjectType(),
                                    a.subjectId(),
                                    a.status(),
                                    a.detectedAt(),
                                    AlertRules.evidenceIds(a.evidence())))
                        .toList()));
  }

  private static <T> T inTenant(TenantId tenant, java.util.concurrent.Callable<T> work) {
    try {
      return TenantContext.call(tenant, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

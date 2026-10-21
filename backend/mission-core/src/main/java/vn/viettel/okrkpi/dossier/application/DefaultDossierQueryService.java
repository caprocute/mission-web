package vn.viettel.okrkpi.dossier.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.appraisal.api.AppraisalResultApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.dossier.api.DossierQueryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-06 · Digital Dossier} cài đặt {@link DossierQueryApi} và phụ
 * thuộc {@link AppraisalResultApi} ({@code appraisal :: api}) cùng {@link PeriodApi} ({@code config
 * :: api}).
 */
@Service
public class DefaultDossierQueryService implements DossierQueryApi {

  private final AppraisalResultApi appraisalResultApi;
  private final PeriodApi periodApi;

  public DefaultDossierQueryService(AppraisalResultApi appraisalResultApi, PeriodApi periodApi) {
    this.appraisalResultApi =
        Objects.requireNonNull(appraisalResultApi, "appraisalResultApi must not be null");
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  @Override
  public Optional<UUID> findDossierId(TenantId tenantId, PeriodId periodId, UUID personId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(personId, "personId must not be null");
    periodApi.findById(tenantId, periodId);
    appraisalResultApi.findFinalGrade(tenantId, periodId, personId);
    return Optional.empty();
  }
}

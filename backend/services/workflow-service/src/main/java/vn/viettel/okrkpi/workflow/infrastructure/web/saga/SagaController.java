package vn.viettel.okrkpi.workflow.infrastructure.web.saga;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.web.CurrentCaller;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.api.WorkflowApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.model.ApproveTenantMove202Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.model.PeriodCloseSagaStart;

/**
 * {@code POST /api/v1/workflow/sagas/period-close} (QT-08, SCR-0801): client nội bộ MOD-08 khởi
 * động saga {@code CHOT_KY}; mã tương quan là lệnh khoá kỳ, khoá độc quyền là kỳ (DV-06-05).
 */
@RestController
@ConditionalOnProperty(prefix = "okrkpi.saga", name = "enabled", matchIfMissing = true)
@RequestMapping("/api/v1")
public class SagaController implements WorkflowApi {

  private final SagaService sagas;

  public SagaController(SagaService sagas) {
    this.sagas = sagas;
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<ApproveTenantMove202Response> startPeriodCloseSaga(
      String idempotencyKey, PeriodCloseSagaStart body) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("periodId", body.getPeriodId().toString());
    parameters.put("lockOrderId", body.getLockOrderId().toString());
    List<String> units =
        body.getUnitIds() == null
            ? List.of()
            : body.getUnitIds().stream().map(UUID::toString).toList();
    parameters.put("unitIds", units);
    parameters.put("requestedBy", body.getRequestedBy().toString());
    SagaService.StartResult result =
        sagas.start(
            new SagaService.StartCommand(
                TenantContext.require().value(),
                SagaType.CHOT_KY,
                body.getLockOrderId(),
                parameters,
                CurrentCaller.actor()));
    Job job = SagaJobSource.job(result.saga());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(Jobs.location(job.id()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(toDto(job));
  }

  private static ApproveTenantMove202Response toDto(Job job) {
    ApproveTenantMove202Response dto =
        new ApproveTenantMove202Response(
            job.id(),
            job.type(),
            ApproveTenantMove202Response.StatusEnum.fromValue(job.status().name()));
    dto.setProgress(job.progress());
    dto.setMessage(job.message());
    dto.setUpdatedAt(job.updatedAt());
    return dto;
  }
}

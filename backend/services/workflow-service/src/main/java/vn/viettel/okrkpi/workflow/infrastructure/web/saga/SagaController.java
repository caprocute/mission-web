package vn.viettel.okrkpi.workflow.infrastructure.web.saga;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseControlService;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.web.CurrentCaller;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.api.WorkflowApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.model.ApproveTenantMove202Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.model.PeriodCloseSagaCommand;
import vn.viettel.okrkpi.workflow.infrastructure.web.saga.model.PeriodCloseSagaStart;

/**
 * {@code POST /api/v1/workflow/sagas/period-close} (QT-08, SCR-0801): client nội bộ MOD-08 khởi
 * động saga {@code CHOT_KY}; mã tương quan là lệnh khoá kỳ, khoá độc quyền là kỳ (DV-06-05). {@code
 * POST .../period-close/{sagaId}/commands} nhận từng dòng hộp lệnh {@code
 * config.period_lock_command} của MOD-08 (DV-06-07).
 */
@RestController
@ConditionalOnProperty(prefix = "okrkpi.saga", name = "enabled", matchIfMissing = true)
@RequestMapping("/api/v1")
public class SagaController implements WorkflowApi {

  private final SagaService sagas;
  private final ObjectProvider<PeriodCloseControlService> periodClose;

  public SagaController(SagaService sagas, ObjectProvider<PeriodCloseControlService> periodClose) {
    this.sagas = sagas;
    this.periodClose = periodClose;
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

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<ApproveTenantMove202Response> controlPeriodCloseSaga(
      UUID sagaId, String idempotencyKey, PeriodCloseSagaCommand body) {
    PeriodCloseControlService control = periodClose.getIfAvailable();
    if (control == null) {
      throw new WorkflowNotFoundException();
    }
    SagaInstance saga =
        switch (body.getCommand()) {
          case TAM_DUNG -> control.pause(sagaId);
          case TIEP_TUC -> control.resume(sagaId);
          case BU_TRU -> {
            if (body.getApprovedBy() == null) {
              throw new BusinessException(
                  "COMMON-422-VALIDATION", "Thiếu người duyệt thứ hai của đề nghị huỷ.");
            }
            yield control.cancel(sagaId, body.getRequestedBy(), body.getApprovedBy());
          }
          case CHAY_LAI_LOI -> {
            // Mã lệnh của hộp lệnh là khoá chống lặp của lô chạy lại.
            control.retryFailedProfiles(
                sagaId, body.getCommandId().toString(), body.getProfileIds());
            yield sagas.find(sagaId).orElseThrow(WorkflowNotFoundException::new);
          }
          case CHAP_NHAN_LOI ->
              control.acceptFailedProfiles(sagaId, body.getRequestedBy(), body.getReason());
        };
    Job job = SagaJobSource.job(saga);
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

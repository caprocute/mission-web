package vn.viettel.okrkpi.docgen.infrastructure.web;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.docgen.infrastructure.web.api.DossiersApi;
import vn.viettel.okrkpi.docgen.infrastructure.web.model.CreateDossierMeetingMinutes202Response;
import vn.viettel.okrkpi.docgen.infrastructure.web.model.DossierFormCode;
import vn.viettel.okrkpi.docgen.infrastructure.web.model.DossierRenderJobRequest;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code POST /api/v1/dossiers/render-jobs} ({@code createDossierRenderJob}, QT-11 bước 10): nhận
 * lệnh sinh từ người thẩm định hoặc saga ({@code serviceClient}), trả 202 kèm {@code Location:
 * /api/v1/jobs/{id}}. Gửi lại cùng {@code Idempotency-Key} trả đúng phản hồi cũ.
 */
@RestController
@RequestMapping("/api/v1")
public class DossierRenderController implements DossiersApi {

  private final RenderService service;

  public DossierRenderController(RenderService service) {
    this.service = service;
  }

  @Override
  @Idempotent(schema = "docgen")
  public ResponseEntity<CreateDossierMeetingMinutes202Response> createDossierRenderJob(
      String idempotencyKey, DossierRenderJobRequest body) {
    List<String> codes =
        body.getFormCodes() == null
            ? List.of()
            : body.getFormCodes().stream().map(DossierFormCode::getValue).toList();
    Job job =
        service.request(
            new RenderService.RequestCommand(
                TenantContext.require().value(),
                CurrentCaller.actor(),
                body.getPackageId(),
                codes,
                Ids.newUuidV7()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(Jobs.location(job.id()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(toDto(job));
  }

  static CreateDossierMeetingMinutes202Response toDto(Job job) {
    CreateDossierMeetingMinutes202Response dto =
        new CreateDossierMeetingMinutes202Response(
            job.id(),
            job.type(),
            CreateDossierMeetingMinutes202Response.StatusEnum.fromValue(job.status().name()));
    dto.setProgress(job.progress());
    dto.setMessage(job.message());
    dto.setUpdatedAt(job.updatedAt());
    return dto;
  }
}

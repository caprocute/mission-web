package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.IncidentView;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.NewIncident;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncident;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncidentCreate;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncidentPage;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tài nguyên {@code incidents} của {@code appraisals.yaml} (CORE-04-13, BS-03 DC-05, QT-09): ghi và
 * đọc sự việc đã có kết luận. Vai trò kiểm theo thao tác qua OPA; phạm vi dữ liệu là cây đơn vị
 * dưới đơn vị phân công đang chọn của người gọi (suy luận, hợp đồng ghi phạm vi {@code Cd}).
 */
@RestController
@RequestMapping("/api/v1/appraisals/incidents")
public class AppraisalIncidentController {

  static final String BASE = "/api/v1/appraisals/incidents";
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private final IncidentService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  public AppraisalIncidentController(
      IncidentService service, ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listAppraisalIncidents",
      module = "appraisals",
      classification = "C2")
  public AppraisalIncidentPage listAppraisalIncidents(
      @RequestParam(value = "person", required = false) UUID person,
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    List<IncidentView> rows =
        service.list(
            tenant,
            unitScope(),
            person,
            period,
            SelfAssessmentController.decodeCursor(cursor),
            size + 1);
    boolean more = rows.size() > size;
    List<IncidentView> page = more ? rows.subList(0, size) : rows;
    String next = more ? SelfAssessmentController.encodeCursor(page.getLast().row().id()) : null;
    return new AppraisalIncidentPage(
        page.stream().map(AppraisalIncidentController::dto).toList(), next);
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(
      operationId = "createAppraisalIncident",
      module = "appraisals",
      classification = "C2")
  public ResponseEntity<AppraisalIncident> createAppraisalIncident(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody AppraisalIncidentCreate body) {
    TenantId tenant = TenantContext.require();
    if (body.getDocumentNo() == null || body.getDocumentNo().isBlank()) {
      throw invalid("documentNo", "Nhập số văn bản kết luận.");
    }
    if (body.getConcludedOn() == null) {
      throw invalid("concludedOn", "Nhập ngày kết luận.");
    }
    if (body.getPersonId() == null) {
      throw invalid("personId", "Chọn người có sự việc.");
    }
    IncidentView created =
        service.newIncident(
            tenant,
            actor(),
            new NewIncident(
                body.getPersonId(),
                body.getPeriodId(),
                body.getIncidentType(),
                body.getConcludedOn(),
                body.getDocumentNo(),
                body.getDocumentRef(),
                body.getImpact(),
                body.getSummary()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(BASE + "/{id}")
                .buildAndExpand(created.row().id())
                .toUri())
        .eTag(ETags.of(created.row().rowVersion()))
        .body(dto(created));
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getAppraisalIncident",
      module = "appraisals",
      classification = "C2")
  public ResponseEntity<AppraisalIncident> getAppraisalIncident(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    try {
      IncidentView view = service.find(tenant, unitScope(), id);
      return ETags.ok(dto(view), view.row().rowVersion());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  static AppraisalIncident dto(IncidentView view) {
    IncidentRow r = view.row();
    AppraisalIncident dto =
        new AppraisalIncident(
            r.id(),
            r.personId(),
            r.incidentType(),
            r.concludedOn(),
            r.documentNo(),
            r.rowVersion());
    dto.setPeriodId(r.periodId());
    dto.setDocumentRef(r.documentRef());
    dto.setImpact(r.impact());
    dto.setSummary(view.summary());
    if (r.createdBy() != null) {
      dto.setCreatedBy(new Ref(r.createdBy(), ""));
    }
    if (r.createdAt() != null) {
      dto.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    }
    return dto;
  }

  /** Đơn vị phân công đang chọn của người gọi; không có thì không thấy sự việc nào. */
  private UUID unitScope() {
    String unit = subject().unitId();
    try {
      return unit == null ? null : UUID.fromString(unit);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private OpaInput.Subject subject() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return subject;
  }

  private UUID actor() {
    try {
      return UUID.fromString(subject().userId());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}

package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCase;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCaseDetectedBy;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCaseEffect;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCaseGroup;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCasePage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSpecialCaseUpdate;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.CaseView;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.GroupView;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService.Page;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository.SpecialCaseRow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Trường hợp đặc thù PL 6 của {@code dossiers.yaml} (CORE-06-05, SCR-1102 vùng 2, BR-11-05): {@code
 * listDossierSpecialCases}, {@code getDossierSpecialCase}, {@code verifyDossierSpecialCase}. Chi
 * tiết là C2 nên đọc một đối tượng và xác minh khai mức {@code C2}; danh sách không trả chi tiết.
 * Tên người trả rỗng như các API khác của mission-core.
 */
@RestController
public class DossierSpecialCaseController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private final SpecialCaseService service;
  private final DossierCallers callers;

  public DossierSpecialCaseController(
      SpecialCaseService service,
      ObjectProvider<DossierCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.callers = new DossierCallers(callers, subjects);
  }

  @GetMapping("/api/v1/dossiers/special-cases")
  @RequiresPermission(
      operationId = "listDossierSpecialCases",
      module = "dossiers",
      resourceType = "special_case")
  public ResponseEntity<DossierSpecialCasePage> listDossierSpecialCases(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "packageId", required = false) UUID packageId,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    callers.actor();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    Page page =
        service.list(
            tenant,
            callers.unit().orElse(null),
            period,
            unit,
            packageId,
            DossierRemediationController.decodeCursor(cursor),
            size + 1);
    boolean more = page.items().size() > size;
    List<CaseView> rows = more ? page.items().subList(0, size) : page.items();
    List<DossierSpecialCase> items = new ArrayList<>();
    for (CaseView v : rows) {
      items.add(dto(v));
    }
    String next =
        more
            ? DossierRemediationController.encodeCursor(rows.get(rows.size() - 1).row().id())
            : null;
    DossierSpecialCasePage body = new DossierSpecialCasePage(items, next);
    List<DossierSpecialCaseGroup> groups = new ArrayList<>();
    for (GroupView g : page.groups()) {
      DossierSpecialCaseGroup group =
          new DossierSpecialCaseGroup(g.legalRef(), g.caseCode(), (int) g.count());
      group.setHandling(g.handling());
      groups.add(group);
    }
    body.setGroups(groups);
    return ResponseEntity.ok(body);
  }

  @GetMapping("/api/v1/dossiers/special-cases/{id}")
  @RequiresPermission(
      operationId = "getDossierSpecialCase",
      module = "dossiers",
      resourceType = "special_case",
      classification = "C2")
  public ResponseEntity<DossierSpecialCase> getDossierSpecialCase(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    callers.actor();
    try {
      CaseView view = service.get(tenant, callers.unit().orElse(null), id);
      return ETags.ok(dto(view), view.row().rowVersion());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
  }

  @PatchMapping("/api/v1/dossiers/special-cases/{id}")
  @Idempotent
  @RequiresPermission(
      operationId = "verifyDossierSpecialCase",
      module = "dossiers",
      resourceType = "special_case",
      classification = "C2")
  public ResponseEntity<DossierSpecialCase> verifyDossierSpecialCase(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody DossierSpecialCaseUpdate body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    try {
      CaseView view =
          service.verify(
              tenant,
              callers.actor(),
              callers.unit().orElse(null),
              id,
              expected,
              new SpecialCaseService.Verification(
                  body.getVerified(),
                  body.getCaseCode(),
                  body.getDetail(),
                  body.getHandlingNote()));
      return ETags.ok(dto(view), view.row().rowVersion());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
  }

  static DossierSpecialCase dto(CaseView v) {
    SpecialCaseRow r = v.row();
    DossierSpecialCase dto =
        new DossierSpecialCase(
            r.id(),
            new Ref(r.personId(), ""),
            r.caseCode(),
            r.legalRef(),
            DossierSpecialCaseDetectedBy.fromValue(r.detectedBy()),
            DossierSpecialCaseEffect.fromValue(r.effect()));
    dto.setHandling(v.handling());
    dto.setEffectiveFrom(r.effectiveFrom());
    dto.setEffectiveTo(r.effectiveTo());
    dto.setDetail(v.detail());
    dto.setConfirmedBy(r.confirmedBy() == null ? null : new Ref(r.confirmedBy(), ""));
    dto.setConfirmedAt(time(r.confirmedAt()));
    return dto;
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atZone(ReviewMeetingService.ZONE).toOffsetDateTime();
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}

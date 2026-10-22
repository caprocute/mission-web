package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.oversight.model.AlertAction;
import vn.viettel.okrkpi.contract.oversight.model.AlertActionInput;
import vn.viettel.okrkpi.contract.oversight.model.AlertActionType;
import vn.viettel.okrkpi.contract.oversight.model.AlertSubject;
import vn.viettel.okrkpi.contract.oversight.model.AnomalyAlert;
import vn.viettel.okrkpi.contract.oversight.model.AnomalyAlertCounts;
import vn.viettel.okrkpi.contract.oversight.model.AnomalyAlertStatus;
import vn.viettel.okrkpi.contract.oversight.model.AnomalyAlertSummary;
import vn.viettel.okrkpi.contract.oversight.model.AnomalySeverity;
import vn.viettel.okrkpi.contract.oversight.model.AnomalySignalCode;
import vn.viettel.okrkpi.contract.oversight.model.EvidenceChainNode;
import vn.viettel.okrkpi.contract.oversight.model.ListAnomalyAlerts200Response;
import vn.viettel.okrkpi.contract.oversight.model.PeriodRef;
import vn.viettel.okrkpi.contract.oversight.model.Ref;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.Command;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.Counts;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.View;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService.Viewer;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertWorkflow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Trung tâm cảnh báo (CORE-07-11, QT-14, SCR-1401): {@code listAnomalyAlerts}, {@code
 * getAnomalyAlert}, {@code createAlertAction}. Ẩn với đối tượng, phạm vi cây tổ chức và tách vai
 * trò kiểm ở {@link AlertCaseService}; cảnh báo không thấy được trả 404.
 */
@RestController
@RequestMapping(AnomalyAlertController.BASE)
public class AnomalyAlertController {

  static final String BASE = "/api/v1/oversight/alerts";
  private static final String SCOPE = "anomaly-alerts";

  /** Tiền tố mã bằng chứng sang loại nút của chuỗi truy vết (suy luận, wiki trang 14). */
  private static final Map<String, EvidenceChainNode.KindEnum> KINDS =
      Map.ofEntries(
          Map.entry("appraisal_profile", EvidenceChainNode.KindEnum.DIEM),
          Map.entry("component", EvidenceChainNode.KindEnum.CAU_PHAN),
          Map.entry("objective", EvidenceChainNode.KindEnum.KPI),
          Map.entry("key_result", EvidenceChainNode.KindEnum.KPI),
          Map.entry("kpi", EvidenceChainNode.KindEnum.KPI),
          Map.entry("task", EvidenceChainNode.KindEnum.NHIEM_VU),
          Map.entry("task_change_request", EvidenceChainNode.KindEnum.NHIEM_VU),
          Map.entry("deliverable", EvidenceChainNode.KindEnum.SAN_PHAM),
          Map.entry("product", EvidenceChainNode.KindEnum.SAN_PHAM),
          Map.entry("evidence", EvidenceChainNode.KindEnum.MINH_CHUNG),
          Map.entry("ledger_entry", EvidenceChainNode.KindEnum.BAN_GHI_SO_CAI),
          Map.entry("ledger_chain", EvidenceChainNode.KindEnum.BAN_GHI_SO_CAI),
          Map.entry("sha256", EvidenceChainNode.KindEnum.BAM_TRUNG));

  private final ObjectProvider<AlertCaseService> service;
  private final ObjectProvider<OversightIdempotency> idempotency;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OversightCaller> custom;
  private final CursorCodec cursors;

  public AnomalyAlertController(
      ObjectProvider<AlertCaseService> service,
      ObjectProvider<OversightIdempotency> idempotency,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OversightCaller> custom,
      CursorCodec cursors) {
    this.service = Objects.requireNonNull(service, "service");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping
  @RequiresPermission(operationId = "listAnomalyAlerts", module = "oversight")
  public ListAnomalyAlerts200Response listAnomalyAlerts(
      @RequestParam(value = "periodId", required = false) UUID periodId,
      @RequestParam(value = "unitId", required = false) UUID unitId,
      @RequestParam(value = "signal", required = false) String signal,
      @RequestParam(value = "severity", required = false) String severity,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    Viewer viewer = viewer();
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    AnomalyAlertRepository.After after =
        page.afterKey()
            .map(
                k ->
                    new AnomalyAlertRepository.After(
                        (int) k.getLong(0), k.getInstant(1), k.getUuid(2)))
            .orElse(null);
    AnomalyAlertRepository.Filter filter =
        new AnomalyAlertRepository.Filter(
            periodId,
            unitId,
            codes("signal", signal, AnomalySignalCode::fromValue),
            codes("severity", severity, AnomalySeverity::fromValue),
            codes("status", status, AnomalyAlertStatus::fromValue));
    AlertCaseService s = service();
    CursorPage<View> result =
        cursors.page(
            s.list(tenant, viewer, filter, after, page.fetchSize()),
            page,
            v ->
                CursorKey.of(
                    (long) AlertWorkflow.order(v.alert().severity()),
                    v.alert().detectedAt(),
                    v.alert().id()));
    List<AnomalyAlertSummary> items = new ArrayList<>();
    for (View v : result.items()) {
      items.add(summary(v));
    }
    String next = result.nextCursor();
    Counts c = s.counts(tenant, viewer);
    return new ListAnomalyAlerts200Response()
        .items(items)
        .nextCursor(next)
        .counts(
            new AnomalyAlertCounts()
                .windowDays(c.windowDays())
                .newCount(c.newCount())
                .validCount(c.validCount())
                .assignedCount(c.assigned())
                .violationCount(c.violations()));
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getAnomalyAlert",
      module = "oversight",
      resourceType = "anomaly_alert")
  public ResponseEntity<AnomalyAlert> getAnomalyAlert(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    AlertCaseService s = service();
    View v = notFoundAs404(() -> s.get(tenant, viewer(), id));
    return ETags.ok(detail(v, s.names(tenant, v)), v.alert().rowVersion());
  }

  @PostMapping("/{id}/actions")
  @RequiresPermission(
      operationId = "createAlertAction",
      module = "oversight",
      resourceType = "anomaly_alert")
  public ResponseEntity<AnomalyAlert> createAlertAction(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AlertActionInput body) {
    TenantId tenant = TenantContext.require();
    Viewer viewer = viewer();
    AlertCaseService s = service();
    OversightIdempotency idem = idempotency.getIfAvailable();
    if (idem == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    Command command =
        body == null
            ? new Command(null, null, null, null)
            : new Command(
                body.getType() == null ? null : body.getType().getValue(),
                body.getNote(),
                body.getAssignedTo(),
                body.getVerdict() == null ? null : body.getVerdict().getValue());
    return idem.run(
        tenant,
        viewer.userId(),
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/actions",
        body,
        AnomalyAlert.class,
        AnomalyAlert::getRowVersion,
        () -> {
          View v = notFoundAs404(() -> s.act(tenant, viewer, id, ifMatch, command));
          return ETags.ok(detail(v, s.names(tenant, v)), v.alert().rowVersion());
        });
  }

  private static AnomalyAlertSummary summary(View v) {
    vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert a = v.alert();
    return new AnomalyAlertSummary()
        .id(a.id())
        .severity(AnomalySeverity.fromValue(a.severity()))
        .signalCode(AnomalySignalCode.fromValue(a.signalCode()))
        .subject(subject(v))
        .period(period(a.periodId(), v.period()))
        .status(AnomalyAlertStatus.fromValue(a.status()))
        .detectedAt(odt(a.detectedAt()));
  }

  private static AnomalyAlert detail(View v, Map<UUID, String> names) {
    vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert a = v.alert();
    List<EvidenceChainNode> chain = new ArrayList<>();
    List<String> ledger = new ArrayList<>();
    for (String item : a.evidence()) {
      int colon = item.indexOf(':');
      String prefix = item.substring(0, colon);
      String value = item.substring(colon + 1);
      EvidenceChainNode.KindEnum kind = KINDS.get(prefix);
      if (kind != null) {
        chain.add(new EvidenceChainNode().kind(kind).id(value));
      }
      if ("ledger_entry".equals(prefix)) {
        ledger.add(value);
      }
    }
    List<AlertAction> actions = new ArrayList<>();
    for (AnomalyAlertRepository.Action x : v.actions()) {
      AlertAction dto =
          new AlertAction()
              .id(x.id())
              .action(AlertActionType.fromValue(x.action()))
              .active(x.active())
              .actedAt(odt(x.actedAt()));
      dto.setNote(x.note());
      if (x.verdict() != null) {
        dto.setVerdict(AlertAction.VerdictEnum.fromValue(x.verdict()));
      }
      if (x.actor() != null && !"TU_DONG".equals(x.action())) {
        dto.actor(new Ref().id(x.actor()).name(names.getOrDefault(x.actor(), "")));
      }
      if (x.assignedTo() != null) {
        dto.assignedTo(new Ref().id(x.assignedTo()).name(names.getOrDefault(x.assignedTo(), "")));
      }
      actions.add(dto);
    }
    AnomalyAlert dto =
        new AnomalyAlert()
            .id(a.id())
            .severity(AnomalySeverity.fromValue(a.severity()))
            .signalCode(AnomalySignalCode.fromValue(a.signalCode()))
            .subject(subject(v))
            .period(period(a.periodId(), v.period()))
            .status(AnomalyAlertStatus.fromValue(a.status()))
            .detectedAt(odt(a.detectedAt()))
            .evidenceChain(chain)
            .modelVersion(a.modelVersion())
            .actions(actions)
            .rowVersion((int) a.rowVersion());
    if (!ledger.isEmpty()) {
      dto.ledgerEntryIds(ledger);
    }
    return dto;
  }

  private static AlertSubject subject(View v) {
    AlertSubject s =
        new AlertSubject()
            .type(AlertSubject.TypeEnum.fromValue(v.alert().subjectType()))
            .id(v.alert().subjectId());
    if (v.subject() != null && v.subject().name() != null) {
      s.name(v.subject().name());
    }
    return s;
  }

  /**
   * Kỳ không còn tra được thì bỏ trường {@code period} (các trường của {@code PeriodRef} bắt buộc).
   */
  private static PeriodRef period(UUID id, PeriodSnapshot p) {
    return p == null
        ? null
        : new PeriodRef().id(id).label(p.name()).startDate(p.startDate()).endDate(p.endDate());
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static <E> Set<String> codes(
      String field, String raw, java.util.function.Function<String, E> parse) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<String> out = new java.util.LinkedHashSet<>();
    for (String v : Arrays.stream(raw.split(",")).map(String::trim).toList()) {
      if (v.isEmpty()) {
        continue;
      }
      try {
        parse.apply(v);
      } catch (IllegalArgumentException e) {
        throw new BusinessException(
            "COMMON-422-VALIDATION",
            "Giá trị lọc không hợp lệ.",
            List.of(new ProblemFieldError(field, "Giá trị lọc không hợp lệ.", null)));
      }
      out.add(v);
    }
    return out;
  }

  private Viewer viewer() {
    OversightCaller caller = custom.getIfAvailable();
    if (caller != null) {
      return new Viewer(
          caller.currentUserId().orElseThrow(AnomalyAlertController::unauthorized),
          caller.roles(),
          caller.unitId().orElse(null));
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return new Viewer(
          UUID.fromString(subject.userId()),
          subject.roles(),
          subject.unitId() == null || subject.unitId().isBlank()
              ? null
              : UUID.fromString(subject.unitId()));
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private AlertCaseService service() {
    AlertCaseService s = service.getIfAvailable();
    if (s == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return s;
  }

  private static <T> T notFoundAs404(Supplier<T> action) {
    try {
      return action.get();
    } catch (AlertCaseService.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}

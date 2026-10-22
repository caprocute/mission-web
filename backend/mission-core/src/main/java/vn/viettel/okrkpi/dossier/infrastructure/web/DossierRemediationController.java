package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.dossiers.model.DossierRemediationItem;
import vn.viettel.okrkpi.contract.dossiers.model.DossierRemediationItemPage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierRemediationItemUpdate;
import vn.viettel.okrkpi.contract.dossiers.model.DossierRemediationStatus;
import vn.viettel.okrkpi.contract.dossiers.model.PeriodRef;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.application.remediation.RemediationService;
import vn.viettel.okrkpi.dossier.application.remediation.RemediationService.ItemView;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.ItemRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.Origin;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Theo dõi khắc phục hạn chế của {@code dossiers.yaml} (CORE-06-04, SCR-1101 vùng 3, BR-11-08):
 * {@code listDossierMeetingRemediationItems}, {@code updateDossierRemediationItem}. Nội dung là C2
 * nên thao tác khai mức {@code C2} để lớp OPA ghi nhật ký truy cập; tên người trả rỗng.
 */
@RestController
public class DossierRemediationController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private final RemediationService service;
  private final DossierCallers callers;
  private final ObjectProvider<PeriodApi> periods;

  public DossierRemediationController(
      RemediationService service,
      ObjectProvider<DossierCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodApi> periods) {
    this.service = Objects.requireNonNull(service, "service");
    this.callers = new DossierCallers(callers, subjects);
    this.periods = Objects.requireNonNull(periods, "periods");
  }

  @GetMapping("/api/v1/dossiers/meetings/{id}/remediation-items")
  @RequiresPermission(
      operationId = "listDossierMeetingRemediationItems",
      module = "dossiers",
      resourceType = "remediation_item",
      classification = "C2")
  public ResponseEntity<DossierRemediationItemPage> listDossierMeetingRemediationItems(
      @PathVariable("id") UUID id,
      @RequestParam(value = "origin", required = false) String origin,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    Origin filter = null;
    if (origin != null) {
      try {
        filter = Origin.valueOf(origin);
      } catch (IllegalArgumentException e) {
        throw invalid("origin", "Nguồn hạn chế không hợp lệ.");
      }
    }
    List<ItemView> rows;
    try {
      rows =
          service.list(
              tenant,
              callers.actor(),
              callers.unit().orElse(null),
              id,
              filter,
              decodeCursor(cursor),
              size + 1);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    boolean more = rows.size() > size;
    List<ItemView> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<DossierRemediationItem> items = new ArrayList<>();
    for (ItemView v : page) {
      PeriodRef ref = refs.computeIfAbsent(v.row().periodId(), p -> periodRef(tenant, p));
      items.add(item(v, ref));
    }
    String next = more ? encodeCursor(page.get(page.size() - 1).row().id()) : null;
    return ResponseEntity.ok(new DossierRemediationItemPage(items, next));
  }

  @PatchMapping("/api/v1/dossiers/remediation-items/{id}")
  @Idempotent
  @RequiresPermission(
      operationId = "updateDossierRemediationItem",
      module = "dossiers",
      resourceType = "remediation_item",
      classification = "C2")
  public ResponseEntity<DossierRemediationItem> updateDossierRemediationItem(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody DossierRemediationItemUpdate body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    ItemView view;
    try {
      view =
          service.update(
              tenant,
              callers.actor(),
              id,
              expected,
              body.getStatus() == null ? null : body.getStatus().getValue(),
              body.getNote());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ETags.ok(item(view, periodRef(tenant, view.row().periodId())), view.row().rowVersion());
  }

  static DossierRemediationItem item(ItemView v, PeriodRef period) {
    ItemRow r = v.row();
    DossierRemediationItem dto =
        new DossierRemediationItem(
            r.id(),
            new Ref(r.personId(), ""),
            r.meetingId(),
            v.content(),
            DossierRemediationStatus.fromValue(r.status()),
            (int) r.rowVersion());
    dto.setReviewCommentId(r.reviewCommentId());
    dto.setSourcePeriod(period);
    dto.setDuePeriodId(r.duePeriodId());
    dto.setVerifiedBy(r.verifiedBy() == null ? null : new Ref(r.verifiedBy(), ""));
    dto.setVerifiedAt(time(r.verifiedAt()));
    return dto;
  }

  static String encodeCursor(UUID last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(last.toString().getBytes(StandardCharsets.US_ASCII));
  }

  static UUID decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
    } catch (RuntimeException e) {
      throw invalid("cursor", "Con trỏ trang không hợp lệ.");
    }
  }

  private PeriodRef periodRef(TenantId tenant, UUID periodId) {
    PeriodApi api = periods.getIfAvailable();
    Optional<PeriodSnapshot> snapshot =
        api == null ? Optional.empty() : api.findById(tenant, PeriodId.of(periodId));
    return snapshot
        .map(s -> new PeriodRef(periodId, s.name(), s.startDate(), s.endDate()))
        .orElseGet(() -> new PeriodRef(periodId, "", null, null));
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

package vn.viettel.okrkpi.okr.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
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
import vn.viettel.okrkpi.contract.okr.model.OkrCheckin;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinChannel;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinCreate;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinPage;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinResult;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinResultKeyResult;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinResultObjective;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinStatus;
import vn.viettel.okrkpi.contract.okr.model.OkrConfidence;
import vn.viettel.okrkpi.contract.okr.model.OkrRiskFlag;
import vn.viettel.okrkpi.contract.okr.model.Ref;
import vn.viettel.okrkpi.okr.application.OkrCheckinService;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinCommand;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinOutcome;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.History;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Check-in KR của {@code okr.yaml} cho QT-03/SCR-0301, SCR-0302 (CORE-01-08): {@code
 * createOkrCheckin} (lưu nháp hoặc gửi, bắt buộc {@code Idempotency-Key}, giao dịch 5 giây) và
 * {@code listOkrCheckins} (con trỏ, mới nhất trước). Không có đường dẫn sửa, xoá check-in
 * (BR-03-03).
 */
@RestController
@RequestMapping("/api/v1/okr")
public class OkrCheckinController {

  private static final String BASE = "/api/v1/okr";

  /** Giới hạn giao dịch gửi check-in (TL-01 "Quản lý giao dịch", mẫu UpdateProgressUseCase). */
  static final int TIMEOUT_SECONDS = 5;

  private final OkrCheckinService checkins;
  private final OkrIdempotency idempotency;
  private final CursorCodec cursors;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OkrCaller> callers;

  public OkrCheckinController(
      OkrCheckinService checkins,
      OkrIdempotency idempotency,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OkrCaller> callers) {
    this.checkins = Objects.requireNonNull(checkins, "checkins");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/key-results/{id}/checkins")
  @RequiresPermission(operationId = "createOkrCheckin", module = "okr")
  public ResponseEntity<OkrCheckinResult> createOkrCheckin(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrCheckinCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    CheckinCommand cmd =
        new CheckinCommand(
            body.getStatus() == null ? null : body.getStatus().getValue(),
            body.getValue(),
            body.getConfidence() == null ? null : body.getConfidence().getValue(),
            body.getSourceValue(),
            body.getSourceFetchedAt() == null ? null : body.getSourceFetchedAt().toInstant(),
            body.getOverrideReason(),
            body.getHighlights(),
            body.getBlockerNote(),
            body.getChannel() == null ? null : body.getChannel().getValue(),
            Boolean.TRUE.equals(body.getConfirmLargeChange()));
    return mapped(
        () ->
            idempotency.runWithTimeout(
                TIMEOUT_SECONDS,
                tenant,
                actor,
                idempotencyKey,
                "POST",
                BASE + "/key-results/" + id + "/checkins",
                body,
                OkrCheckinResult.class,
                null,
                () ->
                    ResponseEntity.status(HttpStatus.CREATED)
                        .body(result(checkins.record(tenant, actor, id, idempotencyKey, cmd)))));
  }

  @GetMapping("/key-results/{id}/checkins")
  @RequiresPermission(operationId = "listOkrCheckins", module = "okr")
  public OkrCheckinPage listOkrCheckins(
      @PathVariable("id") UUID id,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    UUID viewer = currentUser().orElse(null);
    PageRequest page = cursors.pageRequest(limit, cursor, "okr-checkins:" + id + ":" + viewer);
    History history =
        mapped(
            () ->
                checkins.history(
                    tenant,
                    viewer,
                    id,
                    page.afterKey().map(k -> k.getInstant(0)).orElse(null),
                    page.afterKey().map(k -> k.getUuid(1)).orElse(null),
                    page.fetchSize()));
    CursorPage<CheckinRow> result =
        cursors.page(history.items(), page, c -> CursorKey.of(c.checkinAt(), c.id()));
    return new OkrCheckinPage(
        result.items().stream().map(c -> checkin(c, history.owner())).toList(),
        result.nextCursor());
  }

  // ---------------------------------------------------------------- ánh xạ

  static OkrCheckinResult result(CheckinOutcome outcome) {
    CheckinTarget k = outcome.keyResult();
    OkrCheckinResultKeyResult kr =
        new OkrCheckinResultKeyResult(
                k.keyResultId(),
                k.code(),
                k.progress(),
                k.riskFlag() == null ? null : OkrRiskFlag.fromValue(k.riskFlag()))
            .currentValue(k.currentValue())
            .confidence(k.confidence() == null ? null : OkrConfidence.fromValue(k.confidence()));
    return new OkrCheckinResult(
        checkin(outcome.checkin(), k.owner()),
        kr,
        new OkrCheckinResultObjective(k.objectiveId(), k.objectiveCode(), k.objectiveProgress()));
  }

  static OkrCheckin checkin(CheckinRow c, UUID owner) {
    OkrCheckin dto =
        new OkrCheckin(
                c.id(),
                c.keyResultId(),
                time(c.checkinAt()),
                plain(c.value()),
                OkrConfidence.fromValue(c.confidence()),
                OkrCheckinChannel.fromValue(c.channel()),
                OkrCheckinStatus.fromValue(c.status()))
            .periodId(c.periodId())
            .highlights(c.highlights())
            .blockerNote(c.blockerNote())
            .sourceValue(plain(c.sourceValue()))
            .sourceFetchedAt(time(c.sourceFetchedAt()))
            .overrideReason(c.overrideReason())
            .verifiedAt(time(c.verifiedAt()))
            .onBehalf(owner != null && !owner.equals(c.createdBy()));
    if (c.verifiedBy() != null) {
      dto.verifiedBy(new Ref(c.verifiedBy(), ""));
    }
    if (c.createdBy() != null) {
      dto.createdBy(new Ref(c.createdBy(), ""));
    }
    return dto;
  }

  private static BigDecimal plain(BigDecimal v) {
    return v == null ? null : v.stripTrailingZeros();
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static <T> T mapped(Supplier<T> work) {
    try {
      return work.get();
    } catch (OkrCheckinService.NotFound e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (OkrCheckinService.Forbidden e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
  }

  // ---------------------------------------------------------------- người gọi

  private UUID actor() {
    return currentUser()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập"));
  }

  private Optional<UUID> currentUser() {
    OkrCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId();
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(subject.userId()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}

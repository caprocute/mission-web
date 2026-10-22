package vn.viettel.okrkpi.okr.infrastructure.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.FeatureFlagApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.okr.model.CopyOkrObjectivesFromPeriod201Response;
import vn.viettel.okrkpi.contract.okr.model.OkrComposeContext;
import vn.viettel.okrkpi.contract.okr.model.OkrCopyFromPeriodRequest;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResult;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResultCreate;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResultInput;
import vn.viettel.okrkpi.contract.okr.model.OkrLink;
import vn.viettel.okrkpi.contract.okr.model.OkrLinkCreate;
import vn.viettel.okrkpi.contract.okr.model.OkrObjective;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveCreate;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveStatus;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveUpdate;
import vn.viettel.okrkpi.contract.okr.model.OkrParentOption;
import vn.viettel.okrkpi.contract.okr.model.OkrPeriodInfo;
import vn.viettel.okrkpi.contract.okr.model.OkrPersonOption;
import vn.viettel.okrkpi.contract.okr.model.OkrPolicy;
import vn.viettel.okrkpi.contract.okr.model.PeriodRef;
import vn.viettel.okrkpi.contract.okr.model.Ref;
import vn.viettel.okrkpi.okr.application.OkrComposeService;
import vn.viettel.okrkpi.okr.application.OkrComposeService.LinkDraft;
import vn.viettel.okrkpi.okr.application.OkrComposeService.ObjectiveDraft;
import vn.viettel.okrkpi.okr.application.OkrComposeService.Owner;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveFilter;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Phía soạn của {@code okr.yaml} cho QT-02/SCR-0202 (CORE-01-03): {@code createOkrObjective},
 * {@code updateOkrObjective}, {@code copyOkrObjectivesFromPeriod}, {@code createOkrKeyResult},
 * {@code createOkrLink}, {@code deleteOkrLink}, {@code getOkrComposeContext}. Mọi lệnh ghi qua
 * {@link OkrIdempotency} (48 giờ, cùng giao dịch); đơn vị sở hữu phải là đơn vị của người gọi hoặc
 * đơn vị cấp dưới (phạm vi Đv của ma trận SCR-0202), kiểm lại ở máy chủ ngoài lớp OPA.
 */
@RestController
@RequestMapping("/api/v1/okr")
public class OkrComposeController {

  private static final String BASE = "/api/v1/okr";

  /** Cờ tính năng trợ lý AI soát OKR của tenant (suy luận tên cờ, AC-12). */
  static final String AI_FLAG = "okr.ai-assistant";

  /** Khoá tham số Policy Pack cho số mục tiêu khuyến nghị (suy luận, BR-02-01). */
  static final String RECOMMENDED_MAX_PARAM = "okr_recommended_max";

  /** Từ mơ hồ mặc định (BR-02-05) khi Policy Pack chưa đặt. */
  static final List<String> VAGUE_WORDS =
      List.of("tăng cường", "đẩy mạnh", "nâng cao", "góp phần", "tích cực", "chú trọng");

  /** Động từ mở đầu giống nhiệm vụ mặc định (BR-02-06). */
  static final List<String> TASK_VERBS =
      List.of("tổ chức", "ban hành", "triển khai", "xây dựng", "thực hiện");

  /** Đơn vị đo gợi ý (SCR-0202 trường 9). */
  static final List<String> UNITS = List.of("%", "người", "hồ sơ", "ngày", "lần", "mốc");

  private final OkrComposeService compose;
  private final OkrReadService reads;
  private final OkrIdempotency idempotency;
  private final ObjectProvider<OrgDirectoryApi> org;
  private final ObjectProvider<PolicyQueryApi> policies;
  private final ObjectProvider<FeatureFlagApi> flags;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OkrCaller> callers;

  public OkrComposeController(
      OkrComposeService compose,
      OkrReadService reads,
      OkrIdempotency idempotency,
      ObjectProvider<OrgDirectoryApi> org,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<FeatureFlagApi> flags,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OkrCaller> callers) {
    this.compose = Objects.requireNonNull(compose, "compose");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.org = Objects.requireNonNull(org, "org");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.flags = Objects.requireNonNull(flags, "flags");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/objectives")
  @RequiresPermission(operationId = "createOkrObjective", module = "okr")
  public ResponseEntity<OkrObjective> createOkrObjective(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrObjectiveCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    if (body.getOwnerUnitId() != null) {
      requireUnitScope(tenant, body.getOwnerUnitId());
    }
    Owner owner =
        new Owner(
            body.getPeriodId(),
            body.getOwnerType() == null ? "TAP_THE" : body.getOwnerType().getValue(),
            body.getOwnerUnitId(),
            body.getOwnerPersonId());
    ObjectiveDraft draft =
        new ObjectiveDraft(
            body.getTitle(),
            body.getDescription(),
            body.getTier(),
            body.getParentObjectiveId(),
            body.getBasisIds(),
            body.getOverLimitReason(),
            body.getAiSuggestionId(),
            keyResults(body.getKeyResults()));
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/objectives",
        body,
        OkrObjective.class,
        OkrObjective::getRowVersion,
        () -> {
          int max = recommendedMax(tenant, body.getPeriodId(), body.getOwnerUnitId());
          UUID id = compose.create(tenant, actor, owner, draft, max);
          OkrObjective dto = detail(tenant, id);
          return ResponseEntity.created(URI.create(BASE + "/objectives/" + id))
              .eTag(ETags.of(dto.getRowVersion()))
              .body(dto);
        });
  }

  @PutMapping("/objectives/{id}")
  @RequiresPermission(operationId = "updateOkrObjective", module = "okr")
  public ResponseEntity<OkrObjective> updateOkrObjective(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody OkrObjectiveUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    ETags.expectedVersion(ifMatch, "OKR");
    ObjectiveDraft draft =
        new ObjectiveDraft(
            body.getTitle(),
            body.getDescription(),
            body.getTier(),
            body.getParentObjectiveId(),
            body.getBasisIds(),
            body.getOverLimitReason(),
            body.getAiSuggestionId(),
            keyResults(body.getKeyResults()));
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + "/objectives/" + id + "#" + ifMatch,
        body,
        OkrObjective.class,
        OkrObjective::getRowVersion,
        () -> {
          var head = found(() -> compose.head(tenant, id).orElseThrow());
          requireUnitScope(tenant, head.ownerUnitId());
          int max = recommendedMax(tenant, head.periodId(), head.ownerUnitId());
          found(() -> compose.update(tenant, actor, id, ifMatch, draft, max));
          OkrObjective dto = detail(tenant, id);
          return ETags.ok(dto, dto.getRowVersion());
        });
  }

  @PostMapping("/objectives/copy-from-period")
  @RequiresPermission(operationId = "copyOkrObjectivesFromPeriod", module = "okr")
  public ResponseEntity<CopyOkrObjectivesFromPeriod201Response> copyOkrObjectivesFromPeriod(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrCopyFromPeriodRequest body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    if (body.getOwnerUnitId() != null) {
      requireUnitScope(tenant, body.getOwnerUnitId());
    }
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/objectives/copy-from-period",
        body,
        CopyOkrObjectivesFromPeriod201Response.class,
        r -> 0,
        () -> {
          List<UUID> ids =
              compose.copyFromPeriod(
                  tenant,
                  actor,
                  body.getFromPeriodId(),
                  body.getToPeriodId(),
                  body.getOwnerUnitId(),
                  body.getObjectiveIds());
          List<OkrObjective> items = new ArrayList<>();
          for (UUID id : ids) {
            items.add(detail(tenant, id));
          }
          return ResponseEntity.status(HttpStatus.CREATED)
              .body(new CopyOkrObjectivesFromPeriod201Response(items));
        });
  }

  @PostMapping("/key-results")
  @RequiresPermission(operationId = "createOkrKeyResult", module = "okr")
  public ResponseEntity<OkrKeyResult> createOkrKeyResult(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrKeyResultCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    KeyResultData data =
        new KeyResultData(
            null,
            body.getTitle(),
            body.getMetricType() == null ? null : body.getMetricType().getValue(),
            body.getUnit(),
            body.getBaselineValue(),
            body.getTargetValue(),
            body.getDirection() == null ? null : body.getDirection().getValue(),
            body.getWeight(),
            body.getDueDate(),
            body.getVerifierPersonId(),
            body.getDataSource(),
            body.getFormulaText(),
            body.getCheckinFrequency() == null ? null : body.getCheckinFrequency().getValue(),
            body.getContentSource() == null ? null : body.getContentSource().getValue(),
            body.getAiSuggestionId());
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/key-results",
        body,
        OkrKeyResult.class,
        OkrKeyResult::getRowVersion,
        () -> {
          if (body.getObjectiveId() != null) {
            compose
                .head(tenant, body.getObjectiveId())
                .ifPresent(h -> requireUnitScope(tenant, h.ownerUnitId()));
          }
          UUID id = compose.addKeyResult(tenant, actor, body.getObjectiveId(), data);
          OkrKeyResult dto = OkrReadController.keyResult(reads.keyResult(tenant, id).orElseThrow());
          return ResponseEntity.created(URI.create(BASE + "/key-results/" + id))
              .eTag(ETags.of(dto.getRowVersion()))
              .body(dto);
        });
  }

  @PostMapping("/links")
  @RequiresPermission(operationId = "createOkrLink", module = "okr")
  public ResponseEntity<OkrLink> createOkrLink(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrLinkCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    if (body.getFromType() == null || body.getToType() == null || body.getLinkKind() == null) {
      throw OkrReadController.missing(
          body.getFromType() == null
              ? "fromType"
              : body.getToType() == null ? "toType" : "linkKind");
    }
    LinkDraft draft =
        new LinkDraft(
            body.getFromType().getValue(),
            body.getFromId(),
            body.getToType().getValue(),
            body.getToId(),
            body.getLinkKind().getValue(),
            body.getContributionWeight());
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/links",
        body,
        OkrLink.class,
        OkrLink::getRowVersion,
        () -> {
          OkrLink dto = OkrReadController.link(compose.createLink(tenant, actor, draft));
          return ResponseEntity.status(HttpStatus.CREATED)
              .eTag(ETags.of(dto.getRowVersion()))
              .body(dto);
        });
  }

  @DeleteMapping("/links/{id}")
  @RequiresPermission(operationId = "deleteOkrLink", module = "okr")
  public ResponseEntity<Void> deleteOkrLink(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = actor();
    ETags.expectedVersion(ifMatch, "OKR");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "DELETE",
        BASE + "/links/" + id + "#" + ifMatch,
        null,
        Void.class,
        v -> 0,
        () -> {
          found(
              () -> {
                compose.deleteLink(tenant, id, ifMatch);
                return null;
              });
          return ResponseEntity.noContent().build();
        });
  }

  @GetMapping("/compose-context")
  @RequiresPermission(operationId = "getOkrComposeContext", module = "okr")
  public OkrComposeContext getOkrComposeContext(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "objective", required = false) UUID objective) {
    TenantId tenant = TenantContext.require();
    UUID me = actor();
    PeriodSnapshot p = found(() -> reads.period(tenant, period));
    UUID unit = currentUnit().orElseThrow(() -> OkrReadController.missing("unit"));
    UUID ownerUnit = unit;
    long existing;
    String ownerType = "TAP_THE";
    if (objective != null) {
      var head = found(() -> compose.head(tenant, objective).orElseThrow());
      requireUnitScope(tenant, head.ownerUnitId());
      ownerUnit = head.ownerUnitId();
      ownerType = head.ownerType();
    }
    existing = reads.countUnitObjectives(tenant, p, ownerUnit);
    if (objective != null && "TAP_THE".equals(ownerType)) {
      existing = Math.max(0, existing - 1);
    }
    OrgDirectoryApi directory = org.getIfAvailable();
    Optional<UUID> parentUnit =
        directory == null
            ? Optional.empty()
            : directory.parentOf(tenant, OrgTree.CHUYEN_MON, ownerUnit);
    boolean topLevel = directory != null && parentUnit.isEmpty();
    List<OkrParentOption> parents = new ArrayList<>();
    if (parentUnit.isPresent()) {
      ObjectiveFilter filter =
          new ObjectiveFilter(
              p.id().value(),
              OkrReadService.buckets(p),
              List.of(parentUnit.get()),
              null,
              null,
              List.of("DA_DUYET"),
              null);
      for (ObjectiveView v : reads.objectives(tenant, filter, null, null, 200)) {
        parents.add(
            new OkrParentOption(v.row().id(), v.row().code(), v.row().title(), p.id().value())
                .status(OkrObjectiveStatus.fromValue(v.row().status()))
                .unitName(""));
      }
    }
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
    Set<UUID> verifierIds = new LinkedHashSet<>();
    if (directory != null) {
      verifierIds.addAll(directory.headsOf(tenant, ownerUnit, today));
      parentUnit.ifPresent(u -> verifierIds.addAll(directory.headsOf(tenant, u, today)));
    }
    verifierIds.remove(me);
    OkrPeriodInfo info = OkrReadController.periodInfo(reads.periodView(p));
    FeatureFlagApi flagApi = flags.getIfAvailable();
    boolean ai =
        flagApi != null
            && flagApi.isEnabled(
                tenant, AI_FLAG, new FeatureFlagApi.FlagContext(me, List.of(ownerUnit)));
    return new OkrComposeContext(
        info,
        p.isLocked() ? List.of() : List.of(info),
        new Ref(ownerUnit, ""),
        topLevel,
        reads.bases(tenant, null, today, null, null, null, 200).stream()
            .map(OkrReadController::basisRef)
            .toList(),
        parents,
        (int) existing,
        verifierIds.stream().map(id -> new OkrPersonOption(id, "")).toList(),
        new Ref(me, ""),
        ai,
        policy(tenant, p, ownerUnit));
  }

  // ---------------------------------------------------------------- tiện ích

  private OkrObjective detail(TenantId tenant, UUID id) {
    ObjectiveView view = reads.objective(tenant, id).orElseThrow();
    OkrObjective dto = OkrReadController.objective(view, reads.linksOf(tenant, view));
    if (view.row().parentObjectiveId() != null) {
      reads
          .objectiveRow(tenant, view.row().parentObjectiveId())
          .ifPresent(parent -> dto.parentObjective(OkrReadController.objectiveRef(parent)));
    }
    reads
        .optionalPeriod(tenant, view.row().periodId())
        .ifPresent(
            p -> dto.period(new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate())));
    return dto;
  }

  private OkrPolicy policy(TenantId tenant, PeriodSnapshot p, UUID unit) {
    Optional<PolicyQueryApi.ResolvedPack> pack = resolve(tenant, p.id().value(), unit);
    return new OkrPolicy(
        pack.map(PolicyQueryApi.ResolvedPack::version).orElse("mac-dinh"),
        recommendedMax(tenant, pack),
        VAGUE_WORDS,
        TASK_VERBS,
        UNITS,
        List.of());
  }

  private int recommendedMax(TenantId tenant, UUID periodId, UUID unit) {
    if (periodId == null) {
      return OkrReadService.RECOMMENDED_MAX;
    }
    return recommendedMax(tenant, resolve(tenant, periodId, unit));
  }

  private int recommendedMax(TenantId tenant, Optional<PolicyQueryApi.ResolvedPack> pack) {
    PolicyQueryApi api = policies.getIfAvailable();
    if (api == null || pack.isEmpty()) {
      return OkrReadService.RECOMMENDED_MAX;
    }
    return api.parameter(tenant, pack.get().versionId(), RECOMMENDED_MAX_PARAM)
        .filter(Number.class::isInstance)
        .map(v -> ((Number) v).intValue())
        .filter(v -> v >= 1)
        .orElse(OkrReadService.RECOMMENDED_MAX);
  }

  private Optional<PolicyQueryApi.ResolvedPack> resolve(TenantId tenant, UUID periodId, UUID unit) {
    PolicyQueryApi api = policies.getIfAvailable();
    if (api == null) {
      return Optional.empty();
    }
    return api.resolve(tenant, PeriodId.of(periodId), "DANG", null, unit);
  }

  private static List<KeyResultData> keyResults(List<OkrKeyResultInput> input) {
    if (input == null) {
      return List.of();
    }
    return input.stream()
        .map(
            k ->
                new KeyResultData(
                    k.getId(),
                    k.getTitle(),
                    k.getMetricType() == null ? null : k.getMetricType().getValue(),
                    k.getUnit(),
                    k.getBaselineValue(),
                    k.getTargetValue(),
                    k.getDirection() == null ? null : k.getDirection().getValue(),
                    k.getWeight(),
                    k.getDueDate(),
                    k.getVerifierPersonId(),
                    k.getDataSource(),
                    k.getFormulaText(),
                    k.getCheckinFrequency() == null ? null : k.getCheckinFrequency().getValue(),
                    k.getContentSource() == null ? null : k.getContentSource().getValue(),
                    k.getAiSuggestionId()))
        .toList();
  }

  /** Đơn vị sở hữu phải là đơn vị của người gọi hoặc đơn vị cấp dưới trên một trong hai cây. */
  private void requireUnitScope(TenantId tenant, UUID ownerUnit) {
    UUID own = currentUnit().orElseThrow(OkrComposeController::forbidden);
    if (own.equals(ownerUnit)) {
      return;
    }
    OrgDirectoryApi directory = org.getIfAvailable();
    if (directory != null) {
      for (OrgTree tree : OrgTree.values()) {
        if (directory.isDescendant(tenant, tree, own, ownerUnit)) {
          return;
        }
      }
    }
    throw forbidden();
  }

  private UUID actor() {
    OkrCaller caller = callers.getIfAvailable();
    Optional<UUID> user =
        caller != null
            ? caller.currentUserId()
            : subject().map(OpaInput.Subject::userId).flatMap(OkrComposeController::uuid);
    return user.orElseThrow(
        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập"));
  }

  private Optional<UUID> currentUnit() {
    OkrCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUnitId();
    }
    return subject().map(OpaInput.Subject::unitId).flatMap(OkrComposeController::uuid);
  }

  private Optional<OpaInput.Subject> subject() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    return Optional.ofNullable(resolver == null ? null : resolver.currentSubject());
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static <T> T found(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException | OkrComposeService.NotFound e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Đơn vị sở hữu nằm ngoài phạm vi được soạn");
  }
}

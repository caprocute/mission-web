package vn.viettel.okrkpi.okr.infrastructure.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.okr.model.OkrAlignmentEdge;
import vn.viettel.okrkpi.contract.okr.model.OkrAlignmentGraph;
import vn.viettel.okrkpi.contract.okr.model.OkrAlignmentNode;
import vn.viettel.okrkpi.contract.okr.model.OkrBasis;
import vn.viettel.okrkpi.contract.okr.model.OkrBasisKind;
import vn.viettel.okrkpi.contract.okr.model.OkrBasisPage;
import vn.viettel.okrkpi.contract.okr.model.OkrBasisRef;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckinFrequency;
import vn.viettel.okrkpi.contract.okr.model.OkrConfidence;
import vn.viettel.okrkpi.contract.okr.model.OkrContentSource;
import vn.viettel.okrkpi.contract.okr.model.OkrDirection;
import vn.viettel.okrkpi.contract.okr.model.OkrExportRequest;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResult;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResultListItem;
import vn.viettel.okrkpi.contract.okr.model.OkrKeyResultPage;
import vn.viettel.okrkpi.contract.okr.model.OkrLastCheckin;
import vn.viettel.okrkpi.contract.okr.model.OkrLink;
import vn.viettel.okrkpi.contract.okr.model.OkrLinkFromType;
import vn.viettel.okrkpi.contract.okr.model.OkrLinkKind;
import vn.viettel.okrkpi.contract.okr.model.OkrLinkPage;
import vn.viettel.okrkpi.contract.okr.model.OkrLinkToType;
import vn.viettel.okrkpi.contract.okr.model.OkrMetricType;
import vn.viettel.okrkpi.contract.okr.model.OkrObjective;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectivePage;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectivePageCounts;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveRef;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveStatus;
import vn.viettel.okrkpi.contract.okr.model.OkrOwnerType;
import vn.viettel.okrkpi.contract.okr.model.OkrPeerLink;
import vn.viettel.okrkpi.contract.okr.model.OkrPeriodInfo;
import vn.viettel.okrkpi.contract.okr.model.OkrRiskFlag;
import vn.viettel.okrkpi.contract.okr.model.OkrTree;
import vn.viettel.okrkpi.contract.okr.model.PeriodRef;
import vn.viettel.okrkpi.contract.okr.model.PeriodStatus;
import vn.viettel.okrkpi.contract.okr.model.Ref;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.OkrReadService.AlignmentNode;
import vn.viettel.okrkpi.okr.application.OkrReadService.AlignmentView;
import vn.viettel.okrkpi.okr.application.OkrReadService.KeyResultView;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrReadService.PeerLinkView;
import vn.viettel.okrkpi.okr.application.OkrReadService.PeriodView;
import vn.viettel.okrkpi.okr.application.OkrReadService.TreeView;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.BasisRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Phía đọc của {@code okr.yaml} cho QT-02/SCR-0201 (CORE-01-02): {@code getOkrTree}, {@code
 * listOkrObjectives}, {@code getOkrObjective}, {@code listOkrKeyResults}, {@code getOkrKeyResult},
 * {@code getOkrAlignment}, {@code listOkrLinks}, {@code listOkrBases}, {@code exportOkrObjectives}.
 * Phạm vi đơn vị kiểm ở lớp OPA dùng chung qua {@link OkrUnitScope}; tên đơn vị và người chưa có
 * nguồn trong MOD-01 nên trả rỗng.
 */
@RestController
@RequestMapping("/api/v1/okr")
public class OkrReadController {

  private static final String BASE = "/api/v1/okr";
  private static final String VIEW = "SCR-0201.xem";

  private final OkrReadService reads;
  private final CursorCodec cursors;
  private final OkrIdempotency idempotency;
  private final OkrExportJobs exports;
  private final ObjectProvider<OrgDirectoryApi> org;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OkrCaller> callers;

  public OkrReadController(
      OkrReadService reads,
      CursorCodec cursors,
      OkrIdempotency idempotency,
      OkrExportJobs exports,
      ObjectProvider<OrgDirectoryApi> org,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OkrCaller> callers) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.org = Objects.requireNonNull(org, "org");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping("/tree")
  @RequiresPermission(action = VIEW, operationId = "getOkrTree", module = "okr")
  public OkrTree getOkrTree(
      @RequestParam(value = "period", required = false) UUID period,
      @ModelAttribute OkrUnitScope scope) {
    TenantId tenant = TenantContext.require();
    PeriodSnapshot p = notFound(() -> reads.period(tenant, period));
    Optional<UUID> own = currentUnit();
    UUID unit = scope.unit() != null ? scope.unit() : own.orElseThrow(() -> missing("unit"));
    TreeView tree = reads.tree(tenant, p, unit, own.map(unit::equals).orElse(false));
    OkrPeriodInfo info = periodInfo(tree.period());
    return new OkrTree(
        info,
        List.of(info),
        new Ref(unit, ""),
        tree.viewerOwnUnit(),
        tree.bases().stream().map(OkrReadController::basisRef).toList(),
        tree.parent() == null ? null : objectiveRef(tree.parent()),
        tree.recommendedMax(),
        tree.objectives().stream().map(o -> objective(o, List.of())).toList(),
        tree.peerLinks().stream().map(OkrReadController::peerLink).toList());
  }

  @GetMapping("/objectives")
  @RequiresPermission(action = VIEW, operationId = "listOkrObjectives", module = "okr")
  public OkrObjectivePage listOkrObjectives(
      @RequestParam(value = "period", required = false) UUID period,
      @ModelAttribute OkrUnitScope scope,
      @RequestParam(value = "view", required = false) String view,
      @RequestParam(value = "ownerType", required = false) String ownerType,
      @RequestParam(value = "owner", required = false) String owner,
      @RequestParam(value = "status", required = false) List<String> status,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    if (view != null && !List.of("tree", "table", "map").contains(view)) {
      throw invalidFilter("view");
    }
    String ownerTypeValue =
        ownerType == null
            ? null
            : enumValue("ownerType", ownerType, OkrOwnerType::fromValue).getValue();
    List<String> statuses =
        status == null
            ? List.of()
            : status.stream()
                .map(s -> enumValue("status", s, OkrObjectiveStatus::fromValue).getValue())
                .toList();
    UUID person = me(owner);
    Optional<PeriodSnapshot> p = periodFilter(tenant, period);
    UUID unit =
        scope.unit() != null ? scope.unit() : person != null ? null : currentUnit().orElse(null);
    ObjectiveFilter filter =
        new ObjectiveFilter(
            p.map(x -> x.id().value()).orElse(null),
            p.map(OkrReadService::buckets).orElse(null),
            unit == null ? null : List.of(unit),
            ownerTypeValue,
            person,
            statuses,
            q);
    PageRequest page =
        cursors.pageRequest(
            limit,
            cursor,
            "okr-objectives:"
                + filter.periodId()
                + ":"
                + unit
                + ":"
                + ownerTypeValue
                + ":"
                + person
                + ":"
                + statuses
                + ":"
                + (q == null ? "" : q));
    List<ObjectiveView> fetched =
        reads.objectives(
            tenant,
            filter,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.afterKey().map(k -> k.getUuid(1)).orElse(null),
            page.fetchSize());
    CursorPage<ObjectiveView> result =
        cursors.page(fetched, page, o -> CursorKey.of(o.row().code(), o.row().id()));
    OkrObjectivePage dto =
        new OkrObjectivePage(
            result.items().stream().map(o -> objective(o, List.of())).toList(),
            result.nextCursor());
    if (p.isPresent() && unit != null) {
      dto.counts(
          new OkrObjectivePageCounts()
              .objectives((int) reads.countUnitObjectives(tenant, p.get(), unit))
              .recommendedMax(OkrReadService.RECOMMENDED_MAX));
    }
    return dto;
  }

  @GetMapping("/objectives/{id}")
  @RequiresPermission(action = VIEW, operationId = "getOkrObjective", module = "okr")
  public ResponseEntity<OkrObjective> getOkrObjective(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    ObjectiveView view =
        reads
            .objective(tenant, id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    OkrObjective dto = objective(view, reads.linksOf(tenant, view));
    if (view.row().parentObjectiveId() != null) {
      reads
          .objectiveRow(tenant, view.row().parentObjectiveId())
          .ifPresent(parent -> dto.parentObjective(objectiveRef(parent)));
    }
    reads
        .optionalPeriod(tenant, view.row().periodId())
        .ifPresent(
            p -> dto.period(new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate())));
    return ETags.ok(dto, view.row().rowVersion());
  }

  @GetMapping("/key-results")
  @RequiresPermission(action = VIEW, operationId = "listOkrKeyResults", module = "okr")
  public OkrKeyResultPage listOkrKeyResults(
      @RequestParam(value = "period", required = false) UUID period,
      @ModelAttribute OkrUnitScope unitScope,
      @RequestParam(value = "scope", required = false) String scope,
      @RequestParam(value = "owner", required = false) String owner,
      @RequestParam(value = "needCheckin", required = false) Boolean needCheckin,
      @RequestParam(value = "objective", required = false) UUID objective,
      @RequestParam(value = "riskFlag", required = false) String riskFlag,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    if (scope != null && !List.of("unit", "subtree").contains(scope)) {
      throw invalidFilter("scope");
    }
    String risk =
        riskFlag == null
            ? null
            : enumValue("riskFlag", riskFlag, OkrRiskFlag::fromValue).getValue();
    UUID person = me(owner);
    Optional<PeriodSnapshot> p = periodFilter(tenant, period);
    UUID unit =
        unitScope.unit() != null
            ? unitScope.unit()
            : person != null || objective != null ? null : currentUnit().orElse(null);
    List<UUID> units = unit == null ? null : units(tenant, unit, "subtree".equals(scope));
    boolean check = Boolean.TRUE.equals(needCheckin);
    KeyResultFilter filter =
        new KeyResultFilter(
            p.map(x -> x.id().value()).orElse(null),
            p.map(OkrReadService::buckets).orElse(null),
            units,
            person,
            objective,
            risk,
            false,
            check,
            q);
    PageRequest page =
        cursors.pageRequest(
            limit,
            cursor,
            "okr-key-results:"
                + filter.periodId()
                + ":"
                + units
                + ":"
                + person
                + ":"
                + objective
                + ":"
                + risk
                + ":"
                + check
                + ":"
                + (q == null ? "" : q));
    List<KeyResultView> fetched =
        reads.keyResults(
            tenant,
            filter,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.afterKey().map(k -> k.getUuid(1)).orElse(null),
            page.fetchSize());
    CursorPage<KeyResultView> result =
        cursors.page(fetched, page, k -> CursorKey.of(k.row().code(), k.row().id()));
    OkrKeyResultPage dto =
        new OkrKeyResultPage(
            result.items().stream().map(OkrReadController::listItem).toList(), result.nextCursor());
    p.ifPresent(x -> dto.period(periodInfo(reads.periodView(x))));
    return dto;
  }

  @GetMapping("/key-results/{id}")
  @RequiresPermission(action = VIEW, operationId = "getOkrKeyResult", module = "okr")
  public ResponseEntity<OkrKeyResult> getOkrKeyResult(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    KeyResultView view =
        reads
            .keyResult(tenant, id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    OkrKeyResult dto = keyResult(view);
    reads
        .objectiveRow(tenant, view.row().objectiveId())
        .ifPresent(
            o -> {
              dto.objective(objectiveRef(o));
              if (o.ownerPersonId() != null) {
                dto.owner(new Ref(o.ownerPersonId(), ""));
              }
              currentUser().ifPresent(me -> dto.viewerIsOwner(me.equals(o.ownerPersonId())));
            });
    if (view.row().lastCheckin() != null) {
      dto.latestBlocker(view.row().lastCheckin().blockerNote());
    }
    return ETags.ok(dto, view.row().rowVersion());
  }

  @GetMapping("/alignment")
  @RequiresPermission(action = VIEW, operationId = "getOkrAlignment", module = "okr")
  public OkrAlignmentGraph getOkrAlignment(
      @RequestParam(value = "objective", required = false) UUID objective,
      @RequestParam(value = "period", required = false) UUID period,
      @ModelAttribute OkrUnitScope scope,
      @RequestParam(value = "depth", required = false) Integer depth) {
    TenantId tenant = TenantContext.require();
    int levels = depth == null ? 3 : depth;
    if (levels < 1 || levels > 5) {
      throw invalidFilter("depth");
    }
    PeriodSnapshot p = null;
    UUID unit = null;
    if (objective == null) {
      p = periodFilter(tenant, period).orElse(null);
      unit = scope.unit() != null ? scope.unit() : currentUnit().orElse(null);
      if (p == null || unit == null) {
        throw missing(p == null ? "period" : "unit");
      }
    }
    PeriodSnapshot periodValue = p;
    UUID unitValue = unit;
    AlignmentView graph =
        notFound(() -> reads.alignment(tenant, objective, periodValue, unitValue, levels));
    OkrAlignmentGraph dto =
        new OkrAlignmentGraph(
            graph.nodes().stream().map(OkrReadController::node).toList(),
            graph.edges().stream().map(OkrReadController::edge).toList());
    dto.rootId(graph.rootId());
    return dto;
  }

  @GetMapping("/links")
  @RequiresPermission(action = VIEW, operationId = "listOkrLinks", module = "okr")
  public OkrLinkPage listOkrLinks(
      @RequestParam(value = "fromType", required = false) String fromType,
      @RequestParam(value = "fromId", required = false) UUID fromId,
      @RequestParam(value = "toType", required = false) String toType,
      @RequestParam(value = "toId", required = false) UUID toId,
      @RequestParam(value = "linkKind", required = false) String linkKind,
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    String from =
        fromType == null
            ? null
            : enumValue("fromType", fromType, OkrLinkFromType::fromValue).getValue();
    String to =
        toType == null ? null : enumValue("toType", toType, OkrLinkToType::fromValue).getValue();
    String kind =
        linkKind == null
            ? null
            : enumValue("linkKind", linkKind, OkrLinkKind::fromValue).getValue();
    PageRequest page =
        cursors.pageRequest(
            limit,
            cursor,
            "okr-links:" + from + ":" + fromId + ":" + to + ":" + toId + ":" + kind + ":" + period);
    List<LinkRow> fetched =
        reads.links(
            tenant,
            from,
            fromId,
            to,
            toId,
            kind,
            period,
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<LinkRow> result = cursors.page(fetched, page, l -> CursorKey.of(l.id()));
    return new OkrLinkPage(
        result.items().stream().map(OkrReadController::link).toList(), result.nextCursor());
  }

  @GetMapping("/bases")
  @RequiresPermission(action = VIEW, operationId = "listOkrBases", module = "okr")
  public OkrBasisPage listOkrBases(
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "validOn", required = false) LocalDate validOn,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    String kindValue =
        kind == null ? null : enumValue("kind", kind, OkrBasisKind::fromValue).getValue();
    PageRequest page =
        cursors.pageRequest(
            limit, cursor, "okr-bases:" + kindValue + ":" + validOn + ":" + (q == null ? "" : q));
    List<BasisRow> fetched =
        reads.bases(
            tenant,
            kindValue,
            validOn,
            q,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.afterKey().map(k -> k.getUuid(1)).orElse(null),
            page.fetchSize());
    CursorPage<BasisRow> result = cursors.page(fetched, page, b -> CursorKey.of(b.title(), b.id()));
    return new OkrBasisPage(
        result.items().stream().map(OkrReadController::basis).toList(), result.nextCursor());
  }

  @PostMapping("/objectives/export")
  @RequiresPermission(action = VIEW, operationId = "exportOkrObjectives", module = "okr")
  public ResponseEntity<Job> exportOkrObjectives(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OkrExportRequest body) {
    TenantId tenant = TenantContext.require();
    UUID actor = currentUser().orElseThrow(OkrReadController::unauthorized);
    if (body.getPeriodId() == null || body.getUnitId() == null) {
      throw missing(body.getPeriodId() == null ? "periodId" : "unitId");
    }
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/objectives/export",
        body,
        Job.class,
        j -> 0,
        () -> Jobs.accepted(exports.start(tenant, actor)));
  }

  // ---------------------------------------------------------------- người gọi, phạm vi

  private UUID me(String owner) {
    if (owner == null) {
      return null;
    }
    if (!"me".equals(owner)) {
      throw invalidFilter("owner");
    }
    return currentUser().orElseThrow(OkrReadController::unauthorized);
  }

  private Optional<UUID> currentUser() {
    OkrCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId();
    }
    return subject().map(OpaInput.Subject::userId).flatMap(OkrReadController::uuid);
  }

  private Optional<UUID> currentUnit() {
    OkrCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.currentUnitId();
    }
    return subject().map(OpaInput.Subject::unitId).flatMap(OkrReadController::uuid);
  }

  private Optional<OpaInput.Subject> subject() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    return Optional.ofNullable(resolver == null ? null : resolver.currentSubject());
  }

  /**
   * Đơn vị, kèm cấp dưới theo cả cây chuyên môn và cây Đảng khi {@code scope=subtree} (suy luận).
   */
  private List<UUID> units(TenantId tenant, UUID unit, boolean subtree) {
    Set<UUID> all = new LinkedHashSet<>();
    all.add(unit);
    OrgDirectoryApi directory = subtree ? org.getIfAvailable() : null;
    if (directory != null) {
      for (OrgTree tree : OrgTree.values()) {
        all.addAll(directory.descendantsOf(tenant, tree, unit, Integer.MAX_VALUE));
      }
    }
    return new ArrayList<>(all);
  }

  private Optional<PeriodSnapshot> periodFilter(TenantId tenant, UUID period) {
    if (period == null) {
      return reads.optionalPeriod(tenant, null);
    }
    return Optional.of(notFound(() -> reads.period(tenant, period)));
  }

  // ---------------------------------------------------------------- ánh xạ hợp đồng

  static OkrPeriodInfo periodInfo(PeriodView v) {
    PeriodSnapshot p = v.period();
    return new OkrPeriodInfo(
            p.id().value(),
            p.name(),
            p.startDate(),
            p.endDate(),
            PeriodStatus.fromValue(p.status().name()))
        .weekNo(v.weekNo())
        .weekCount(v.weekCount());
  }

  static OkrObjective objective(ObjectiveView v, List<LinkRow> links) {
    ObjectiveRow o = v.row();
    OkrObjective dto =
        new OkrObjective(
                o.id(),
                o.periodId(),
                OkrOwnerType.fromValue(o.ownerType()),
                new Ref(o.ownerUnitId(), ""),
                o.code(),
                o.title(),
                OkrObjectiveStatus.fromValue(o.status()),
                v.progress(),
                o.versionNo(),
                (int) o.rowVersion())
            .ownerPerson(o.ownerPersonId() == null ? null : new Ref(o.ownerPersonId(), ""))
            .description(o.description())
            .tier(o.tier())
            .bases(v.bases().stream().map(OkrReadController::basisRef).toList())
            .overLimitReason(o.overLimitReason())
            .overLimit(o.overLimitReason() != null)
            .aiAssisted(o.aiAssisted())
            .aiSuggestionId(o.aiSuggestionId())
            .approvedBy(o.approvedBy() == null ? null : new Ref(o.approvedBy(), ""))
            .approvedAt(time(o.approvedAt()))
            .keyResults(v.keyResults().stream().map(OkrReadController::keyResult).toList())
            .peerLinks(links.stream().map(OkrReadController::link).toList())
            .createdBy(o.createdBy() == null ? null : new Ref(o.createdBy(), ""))
            .createdAt(time(o.createdAt()))
            .updatedAt(time(o.updatedAt()));
    if (o.parentObjectiveId() != null) {
      dto.parentObjective(new OkrObjectiveRef(o.parentObjectiveId(), "", ""));
    }
    return dto;
  }

  static OkrObjectiveRef objectiveRef(ObjectiveRow o) {
    return new OkrObjectiveRef(o.id(), o.code(), o.title())
        .status(OkrObjectiveStatus.fromValue(o.status()));
  }

  static OkrKeyResult keyResult(KeyResultView v) {
    KeyResultRow k = v.row();
    return new OkrKeyResult(
            k.id(),
            k.objectiveId(),
            k.code(),
            k.title(),
            k.unit(),
            k.targetValue(),
            k.weight(),
            k.dueDate(),
            v.progress(),
            OkrObjectiveStatus.fromValue(k.status()),
            OkrRiskFlag.fromValue(k.riskFlag()),
            (int) k.rowVersion())
        .periodId(k.periodId())
        .metricType(k.metricType() == null ? null : OkrMetricType.fromValue(k.metricType()))
        .baselineValue(k.baselineValue())
        .currentValue(k.currentValue())
        .direction(k.direction() == null ? null : OkrDirection.fromValue(k.direction()))
        .verifier(k.verifierPersonId() == null ? null : new Ref(k.verifierPersonId(), ""))
        .dataSource(k.dataSource())
        .formulaText(k.formulaText())
        .confidence(k.confidence() == null ? null : OkrConfidence.fromValue(k.confidence()))
        .checkinFrequency(
            k.checkinFrequency() == null
                ? null
                : OkrCheckinFrequency.fromValue(k.checkinFrequency()))
        .contentSource(
            k.contentSource() == null ? null : OkrContentSource.fromValue(k.contentSource()))
        .aiAssisted(k.aiAssisted())
        .aiSuggestionId(k.aiSuggestionId())
        .lastCheckin(lastCheckin(k));
  }

  private static OkrKeyResultListItem listItem(KeyResultView v) {
    KeyResultRow k = v.row();
    return new OkrKeyResultListItem(k.id(), k.code(), k.title())
        .objectiveId(k.objectiveId())
        .unit(k.unit())
        .targetValue(k.targetValue())
        .currentValue(k.currentValue())
        .progress(v.progress())
        .confidence(k.confidence() == null ? null : OkrConfidence.fromValue(k.confidence()))
        .riskFlag(k.riskFlag() == null ? null : OkrRiskFlag.fromValue(k.riskFlag()))
        .dueDate(k.dueDate())
        .lastCheckin(lastCheckin(k));
  }

  private static OkrLastCheckin lastCheckin(KeyResultRow k) {
    if (k.lastCheckin() == null || k.lastCheckin().value() == null) {
      return null;
    }
    return new OkrLastCheckin(
        k.lastCheckin().value(),
        k.lastCheckin().confidence() == null
            ? OkrConfidence.TRUNG_BINH
            : OkrConfidence.fromValue(k.lastCheckin().confidence()),
        time(k.lastCheckin().checkinAt()));
  }

  private static OkrPeerLink peerLink(PeerLinkView v) {
    String fromLabel =
        v.localKeyResult() != null
            ? v.localKeyResult().code()
            : v.localObjective() == null ? "" : v.localObjective().code();
    return new OkrPeerLink(v.link().id(), fromLabel, "", v.peer().code(), v.peer().title())
        .fromKeyResultId(v.localKeyResult() == null ? null : v.localKeyResult().id());
  }

  static OkrLink link(LinkRow l) {
    return new OkrLink(
            l.id(),
            OkrLinkFromType.fromValue(l.fromType()),
            l.fromId(),
            OkrLinkToType.fromValue(l.toType()),
            l.toId(),
            OkrLinkKind.fromValue(l.linkKind()))
        .periodId(l.periodId())
        .contributionWeight(l.contributionWeight())
        .rowVersion((int) l.rowVersion());
  }

  private static OkrAlignmentNode node(AlignmentNode n) {
    OkrAlignmentNode.TypeEnum type = OkrAlignmentNode.TypeEnum.fromValue(n.type());
    if (n.objective() != null) {
      ObjectiveRow o = n.objective().row();
      return new OkrAlignmentNode(type, o.id(), o.title())
          .code(o.code())
          .status(o.status())
          .progress(n.objective().progress());
    }
    if (n.keyResult() != null) {
      KeyResultRow k = n.keyResult().row();
      return new OkrAlignmentNode(type, k.id(), k.title())
          .code(k.code())
          .status(k.status())
          .progress(n.keyResult().progress())
          .confidence(k.confidence() == null ? null : OkrConfidence.fromValue(k.confidence()));
    }
    BasisRow b = n.basis();
    return new OkrAlignmentNode(type, b.id(), b.title()).code(b.docNumber());
  }

  private static OkrAlignmentEdge edge(LinkRow l) {
    return new OkrAlignmentEdge(
            OkrLinkFromType.fromValue(l.fromType()),
            l.fromId(),
            OkrLinkToType.fromValue(l.toType()),
            l.toId(),
            OkrLinkKind.fromValue(l.linkKind()))
        .contributionWeight(l.contributionWeight());
  }

  static OkrBasisRef basisRef(BasisRow b) {
    return new OkrBasisRef(b.id(), b.title())
        .basisKind(OkrBasisKind.fromValue(b.basisKind()))
        .docNumber(b.docNumber());
  }

  private static OkrBasis basis(BasisRow b) {
    return new OkrBasis(b.id(), OkrBasisKind.fromValue(b.basisKind()), b.title())
        .docNumber(b.docNumber())
        .issuedByUnit(b.issuedByUnitId() == null ? null : new Ref(b.issuedByUnitId(), ""))
        .issuedOn(b.issuedOn())
        .sourceDocRef(b.sourceDocRef())
        .parentBasisId(b.parentBasisId())
        .validFrom(b.validFrom())
        .validTo(b.validTo());
  }

  // ---------------------------------------------------------------- tiện ích

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
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

  private static <E> E enumValue(String field, String value, Function<String, E> parse) {
    try {
      return parse.apply(value);
    } catch (IllegalArgumentException e) {
      throw invalidFilter(field);
    }
  }

  private static BusinessException invalidFilter(String field) {
    String message = "Giá trị lọc không hợp lệ";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  static BusinessException missing(String field) {
    String message = "Thiếu thông tin bắt buộc";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}

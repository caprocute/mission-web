package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import vn.viettel.okrkpi.contract.tasks.model.CatalogItem;
import vn.viettel.okrkpi.contract.tasks.model.CatalogItemInput;
import vn.viettel.okrkpi.contract.tasks.model.CatalogItemSaveResult;
import vn.viettel.okrkpi.contract.tasks.model.CatalogItemStatus;
import vn.viettel.okrkpi.contract.tasks.model.CatalogItemsPage;
import vn.viettel.okrkpi.contract.tasks.model.ListApplicableCatalogItems200Response;
import vn.viettel.okrkpi.contract.tasks.model.ListCatalogPositions200Response;
import vn.viettel.okrkpi.contract.tasks.model.ListWorkCatalogs200Response;
import vn.viettel.okrkpi.contract.tasks.model.Position;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.contract.tasks.model.WorkCatalog;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;
import vn.viettel.okrkpi.task.application.catalog.CatalogService.CatalogSummary;
import vn.viettel.okrkpi.task.application.catalog.CatalogService.ItemView;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.ItemScoring;

/**
 * Danh mục công việc (CORE-02-03; QT-04 → SCR-0401, SCR-0501, SCR-0503 → {@code tasks.yaml} nhóm
 * {@code /catalog} → MOD-02 → {@code task.work_catalog}, {@code task.catalog_item}). Nhập Excel
 * thuộc CORE-02-04. Người gọi là {@link Principal} của yêu cầu (claim {@code sub}), như {@code
 * Idempotent}.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

  private static final String BASE = "/api/v1/catalog";

  private final CatalogService catalogs;
  private final CursorCodec cursors;

  public CatalogController(CatalogService catalogs, CursorCodec cursors) {
    this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping
  @RequiresPermission(
      action = "SCR-0401.xem",
      operationId = "listWorkCatalogs",
      module = "catalog",
      resourceType = "work_catalog")
  public ListWorkCatalogs200Response listWorkCatalogs(
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, "catalogs:" + unit);
    CatalogRepository.CatalogKey after =
        page.afterKey()
            .map(
                k ->
                    new CatalogRepository.CatalogKey(
                        (int) k.getLong(0), k.getString(1), (int) k.getLong(2), k.getUuid(3)))
            .orElse(null);
    List<CatalogSummary> fetched = catalogs.listCatalogs(tenant, unit, after, page.fetchSize());
    CursorPage<CatalogSummary> result =
        cursors.page(
            fetched,
            page,
            s ->
                CursorKey.of(
                    CatalogRepository.rank(s.catalog().status()),
                    s.catalog().name(),
                    s.catalog().version(),
                    s.catalog().id()));
    return new ListWorkCatalogs200Response(
        result.items().stream().map(s -> toDto(tenant, s)).toList(), result.nextCursor());
  }

  @GetMapping("/items")
  // Chức năng xem danh mục của ma trận là SCR-0401.xem (phạm vi đơn vị); SCR-0503.xem là "Việc của
  // tôi" với phạm vi
  // bản thân nên mục danh mục, vốn không có người sở hữu, luôn bị từ chối.
  @RequiresPermission(
      action = "SCR-0401.xem",
      operationId = "listApplicableCatalogItems",
      module = "catalog",
      resourceType = "catalog_item")
  public ListApplicableCatalogItems200Response listApplicableCatalogItems(
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "applicable", required = false) String applicable,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor,
      Principal principal) {
    TenantId tenant = TenantContext.require();
    boolean me = "me".equals(applicable);
    PageRequest page =
        cursors.pageRequest(limit, cursor, "catalog-items:" + unit + ":" + me + ":" + q);
    String afterCode = page.afterKey().map(k -> k.getString(0)).orElse(null);
    UUID afterId = page.afterKey().map(k -> k.getUuid(1)).orElse(null);
    List<ItemView> fetched =
        catalogs.applicableItems(
            tenant,
            me ? caller(principal) : null,
            unit,
            me,
            q,
            afterCode,
            afterId,
            page.fetchSize());
    CursorPage<ItemView> result =
        cursors.page(fetched, page, v -> CursorKey.of(v.item().code(), v.item().id()));
    return new ListApplicableCatalogItems200Response(
        result.items().stream().map(CatalogController::toDto).toList(), result.nextCursor());
  }

  @GetMapping("/{catalogId}/items")
  @RequiresPermission(
      action = "SCR-0401.xem",
      operationId = "listCatalogVersionItems",
      module = "catalog",
      resourceType = "catalog_item")
  public CatalogItemsPage listCatalogVersionItems(
      @PathVariable("catalogId") UUID catalogId,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "position", required = false) UUID position,
      @RequestParam(value = "difficulty", required = false) Integer difficulty,
      @RequestParam(value = "status", required = false) CatalogItemStatus status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page =
        cursors.pageRequest(
            limit,
            cursor,
            "catalog:" + catalogId + ":" + q + ":" + position + ":" + difficulty + ":" + status);
    String afterCode = page.afterKey().map(k -> k.getString(0)).orElse(null);
    UUID afterId = page.afterKey().map(k -> k.getUuid(1)).orElse(null);
    CatalogService.ItemsPage items =
        notFoundAs404(
            () ->
                catalogs.versionItems(
                    tenant,
                    catalogId,
                    new CatalogService.ItemFilter(
                        q, position, difficulty, status == null ? null : status.getValue()),
                    afterCode,
                    afterId,
                    page.fetchSize()));
    CursorPage<ItemView> result =
        cursors.page(items.fetched(), page, v -> CursorKey.of(v.item().code(), v.item().id()));
    return new CatalogItemsPage(
        result.items().stream().map(CatalogController::toDto).toList(),
        result.nextCursor(),
        items.total(),
        items.positionCount());
  }

  @PostMapping("/{catalogId}/items")
  @Idempotent
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "addCatalogItem",
      module = "catalog",
      resourceType = "catalog_item")
  public ResponseEntity<CatalogItemSaveResult> addCatalogItem(
      @PathVariable("catalogId") UUID catalogId,
      @RequestBody CatalogItemInput body,
      Principal principal) {
    TenantId tenant = TenantContext.require();
    CatalogService.SaveResult saved =
        notFoundAs404(() -> catalogs.addItem(tenant, caller(principal), catalogId, draft(body)));
    CatalogItemSaveResult dto = toDto(tenant, saved);
    return ResponseEntity.created(
            URI.create(
                BASE + "/" + saved.catalog().catalog().id() + "/items/" + dto.getItem().getId()))
        .eTag(ETags.of(saved.item().item().rowVersion()))
        .body(dto);
  }

  @PutMapping("/{catalogId}/items/{itemId}")
  @Idempotent
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "updateCatalogItem",
      module = "catalog",
      resourceType = "catalog_item")
  public ResponseEntity<CatalogItemSaveResult> updateCatalogItem(
      @PathVariable("catalogId") UUID catalogId,
      @PathVariable("itemId") UUID itemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody CatalogItemInput body,
      Principal principal) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    CatalogService.SaveResult saved =
        notFoundAs404(
            () ->
                catalogs.reviseItem(
                    tenant, caller(principal), catalogId, itemId, expected, draft(body)));
    return ETags.ok(toDto(tenant, saved), saved.item().item().rowVersion());
  }

  @GetMapping("/{catalogId}/positions")
  @RequiresPermission(
      action = "SCR-0401.xem",
      operationId = "listCatalogPositions",
      module = "catalog",
      resourceType = "work_catalog")
  public ListCatalogPositions200Response listCatalogPositions(
      @PathVariable("catalogId") UUID catalogId) {
    TenantId tenant = TenantContext.require();
    Map<UUID, String> positions = notFoundAs404(() -> catalogs.positions(tenant, catalogId));
    return new ListCatalogPositions200Response(
        positions.entrySet().stream().map(e -> new Position(e.getKey(), e.getValue())).toList());
  }

  @PostMapping("/{catalogId}/versions")
  @Idempotent
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "cloneCatalogVersion",
      module = "catalog",
      resourceType = "work_catalog")
  public ResponseEntity<WorkCatalog> cloneCatalogVersion(
      @PathVariable("catalogId") UUID catalogId, Principal principal) {
    TenantId tenant = TenantContext.require();
    CatalogSummary created =
        notFoundAs404(() -> catalogs.cloneVersion(tenant, caller(principal), catalogId));
    return ResponseEntity.created(URI.create(BASE + "/" + created.catalog().id() + "/items"))
        .eTag(ETags.of(created.catalog().rowVersion()))
        .body(toDto(tenant, created));
  }

  @PostMapping("/{catalogId}/activate")
  @Idempotent
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "activateCatalogVersion",
      module = "catalog",
      resourceType = "work_catalog")
  public ResponseEntity<WorkCatalog> activateCatalogVersion(
      @PathVariable("catalogId") UUID catalogId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      Principal principal) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    CatalogSummary activated =
        notFoundAs404(() -> catalogs.activate(tenant, caller(principal), catalogId, expected));
    return ETags.ok(toDto(tenant, activated), activated.catalog().rowVersion());
  }

  // ---------------------------------------------------------------- ánh xạ

  private CatalogItemSaveResult toDto(TenantId tenant, CatalogService.SaveResult saved) {
    return new CatalogItemSaveResult(
        toDto(saved.item()), toDto(tenant, saved.catalog()), saved.movedToNewVersion());
  }

  private WorkCatalog toDto(TenantId tenant, CatalogSummary s) {
    WorkCatalog dto =
        new WorkCatalog(
            s.catalog().id(),
            s.catalog().name(),
            s.catalog().version(),
            CatalogStatusMapper.toContract(s.catalog().status()),
            s.itemCount(),
            s.positionCount(),
            s.catalog().rowVersion(),
            byLevel(catalogs.difficultyFactors(tenant)),
            byLevel(catalogs.scoreSuggestion(tenant)));
    dto.setParentCatalogName(s.parentName());
    return dto;
  }

  static CatalogItem toDto(ItemView v) {
    vn.viettel.okrkpi.task.domain.catalog.CatalogItem i = v.item();
    CatalogItem dto =
        new CatalogItem(
            i.id(),
            i.code(),
            i.name(),
            i.productName(),
            i.unit(),
            i.difficulty(),
            i.baseScore(),
            i.qualityCriteria(),
            TaskGroup.fromValue(i.group()));
    dto.setCatalogId(i.catalogId());
    dto.setPositionId(v.visible().effectivePositionId());
    dto.setPositionName(v.positionName());
    dto.setEvidenceRequired(i.evidenceRequired());
    dto.setStandardDays(i.standardDays());
    dto.setStatus(CatalogItemStatus.fromValue(i.status()));
    dto.setUsageCount(v.usage().count());
    dto.setUsagePeriods(v.usage().periods());
    dto.setUsedInLockedPeriod(v.usage().usedInLockedPeriod());
    dto.setRowVersion(i.rowVersion());
    dto.setCoefficientLevels(new java.util.LinkedHashMap<>(i.scoring().coefficientLevels()));
    dto.setAxisCode(i.scoring().axisCode());
    dto.setScoreAdjustMin(i.scoring().scoreAdjustMin());
    dto.setScoreAdjustMax(i.scoring().scoreAdjustMax());
    return dto;
  }

  private static Map<String, BigDecimal> byLevel(Map<Integer, BigDecimal> values) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    values.forEach((level, value) -> out.put(String.valueOf(level), value));
    return out;
  }

  private static CatalogItemDraft draft(CatalogItemInput in) {
    return new CatalogItemDraft(
        in.getCode(),
        in.getPositionId(),
        in.getName(),
        in.getProductName(),
        in.getUnit(),
        in.getDifficulty(),
        in.getBaseScore(),
        in.getQualityCriteria(),
        in.getEvidenceRequired(),
        in.getDefaultGroup() == null ? null : in.getDefaultGroup().getValue(),
        in.getStandardDays(),
        in.getStatus() == null ? null : in.getStatus().getValue(),
        new ItemScoring(
            in.getCoefficientLevels(),
            in.getAxisCode(),
            in.getScoreAdjustMin(),
            in.getScoreAdjustMax()));
  }

  static UUID caller(Principal principal) {
    if (principal == null || principal.getName() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
    }
    try {
      return UUID.fromString(principal.getName().trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
    }
  }

  private static <T> T notFoundAs404(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}

package vn.viettel.okrkpi.task.application.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.catalog.AccentFold;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/** Bản giả trong bộ nhớ của {@link CatalogRepository} cho kiểm thử đơn vị (một tenant). */
class InMemoryCatalogRepository implements CatalogRepository {

  final Map<UUID, WorkCatalog> catalogs = new LinkedHashMap<>();
  final Map<UUID, CatalogItem> items = new LinkedHashMap<>();
  final List<PeriodUse> uses = new ArrayList<>();
  boolean failNextCatalogInsert;
  boolean failNextItemWrite;
  boolean failNextStatusChange;

  private static final Comparator<VisibleItem> BY_CODE =
      Comparator.comparing((VisibleItem v) -> v.item().code()).thenComparing(v -> v.item().id());

  @Override
  public Optional<WorkCatalog> findCatalog(TenantId tenant, UUID id) {
    return Optional.ofNullable(catalogs.get(id));
  }

  @Override
  public List<WorkCatalog> listCatalogs(
      TenantId tenant, UUID orgUnit, CatalogKey after, int fetch) {
    return catalogs.values().stream()
        .filter(c -> orgUnit == null || orgUnit.equals(c.orgUnitId()))
        .sorted(
            Comparator.comparingInt((WorkCatalog c) -> CatalogRepository.rank(c.status()))
                .thenComparing(WorkCatalog::name)
                .thenComparing(c -> -c.version())
                .thenComparing(WorkCatalog::id))
        .limit(fetch)
        .toList();
  }

  @Override
  public Optional<WorkCatalog> findLatest(TenantId tenant, String code, CatalogStatus status) {
    return catalogs.values().stream()
        .filter(c -> c.code().equals(code) && c.status() == status)
        .max(Comparator.comparingInt(WorkCatalog::version));
  }

  @Override
  public int maxVersion(TenantId tenant, String code) {
    return catalogs.values().stream()
        .filter(c -> c.code().equals(code))
        .mapToInt(WorkCatalog::version)
        .max()
        .orElse(0);
  }

  @Override
  public void insertCatalog(TenantId tenant, UUID actor, WorkCatalog catalog) {
    if (failNextCatalogInsert) {
      failNextCatalogInsert = false;
      throw new DuplicateVersionException(null);
    }
    catalogs.put(catalog.id(), catalog);
  }

  @Override
  public boolean changeStatus(
      TenantId tenant, UUID actor, UUID id, CatalogStatus status, int expectedRowVersion) {
    WorkCatalog c = catalogs.get(id);
    if (failNextStatusChange || c.rowVersion() != expectedRowVersion) {
      failNextStatusChange = false;
      return false;
    }
    catalogs.put(id, with(c, status, c.parentId()));
    return true;
  }

  @Override
  public int replaceStatus(
      TenantId tenant, UUID actor, String code, CatalogStatus from, CatalogStatus to) {
    int n = 0;
    for (WorkCatalog c : List.copyOf(catalogs.values())) {
      if (c.code().equals(code) && c.status() == from) {
        catalogs.put(c.id(), with(c, to, c.parentId()));
        n++;
      }
    }
    return n;
  }

  @Override
  public void changeParent(TenantId tenant, UUID actor, UUID id, UUID parentId) {
    WorkCatalog c = catalogs.get(id);
    catalogs.put(id, with(c, c.status(), parentId));
  }

  @Override
  public Optional<UUID> parentOf(TenantId tenant, UUID id) {
    return Optional.ofNullable(catalogs.get(id)).map(WorkCatalog::parentId);
  }

  @Override
  public Map<UUID, String> catalogNames(TenantId tenant, Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    ids.forEach(id -> out.put(id, catalogs.get(id).name()));
    return out;
  }

  @Override
  public Map<UUID, String> positionLabels(TenantId tenant, Collection<UUID> positionIds) {
    Map<UUID, String> out = new HashMap<>();
    for (WorkCatalog c : catalogs.values()) {
      if (c.positionId() != null && positionIds.contains(c.positionId())) {
        out.putIfAbsent(c.positionId(), c.name());
      }
    }
    return out;
  }

  @Override
  public List<VisibleItem> visibleItems(TenantId tenant, UUID catalogId) {
    WorkCatalog root = catalogs.get(catalogId);
    Map<String, VisibleItem> byCode = new LinkedHashMap<>();
    UUID cursor = catalogId;
    int depth = 0;
    while (cursor != null && depth++ < 16) {
      for (CatalogItem i : items.values()) {
        if (i.catalogId().equals(cursor)) {
          byCode.putIfAbsent(
              i.code(),
              new VisibleItem(
                  i, catalogId, i.positionId() != null ? i.positionId() : root.positionId()));
        }
      }
      cursor = catalogs.get(cursor).parentId();
    }
    return byCode.values().stream().sorted(BY_CODE).toList();
  }

  @Override
  public List<VisibleItem> applicableItems(
      TenantId tenant,
      UUID orgUnit,
      Set<UUID> positions,
      String foldedQuery,
      String afterCode,
      UUID afterId,
      int fetch) {
    Map<UUID, VisibleItem> out = new LinkedHashMap<>();
    for (WorkCatalog c : catalogs.values()) {
      if (c.status() != CatalogStatus.HIEU_LUC
          || (orgUnit != null && !orgUnit.equals(c.orgUnitId()))) {
        continue;
      }
      for (VisibleItem v : visibleItems(tenant, c.id())) {
        if ("AP_DUNG".equals(v.item().status())
            && (positions == null || positions.contains(v.effectivePositionId()))
            && (foldedQuery == null || AccentFold.fold(v.item().name()).contains(foldedQuery))) {
          out.putIfAbsent(v.item().id(), v);
        }
      }
    }
    return out.values().stream()
        .sorted(BY_CODE)
        .filter(
            v ->
                afterCode == null
                    || v.item().code().compareTo(afterCode) > 0
                    || (v.item().code().equals(afterCode) && v.item().id().compareTo(afterId) > 0))
        .limit(fetch)
        .toList();
  }

  @Override
  public Optional<CatalogItem> findItem(TenantId tenant, UUID id) {
    return Optional.ofNullable(items.get(id));
  }

  @Override
  public Optional<CatalogItem> findItemByCode(TenantId tenant, UUID catalogId, String code) {
    return items.values().stream()
        .filter(i -> i.catalogId().equals(catalogId) && i.code().equals(code))
        .findFirst();
  }

  @Override
  public List<String> itemCodes(TenantId tenant, UUID catalogId) {
    return items.values().stream()
        .filter(i -> i.catalogId().equals(catalogId))
        .map(CatalogItem::code)
        .toList();
  }

  @Override
  public void insertItem(TenantId tenant, UUID actor, CatalogItem item) {
    if (failNextItemWrite) {
      failNextItemWrite = false;
      throw new DuplicateCodeException(null);
    }
    items.put(item.id(), item);
  }

  @Override
  public boolean reviseItem(TenantId tenant, UUID actor, CatalogItem item, int expectedRowVersion) {
    if (failNextItemWrite) {
      failNextItemWrite = false;
      throw new DuplicateCodeException(null);
    }
    CatalogItem current = items.get(item.id());
    if (current.rowVersion() != expectedRowVersion) {
      return false;
    }
    items.put(
        item.id(),
        new CatalogItem(
            item.id(),
            item.catalogId(),
            item.code(),
            item.name(),
            item.productName(),
            item.unit(),
            item.difficulty(),
            item.baseScore(),
            item.qualityCriteria(),
            item.evidenceRequired(),
            item.group(),
            item.positionId(),
            item.standardDays(),
            item.status(),
            current.rowVersion() + 1));
    return true;
  }

  @Override
  public int copyItems(TenantId tenant, UUID actor, UUID from, UUID to) {
    int n = 0;
    for (CatalogItem i : List.copyOf(items.values())) {
      if (i.catalogId().equals(from)) {
        CatalogItem copy =
            new CatalogItem(
                UUID.randomUUID(),
                to,
                i.code(),
                i.name(),
                i.productName(),
                i.unit(),
                i.difficulty(),
                i.baseScore(),
                i.qualityCriteria(),
                i.evidenceRequired(),
                i.group(),
                i.positionId(),
                i.standardDays(),
                i.status(),
                0);
        items.put(copy.id(), copy);
        n++;
      }
    }
    return n;
  }

  @Override
  public List<PeriodUse> usage(TenantId tenant, Collection<UUID> itemIds) {
    return uses.stream().filter(u -> itemIds.contains(u.itemId())).toList();
  }

  private static WorkCatalog with(WorkCatalog c, CatalogStatus status, UUID parent) {
    return new WorkCatalog(
        c.id(),
        c.code(),
        c.name(),
        c.scope(),
        c.positionId(),
        c.orgUnitId(),
        c.version(),
        c.source(),
        status,
        parent,
        c.rowVersion() + 1);
  }
}

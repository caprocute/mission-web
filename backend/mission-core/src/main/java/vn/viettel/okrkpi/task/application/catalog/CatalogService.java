package vn.viettel.okrkpi.task.application.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository.CatalogKey;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository.VisibleItem;
import vn.viettel.okrkpi.task.domain.catalog.AccentFold;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogRules;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.CatalogUsage;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/**
 * Ca sử dụng danh mục công việc theo vị trí việc làm (CORE-02-03; QT-04 → SCR-0401; BR-04-13,
 * BR-04-14; MSG-04-01).
 *
 * <ul>
 *   <li>Phiên bản nháp được sửa tại chỗ. Phiên bản đang áp dụng được sửa tại chỗ khi mục chưa dùng
 *       ở kỳ đã khoá; mục đã dùng ở kỳ đã khoá thì thay đổi được ghi vào phiên bản nháp kế tiếp
 *       (tạo bằng nhân bản nếu chưa có) và trả {@code movedToNewVersion = true}, đúng mô tả {@code
 *       updateCatalogItem} của hợp đồng. Thêm mục vào phiên bản đang áp dụng cũng ghi vào phiên bản
 *       nháp. Phiên bản đã ngừng chỉ xem.
 *   <li>Mục kế thừa từ danh mục cấp trên chỉ đọc ở danh mục con.
 * </ul>
 *
 * <p>Danh mục là dữ liệu của tenant, không thuộc kỳ, nên lệnh ghi không gọi {@code
 * PeriodApi.assertWritable}; kỳ đã khoá được bảo toàn bằng BR-04-13 ở trên.
 */
public class CatalogService {

  static final String TX = "taskTransactionManager";
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Phiên bản kèm số liệu để trả {@code WorkCatalog}. */
  public record CatalogSummary(
      WorkCatalog catalog, int itemCount, int positionCount, String parentName) {}

  /** Mục kèm mức sử dụng và nhãn vị trí. */
  public record ItemView(VisibleItem visible, CatalogUsage usage, String positionName) {
    public CatalogItem item() {
      return visible.item();
    }
  }

  /** Một trang mục (đọc dư một dòng để biết còn trang sau), tổng theo bộ lọc và số vị trí. */
  public record ItemsPage(List<ItemView> fetched, int total, int positionCount) {}

  /** Bộ lọc của {@code listCatalogVersionItems}. */
  public record ItemFilter(String q, UUID position, Integer difficulty, String status) {}

  /** Kết quả thêm hoặc sửa mục. */
  public record SaveResult(ItemView item, CatalogSummary catalog, boolean movedToNewVersion) {}

  private final CatalogRepository repository;
  private final CatalogPolicy policy;
  private final PeriodApi periods;
  private final OrgDirectoryApi directory;

  public CatalogService(
      CatalogRepository repository,
      CatalogPolicy policy,
      PeriodApi periods,
      OrgDirectoryApi directory) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  // ---------------------------------------------------------------- đọc

  /** {@code listWorkCatalogs}. */
  @Transactional(value = TX, readOnly = true)
  public List<CatalogSummary> listCatalogs(
      TenantId tenant, UUID orgUnit, CatalogKey after, int fetch) {
    return repository.listCatalogs(tenant, orgUnit, after, fetch).stream()
        .map(c -> summarize(tenant, c))
        .toList();
  }

  /** {@code listCatalogVersionItems}: lọc trong bộ nhớ trên các mục nhìn thấy của một phiên bản. */
  @Transactional(value = TX, readOnly = true)
  public ItemsPage versionItems(
      TenantId tenant,
      UUID catalogId,
      ItemFilter filter,
      String afterCode,
      UUID afterId,
      int fetch) {
    WorkCatalog catalog = catalog(tenant, catalogId);
    List<VisibleItem> visible = repository.visibleItems(tenant, catalog.id());
    String q = AccentFold.fold(blankToNull(filter.q()));
    List<VisibleItem> matched =
        visible.stream()
            .filter(
                v ->
                    q == null
                        || AccentFold.fold(v.item().name()).contains(q)
                        || AccentFold.fold(v.item().code()).contains(q))
            .filter(
                v -> filter.position() == null || filter.position().equals(v.effectivePositionId()))
            .filter(
                v -> filter.difficulty() == null || filter.difficulty() == v.item().difficulty())
            .filter(v -> filter.status() == null || filter.status().equals(v.item().status()))
            .toList();
    List<VisibleItem> page =
        matched.stream()
            .filter(v -> afterCode == null || after(v.item(), afterCode, afterId))
            .limit(fetch)
            .toList();
    return new ItemsPage(describe(tenant, page, catalog), matched.size(), positionCount(visible));
  }

  /** {@code listApplicableCatalogItems}; {@code me} lọc theo vị trí việc làm của người gọi. */
  @Transactional(value = TX, readOnly = true)
  public List<ItemView> applicableItems(
      TenantId tenant,
      UUID caller,
      UUID orgUnit,
      boolean me,
      String q,
      String afterCode,
      UUID afterId,
      int fetch) {
    Set<UUID> positions = null;
    if (me) {
      positions =
          directory.assignmentsOf(tenant, caller, LocalDate.now(ZONE)).stream()
              .map(AssignmentView::positionId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      if (positions.isEmpty()) {
        return List.of();
      }
    }
    List<VisibleItem> items =
        repository.applicableItems(
            tenant, orgUnit, positions, AccentFold.fold(blankToNull(q)), afterCode, afterId, fetch);
    return describe(tenant, items, null);
  }

  /** {@code listCatalogPositions}: vị trí việc làm có trong các mục nhìn thấy của phiên bản. */
  @Transactional(value = TX, readOnly = true)
  public Map<UUID, String> positions(TenantId tenant, UUID catalogId) {
    WorkCatalog catalog = catalog(tenant, catalogId);
    Set<UUID> ids = new HashSet<>();
    for (VisibleItem v : repository.visibleItems(tenant, catalog.id())) {
      if (v.effectivePositionId() != null) {
        ids.add(v.effectivePositionId());
      }
    }
    if (ids.isEmpty() && catalog.positionId() != null) {
      ids.add(catalog.positionId());
    }
    Map<UUID, String> labels = repository.positionLabels(tenant, ids);
    return ids.stream()
        .map(id -> Map.entry(id, labels.getOrDefault(id, catalog.name())))
        .sorted(
            Map.Entry.<UUID, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /** Phiên bản kèm số liệu cho phản hồi. */
  @Transactional(value = TX, readOnly = true)
  public CatalogSummary summary(TenantId tenant, UUID catalogId) {
    return summarize(tenant, catalog(tenant, catalogId));
  }

  /** Hệ số mức khó (khoá "1".."5") cho {@code WorkCatalog.difficultyFactors}. */
  public Map<Integer, BigDecimal> difficultyFactors(TenantId tenant) {
    Map<Integer, BigDecimal> factors = policy.difficultyFactors(tenant);
    Map<Integer, BigDecimal> out = new LinkedHashMap<>();
    for (int level = 1; level <= 5; level++) {
      out.put(level, factors.getOrDefault(level, BigDecimal.ONE));
    }
    return out;
  }

  /** Điểm gợi ý theo mức khó cho {@code WorkCatalog.scoreSuggestion}. */
  public Map<Integer, BigDecimal> scoreSuggestion(TenantId tenant) {
    return CatalogRules.scoreSuggestion(
        policy.basePerLevel(tenant), policy.difficultyFactors(tenant));
  }

  // ---------------------------------------------------------------- ghi

  /** {@code addCatalogItem} (BR-04-14). */
  @Transactional(TX)
  public SaveResult addItem(TenantId tenant, UUID actor, UUID catalogId, CatalogItemDraft input) {
    WorkCatalog viewed = catalog(tenant, catalogId);
    CatalogItemDraft draft = checked(tenant, input);
    WorkCatalog target = writable(tenant, actor, viewed);
    String code =
        draft.code() != null ? draft.code() : CatalogRules.nextCode(visibleCodes(tenant, target));
    if (repository.findItemByCode(tenant, target.id(), code).isPresent()) {
      throw duplicateCode(code);
    }
    CatalogItem item = CatalogItem.create(Ids.newUuidV7(), target.id(), code, draft);
    try {
      repository.insertItem(tenant, actor, item);
    } catch (CatalogRepository.DuplicateCodeException e) {
      throw duplicateCode(code);
    }
    return saved(tenant, target, item, !target.id().equals(viewed.id()));
  }

  /** {@code updateCatalogItem} (BR-04-13, BR-04-14, MSG-04-01). */
  @Transactional(TX)
  public SaveResult reviseItem(
      TenantId tenant,
      UUID actor,
      UUID catalogId,
      UUID itemId,
      long expectedRowVersion,
      CatalogItemDraft input) {
    WorkCatalog viewed = catalog(tenant, catalogId);
    CatalogItem current =
        repository
            .findItem(tenant, itemId)
            .filter(i -> visibleFrom(tenant, viewed, i))
            .orElseThrow(() -> new NoSuchElementException("Không có mục " + itemId));
    if (!current.catalogId().equals(viewed.id())) {
      throw new BusinessException(
          "TASK-409-NOT-DRAFT",
          "Công việc " + current.code() + " thuộc danh mục mẫu cấp trên, chỉ xem ở danh mục này.");
    }
    if (current.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("TASK", expectedRowVersion, (long) current.rowVersion());
    }
    CatalogItemDraft draft = checked(tenant, input);
    String code = draft.code() != null ? draft.code() : current.code();

    boolean lockedUse =
        viewed.status() == CatalogStatus.HIEU_LUC
            && usageOf(tenant, List.of(current.id())).get(current.id()).usedInLockedPeriod();
    if (viewed.status() == CatalogStatus.NGUNG) {
      throw notEditable(viewed);
    }
    if (!lockedUse) {
      return revise(tenant, actor, viewed, current, code, draft, false);
    }
    WorkCatalog target = draftOf(tenant, actor, viewed);
    Optional<CatalogItem> copy = repository.findItemByCode(tenant, target.id(), current.code());
    if (copy.isPresent()) {
      return revise(tenant, actor, target, copy.get(), code, draft, true);
    }
    CatalogItem item = CatalogItem.create(Ids.newUuidV7(), target.id(), code, draft);
    try {
      repository.insertItem(tenant, actor, item);
    } catch (CatalogRepository.DuplicateCodeException e) {
      throw duplicateCode(code);
    }
    return saved(tenant, target, item, true);
  }

  /** {@code cloneCatalogVersion}: phiên bản n+1 ở nháp, sao chép đủ mục, giữ mã. */
  @Transactional(TX)
  public CatalogSummary cloneVersion(TenantId tenant, UUID actor, UUID catalogId) {
    WorkCatalog source = catalog(tenant, catalogId);
    Optional<WorkCatalog> draft = repository.findLatest(tenant, source.code(), CatalogStatus.NHAP);
    if (draft.isPresent()) {
      throw new BusinessException(
          "TASK-409-INVALID-TRANSITION",
          "Danh mục đã có phiên bản nháp "
              + draft.get().version()
              + ". Hãy sửa hoặc áp dụng phiên bản đó trước khi nhân bản tiếp.");
    }
    return summarize(tenant, cloneOf(tenant, actor, source));
  }

  /**
   * {@code activateCatalogVersion}: nháp sang áp dụng, phiên bản đang áp dụng trước đó của cùng
   * danh mục sang ngừng (QT-04 bước 1).
   */
  @Transactional(TX)
  public CatalogSummary activate(
      TenantId tenant, UUID actor, UUID catalogId, long expectedRowVersion) {
    WorkCatalog catalog = catalog(tenant, catalogId);
    if (catalog.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("TASK", expectedRowVersion, (long) catalog.rowVersion());
    }
    if (catalog.status() != CatalogStatus.NHAP) {
      throw new BusinessException(
          "TASK-409-INVALID-TRANSITION", "Chỉ phiên bản nháp mới được áp dụng.");
    }
    repository.replaceStatus(
        tenant, actor, catalog.code(), CatalogStatus.HIEU_LUC, CatalogStatus.NGUNG);
    if (!repository.changeStatus(
        tenant, actor, catalog.id(), CatalogStatus.HIEU_LUC, catalog.rowVersion())) {
      throw new VersionConflictException("TASK", expectedRowVersion, null);
    }
    return summary(tenant, catalog.id());
  }

  /**
   * Gắn phiên bản vào danh mục mẫu cấp trên (SCR-0401 trường 2), chặn vòng kế thừa. Hợp đồng chưa
   * có thao tác riêng; dùng cho nhập Excel (CORE-02-04) và dữ liệu khởi tạo.
   */
  @Transactional(TX)
  public CatalogSummary inheritFrom(TenantId tenant, UUID actor, UUID catalogId, UUID parentId) {
    WorkCatalog catalog = catalog(tenant, catalogId);
    if (parentId != null) {
      catalog(tenant, parentId);
      try {
        CatalogRules.requireAcyclic(catalog.id(), parentId, id -> repository.parentOf(tenant, id));
      } catch (CatalogViolationException e) {
        throw invalid(e);
      }
    }
    repository.changeParent(tenant, actor, catalog.id(), parentId);
    return summary(tenant, catalog.id());
  }

  // ---------------------------------------------------------------- nội bộ

  private SaveResult revise(
      TenantId tenant,
      UUID actor,
      WorkCatalog target,
      CatalogItem current,
      String code,
      CatalogItemDraft draft,
      boolean moved) {
    if (!code.equals(current.code())
        && repository.findItemByCode(tenant, target.id(), code).isPresent()) {
      throw duplicateCode(code);
    }
    CatalogItem next = current.revise(code, draft);
    boolean ok;
    try {
      ok = repository.reviseItem(tenant, actor, next, current.rowVersion());
    } catch (CatalogRepository.DuplicateCodeException e) {
      throw duplicateCode(code);
    }
    if (!ok) {
      throw new VersionConflictException("TASK", (long) current.rowVersion(), null);
    }
    CatalogItem stored = repository.findItem(tenant, current.id()).orElseThrow();
    return saved(tenant, target, stored, moved);
  }

  /**
   * Phiên bản nhận mục mới của {@code catalogId} (nhập Excel, CORE-02-04): chính nó khi nháp, nháp
   * kế tiếp khi đang áp dụng; phiên bản ngừng áp dụng thì 409.
   */
  @Transactional(TX)
  public WorkCatalog writableVersion(TenantId tenant, UUID actor, UUID catalogId) {
    return writable(tenant, actor, catalog(tenant, catalogId));
  }

  /** Phiên bản nhận mục mới: chính nó khi nháp, nháp kế tiếp khi đang áp dụng. */
  private WorkCatalog writable(TenantId tenant, UUID actor, WorkCatalog viewed) {
    return switch (viewed.status()) {
      case NHAP -> viewed;
      case HIEU_LUC -> draftOf(tenant, actor, viewed);
      case NGUNG -> throw notEditable(viewed);
    };
  }

  private WorkCatalog draftOf(TenantId tenant, UUID actor, WorkCatalog applied) {
    return repository
        .findLatest(tenant, applied.code(), CatalogStatus.NHAP)
        .orElseGet(() -> cloneOf(tenant, actor, applied));
  }

  private WorkCatalog cloneOf(TenantId tenant, UUID actor, WorkCatalog source) {
    WorkCatalog next =
        source.nextDraft(Ids.newUuidV7(), repository.maxVersion(tenant, source.code()) + 1);
    try {
      repository.insertCatalog(tenant, actor, next);
    } catch (CatalogRepository.DuplicateVersionException e) {
      throw new BusinessException(
          "TASK-409-INVALID-TRANSITION",
          "Phiên bản " + next.version() + " vừa được tạo bởi thao tác khác. Hãy tải lại danh mục.");
    }
    repository.copyItems(tenant, actor, source.id(), next.id());
    return next;
  }

  private SaveResult saved(TenantId tenant, WorkCatalog target, CatalogItem item, boolean moved) {
    VisibleItem visible =
        new VisibleItem(
            item, target.id(), item.positionId() != null ? item.positionId() : target.positionId());
    ItemView view = describe(tenant, List.of(visible), target).get(0);
    return new SaveResult(view, summarize(tenant, target), moved);
  }

  private CatalogItemDraft checked(TenantId tenant, CatalogItemDraft input) {
    CatalogItemDraft draft = CatalogRules.normalize(input);
    try {
      CatalogRules.validate(draft, policy.vagueWords(tenant));
    } catch (CatalogViolationException e) {
      throw invalid(e);
    }
    return draft;
  }

  private List<ItemView> describe(TenantId tenant, List<VisibleItem> items, WorkCatalog catalog) {
    if (items.isEmpty()) {
      return List.of();
    }
    Map<UUID, CatalogUsage> usage =
        usageOf(tenant, items.stream().map(v -> v.item().id()).toList());
    Set<UUID> positionIds = new HashSet<>();
    items.forEach(
        v -> {
          if (v.effectivePositionId() != null) {
            positionIds.add(v.effectivePositionId());
          }
        });
    Map<UUID, String> labels = repository.positionLabels(tenant, positionIds);
    List<ItemView> out = new ArrayList<>(items.size());
    for (VisibleItem v : items) {
      UUID pos = v.effectivePositionId();
      String label = pos == null ? null : labels.get(pos);
      if (label == null && pos != null && catalog != null) {
        label = catalog.name();
      }
      out.add(new ItemView(v, usage.getOrDefault(v.item().id(), CatalogUsage.NONE), label));
    }
    return out;
  }

  private Map<UUID, CatalogUsage> usageOf(TenantId tenant, Collection<UUID> itemIds) {
    Map<UUID, Integer> counts = new HashMap<>();
    Map<UUID, Set<UUID>> periodsByItem = new HashMap<>();
    for (CatalogRepository.PeriodUse use : repository.usage(tenant, itemIds)) {
      counts.merge(use.itemId(), use.count(), Integer::sum);
      periodsByItem.computeIfAbsent(use.itemId(), k -> new HashSet<>()).add(use.periodId());
    }
    Map<UUID, Boolean> locked = new HashMap<>();
    Map<UUID, CatalogUsage> out = new HashMap<>();
    for (UUID id : itemIds) {
      Set<UUID> used = periodsByItem.getOrDefault(id, Set.of());
      boolean anyLocked = false;
      for (UUID period : used) {
        anyLocked |=
            locked.computeIfAbsent(
                period,
                p ->
                    periods
                        .findById(tenant, PeriodId.of(p))
                        .map(PeriodSnapshot::isLocked)
                        .orElse(false));
      }
      out.put(id, new CatalogUsage(counts.getOrDefault(id, 0), used.size(), anyLocked));
    }
    return out;
  }

  private CatalogSummary summarize(TenantId tenant, WorkCatalog catalog) {
    List<VisibleItem> visible = repository.visibleItems(tenant, catalog.id());
    String parentName =
        catalog.parentId() == null
            ? null
            : repository.catalogNames(tenant, List.of(catalog.parentId())).get(catalog.parentId());
    int positions = positionCount(visible);
    if (positions == 0 && catalog.positionId() != null) {
      positions = 1;
    }
    return new CatalogSummary(catalog, visible.size(), positions, parentName);
  }

  private static int positionCount(List<VisibleItem> visible) {
    return (int)
        visible.stream()
            .map(VisibleItem::effectivePositionId)
            .filter(Objects::nonNull)
            .distinct()
            .count();
  }

  private boolean visibleFrom(TenantId tenant, WorkCatalog viewed, CatalogItem item) {
    if (item.catalogId().equals(viewed.id())) {
      return true;
    }
    return repository.visibleItems(tenant, viewed.id()).stream()
        .anyMatch(v -> v.item().id().equals(item.id()));
  }

  private List<String> visibleCodes(TenantId tenant, WorkCatalog catalog) {
    List<String> codes = new ArrayList<>(repository.itemCodes(tenant, catalog.id()));
    repository.visibleItems(tenant, catalog.id()).forEach(v -> codes.add(v.item().code()));
    return codes;
  }

  private WorkCatalog catalog(TenantId tenant, UUID id) {
    return repository
        .findCatalog(tenant, id)
        .orElseThrow(() -> new NoSuchElementException("Không có danh mục " + id));
  }

  private static boolean after(CatalogItem item, String afterCode, UUID afterId) {
    int c = Comparator.<String>naturalOrder().compare(item.code(), afterCode);
    return c > 0 || (c == 0 && item.id().compareTo(afterId) > 0);
  }

  private static BusinessException notEditable(WorkCatalog catalog) {
    return new BusinessException(
        "TASK-409-NOT-DRAFT",
        "Phiên bản " + catalog.version() + " đã ngừng áp dụng, chỉ xem. Hãy sửa ở phiên bản mới.");
  }

  private static BusinessException duplicateCode(String code) {
    return new BusinessException(
        "COMMON-422-VALIDATION",
        "Mã công việc " + code + " đã có trong danh mục.",
        List.of(ProblemFieldError.of("code", "Mã công việc đã có trong danh mục.")));
  }

  private static BusinessException invalid(CatalogViolationException e) {
    return new BusinessException(
        "COMMON-422-VALIDATION",
        e.getMessage(),
        e.violations().stream().map(v -> ProblemFieldError.of(v.field(), v.message())).toList());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}

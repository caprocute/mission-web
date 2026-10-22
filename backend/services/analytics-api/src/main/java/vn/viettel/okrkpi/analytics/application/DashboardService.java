package vn.viettel.okrkpi.analytics.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.AnalyticsRequest;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardDraft;
import vn.viettel.okrkpi.analytics.domain.DashboardPage;
import vn.viettel.okrkpi.analytics.domain.DashboardWidget;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.analytics.domain.WidgetData;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;

/**
 * Dashboard tự dựng (SCR-1602, BR-16-02): kiểm từng widget theo lớp ngữ nghĩa rồi lưu định nghĩa ở
 * MOD-08; khi xem, số liệu từng widget chạy qua {@link QueryService} trong phạm vi của người xem,
 * nên dashboard được chia sẻ không làm lộ số liệu ngoài phạm vi của người nhận.
 */
public class DashboardService {

  /** Một dashboard cùng số liệu (khi được yêu cầu). */
  public record View(Dashboard dashboard, WidgetData data) {}

  private final DashboardStorePort store;
  private final ScopeService scopes;
  private final SemanticModelService models;
  private final QueryService queries;
  private final ShareNotifier notifier;

  public DashboardService(
      DashboardStorePort store,
      ScopeService scopes,
      SemanticModelService models,
      QueryService queries,
      ShareNotifier notifier) {
    this.store = store;
    this.scopes = scopes;
    this.models = models;
    this.queries = queries;
    this.notifier = notifier;
  }

  public DashboardPage list(Viewer viewer, boolean mine, int limit, String cursor) {
    Scope scope = scopes.resolve(viewer, null);
    return store.list(viewer, scope.viewerUnits(), mine, limit, cursor);
  }

  public Dashboard create(
      Viewer viewer, String title, List<DashboardWidget> layout, String idempotencyKey) {
    SemanticCatalog catalog = models.current(scopes.resolve(viewer, null));
    validateLayout(catalog, layout);
    DashboardDraft draft =
        new DashboardDraft(title, layout, DashboardDraft.RIENG, List.of(), catalog.version());
    return store.create(viewer, draft, idempotencyKey);
  }

  public Dashboard update(
      Viewer viewer,
      UUID id,
      String title,
      List<DashboardWidget> layout,
      String idempotencyKey,
      String ifMatch) {
    requireIfMatch(ifMatch);
    Scope scope = scopes.resolve(viewer, null);
    SemanticCatalog catalog = models.current(scope);
    validateLayout(catalog, layout);
    Dashboard current = store.get(viewer, scope.viewerUnits(), id);
    DashboardDraft draft =
        new DashboardDraft(
            title, layout, current.shareScope(), current.sharedUnitIds(), catalog.version());
    return store.replace(viewer, id, draft, idempotencyKey, ifMatch);
  }

  public Dashboard share(
      Viewer viewer,
      UUID id,
      String shareScope,
      List<UUID> sharedUnitIds,
      String idempotencyKey,
      String ifMatch) {
    requireIfMatch(ifMatch);
    List<UUID> units = sharedUnitIds == null ? List.of() : sharedUnitIds;
    if (DashboardDraft.DON_VI.equals(shareScope) && units.isEmpty()) {
      throw AnalyticsErrors.validation("sharedUnitIds", "Cần chọn ít nhất một đơn vị được chia sẻ");
    }
    if (!DashboardDraft.DON_VI.equals(shareScope)) {
      units = List.of();
    }
    Scope scope = scopes.resolve(viewer, null);
    Dashboard current = store.get(viewer, scope.viewerUnits(), id);
    String version =
        current.semanticModelVersion() != null
            ? current.semanticModelVersion()
            : models.current(scope).version();
    DashboardDraft draft =
        new DashboardDraft(current.title(), current.layout(), shareScope, units, version);
    Dashboard saved = store.replace(viewer, id, draft, idempotencyKey, ifMatch);
    notifier.shared(viewer, saved);
    return saved;
  }

  public View get(Viewer viewer, UUID id, UUID periodId, UUID unitId, boolean includeData) {
    Scope scope = scopes.resolve(viewer, null);
    Dashboard dashboard = store.get(viewer, scope.viewerUnits(), id);
    if (!includeData) {
      return new View(dashboard, null);
    }
    Instant asOf = null;
    boolean stale = false;
    List<WidgetData.Item> items = new ArrayList<>();
    for (DashboardWidget w : dashboard.layout()) {
      AnalyticsRequest request =
          new AnalyticsRequest(
              List.of(w.measure()),
              w.dimensions(),
              w.filters(),
              periodId,
              unitId,
              null,
              false,
              AnalyticsRequest.DEFAULT_LIMIT);
      QueryResult r;
      try {
        r = queries.query(viewer, request);
      } catch (BusinessException e) {
        // Widget lưu theo phiên bản lớp ngữ nghĩa cũ (chỉ số đã bỏ) hoặc không lọc được theo kỳ:
        // widget đó không có số liệu, các widget khác vẫn hiện (suy luận). Lỗi phạm vi vẫn ném.
        if (!AnalyticsErrors.INVALID_COMBINATION.equals(e.errorCode().code())) {
          throw e;
        }
        items.add(new WidgetData.Item(w.id(), List.of(), List.of()));
        continue;
      }
      items.add(new WidgetData.Item(w.id(), r.columns(), r.rows()));
      asOf = asOf == null || r.dataAsOf().isBefore(asOf) ? r.dataAsOf() : asOf;
      stale = stale || r.stale();
    }
    return new View(dashboard, new WidgetData(asOf, stale, items));
  }

  static void validateLayout(SemanticCatalog catalog, List<DashboardWidget> layout) {
    if (layout == null || layout.isEmpty() || layout.size() > DashboardDraft.MAX_WIDGETS) {
      throw AnalyticsErrors.validation("layout", "Dashboard có từ 1 đến 20 widget");
    }
    List<SemanticCatalog.Violation> violations = new ArrayList<>();
    for (int i = 0; i < layout.size(); i++) {
      DashboardWidget w = layout.get(i);
      violations.addAll(
          catalog.check("layout[" + i + "].", List.of(w.measure()), w.dimensions(), w.filters()));
    }
    AnalyticsErrors.raise(violations);
  }

  private static void requireIfMatch(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new PreconditionRequiredException();
    }
  }
}

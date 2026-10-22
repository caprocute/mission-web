package vn.viettel.okrkpi.analytics.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;

/** Cổng giả cho kiểm thử đơn vị tầng ứng dụng. */
final class Fakes {

  static final UUID TENANT = UUID.fromString("0192f000-0000-7000-8000-00000000a001");
  static final UUID UNIT_TINH = UUID.fromString("0192f000-0000-7000-8000-0000000000e1");
  static final UUID UNIT_HUYEN = UUID.fromString("0192f000-0000-7000-8000-0000000000e2");
  static final UUID UNIT_KHAC = UUID.fromString("0192f000-0000-7000-8000-0000000000e3");
  static final UUID KY_Q4 = UUID.fromString("0192f000-0000-7000-8000-0000000000a4");
  static final UUID KY_Q3 = UUID.fromString("0192f000-0000-7000-8000-0000000000a3");

  private Fakes() {}

  static SemanticCatalog catalog() {
    Map<String, SemanticCatalog.Measure> ms = new LinkedHashMap<>();
    ms.put(
        "okr.tien_do_o",
        new SemanticCatalog.Measure("okr.tien_do_o", "Tiến độ mục tiêu O", "number", "%", false));
    ms.put(
        "okr.so_muc_tieu",
        new SemanticCatalog.Measure("okr.so_muc_tieu", "Số mục tiêu O", "count", null, false));
    ms.put(
        "task.so_nhiem_vu",
        new SemanticCatalog.Measure("task.so_nhiem_vu", "Số nhiệm vụ", "count", null, true));
    ms.put(
        QueryService.FRESHNESS_MEMBER,
        new SemanticCatalog.Measure(
            QueryService.FRESHNESS_MEMBER, "Số liệu cập nhật lúc", "max", null, false));
    Set<String> okr = Set.of("okr.tien_do_o", "okr.so_muc_tieu");
    Set<String> all = Set.of("okr.tien_do_o", "okr.so_muc_tieu", "task.so_nhiem_vu");
    Map<String, SemanticCatalog.Dimension> ds = new LinkedHashMap<>();
    ds.put(
        "org_unit.ten", new SemanticCatalog.Dimension("org_unit.ten", "Tên đơn vị", "string", all));
    ds.put(
        "period.id", new SemanticCatalog.Dimension("period.id", "Mã định danh kỳ", "string", okr));
    ds.put(
        "okr.chu_the",
        new SemanticCatalog.Dimension("okr.chu_the", "Chủ thể mục tiêu", "string", okr));
    ds.put(
        "task.han",
        new SemanticCatalog.Dimension(
            "task.han", "Hạn hoàn thành", "time", Set.of("task.so_nhiem_vu")));
    return new SemanticCatalog("2026.4", ms, ds);
  }

  /** Cube giả: ghi lại truy vấn và phạm vi, trả dòng theo hàm cho trước. */
  static final class Cube implements CubePort {
    final List<Map<String, Object>> queries = new ArrayList<>();
    final List<String> paths = new ArrayList<>();
    BiFunction<Scope, Map<String, Object>, List<Map<String, Object>>> rows = (s, q) -> List.of();
    Long freshnessMs = System.currentTimeMillis();
    int modelCalls;

    @Override
    public SemanticCatalog model(Scope scope) {
      modelCalls++;
      return catalog();
    }

    @Override
    public List<Map<String, Object>> load(Scope scope, Map<String, Object> query) {
      if (query.get("measures") instanceof List<?> m && m.contains(QueryService.FRESHNESS_MEMBER)) {
        Map<String, Object> r = new HashMap<>();
        r.put(
            QueryService.FRESHNESS_MEMBER,
            freshnessMs == null ? null : String.valueOf(freshnessMs));
        return List.of(r);
      }
      queries.add(query);
      paths.add(scope.orgPath());
      return rows.apply(scope, query);
    }
  }

  static final class Org implements OrgScopePort {
    final Map<UUID, List<String>> paths = new HashMap<>();
    final Map<UUID, UUID> previous = new HashMap<>();

    Org() {
      paths.put(UNIT_TINH, List.of("/t01"));
      paths.put(UNIT_HUYEN, List.of("/t01/h01", "/cm01/h01"));
      paths.put(UNIT_KHAC, List.of("/t02"));
      previous.put(KY_Q4, KY_Q3);
    }

    @Override
    public List<String> paths(UUID tenant, UUID unit) {
      return paths.getOrDefault(unit, List.of());
    }

    @Override
    public List<UUID> unitAndAncestors(UUID tenant, UUID unit, int limit) {
      return UNIT_HUYEN.equals(unit) ? List.of(UNIT_HUYEN, UNIT_TINH) : List.of(unit);
    }

    @Override
    public Optional<UUID> previousPeriod(UUID tenant, UUID period) {
      return Optional.ofNullable(previous.get(period));
    }
  }

  static final class Cache implements ResultCache {
    final Map<String, QueryResult> entries = new HashMap<>();

    @Override
    public Optional<QueryResult> get(UUID tenant, List<String> key) {
      return Optional.ofNullable(entries.get(tenant + ":" + key));
    }

    @Override
    public void put(UUID tenant, List<String> key, QueryResult result, Duration ttl) {
      entries.put(tenant + ":" + key, result);
    }
  }
}

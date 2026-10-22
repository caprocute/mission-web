package vn.viettel.okrkpi.analytics.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.AnalyticsRequest;
import vn.viettel.okrkpi.analytics.domain.Column;
import vn.viettel.okrkpi.analytics.domain.Filter;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * {@code queryAnalytics} (QT-16, BR-16-01, BR-16-02, BR-16-08): kiểm truy vấn theo lớp ngữ nghĩa,
 * dựng phạm vi của người xem, chạy trên Cube, đệm kết quả theo tenant và phạm vi.
 *
 * <ul>
 *   <li>{@code periodId} lọc chiều {@code period.id}; {@code unitId} thu hẹp phạm vi về nhánh của
 *       đơn vị đó (phải nằm trong phạm vi người xem);
 *   <li>{@code timeGranularity} áp cho chiều kiểu thời gian trong {@code dimensions} (suy luận);
 *   <li>{@code compareToPreviousPeriod} chạy thêm truy vấn ở kỳ liền trước cùng loại, cùng luồng và
 *       thêm cột {@code <chỉ số>.previous} cho từng chỉ số;
 *   <li>{@code dataAsOf} là {@code data_freshness.cap_nhat_luc}; {@code stale} khi cũ hơn ngưỡng (5
 *       phút, MSG-16-01).
 * </ul>
 *
 * Khoá đệm gồm phiên bản lớp ngữ nghĩa, nhánh phạm vi và toàn bộ truy vấn, nên hai người xem khác
 * phạm vi không bao giờ dùng chung một kết quả.
 */
public class QueryService {

  public static final String PERIOD_MEMBER = "period.id";
  public static final String FRESHNESS_MEMBER = "data_freshness.cap_nhat_luc";
  public static final String PREVIOUS_SUFFIX = ".previous";

  private final ScopeService scopes;
  private final SemanticModelService models;
  private final CubePort cube;
  private final OrgScopePort org;
  private final ResultCache cache;
  private final Duration ttl;
  private final Duration staleAfter;
  private final Clock clock;

  public QueryService(
      ScopeService scopes,
      SemanticModelService models,
      CubePort cube,
      OrgScopePort org,
      ResultCache cache,
      Duration ttl,
      Duration staleAfter,
      Clock clock) {
    this.scopes = scopes;
    this.models = models;
    this.cube = cube;
    this.org = org;
    this.cache = cache;
    this.ttl = ttl;
    this.staleAfter = staleAfter;
    this.clock = clock;
  }

  public QueryResult query(Viewer viewer, AnalyticsRequest request) {
    Scope scope = scopes.resolve(viewer, request.unitId());
    SemanticCatalog catalog = models.current(scope);
    validate(catalog, request);
    List<String> key = cacheKey(catalog.version(), scope.orgPath(), request);
    Optional<QueryResult> hit = cache.get(scope.tenant(), key);
    if (hit.isPresent()) {
      return hit.get();
    }
    QueryResult result = execute(scope, catalog, request);
    cache.put(scope.tenant(), key, result, ttl);
    return result;
  }

  /** Kiểm tên, tổ hợp và các tham số phụ thuộc nhau; ném lỗi 422. */
  public static void validate(SemanticCatalog catalog, AnalyticsRequest request) {
    List<SemanticCatalog.Violation> violations =
        new ArrayList<>(
            catalog.check("", request.measures(), request.dimensions(), request.filters()));
    if (request.periodId() != null) {
      SemanticCatalog.Dimension period = catalog.dimensions().get(PERIOD_MEMBER);
      if (period == null || !period.supportedMeasures().containsAll(request.measures())) {
        violations.add(
            new SemanticCatalog.Violation(
                "periodId", "Chỉ số đã chọn không lọc được theo kỳ", true));
      }
    }
    AnalyticsErrors.raise(violations);
    if (request.compareToPreviousPeriod() && request.periodId() == null) {
      throw AnalyticsErrors.validation(
          "compareToPreviousPeriod", "Cần chọn kỳ để so sánh với kỳ trước");
    }
    if (request.timeGranularity() != null
        && request.dimensions().stream().noneMatch(d -> isTime(catalog, d))) {
      throw AnalyticsErrors.validation("timeGranularity", "Cần chọn một chiều thời gian");
    }
  }

  private QueryResult execute(Scope scope, SemanticCatalog catalog, AnalyticsRequest request) {
    List<String> keys = memberKeys(catalog, request);
    List<Map<String, Object>> rows = cube.load(scope, cubeQuery(catalog, request));
    List<Column> columns = new ArrayList<>(columns(catalog, request));
    List<List<Object>> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      out.add(values(catalog, request, keys, row));
    }
    if (request.compareToPreviousPeriod()) {
      Optional<UUID> previous = org.previousPeriod(scope.tenant(), request.periodId());
      Map<List<Object>, List<Object>> before = new HashMap<>();
      if (previous.isPresent()) {
        AnalyticsRequest prior = request.withPeriod(previous.get());
        for (Map<String, Object> row : cube.load(scope, cubeQuery(catalog, prior))) {
          List<Object> v = values(catalog, prior, keys, row);
          int d = request.dimensions().size();
          before.put(new ArrayList<>(v.subList(0, d)), new ArrayList<>(v.subList(d, v.size())));
        }
      }
      for (String m : request.measures()) {
        SemanticCatalog.Measure def = catalog.measures().get(m);
        columns.add(
            new Column(
                m + PREVIOUS_SUFFIX, def.title() + " (kỳ trước)", columnType(def), def.unit()));
      }
      int d = request.dimensions().size();
      for (List<Object> row : out) {
        List<Object> prior = before.get(new ArrayList<>(row.subList(0, d)));
        for (int i = 0; i < request.measures().size(); i++) {
          row.add(prior == null ? null : prior.get(i));
        }
      }
    }
    Instant asOf = freshness(scope);
    boolean stale = asOf == null || asOf.isBefore(clock.instant().minus(staleAfter));
    return new QueryResult(
        catalog.version(), asOf == null ? clock.instant() : asOf, stale, columns, out);
  }

  /**
   * Thời điểm dữ liệu mới nhất của tenant. Chưa có dữ liệu thì trả {@code null}: kết quả mang thời
   * điểm hiện tại và đánh dấu {@code stale} (suy luận).
   */
  Instant freshness(Scope scope) {
    List<Map<String, Object>> rows =
        cube.load(scope, Map.of("measures", List.of(FRESHNESS_MEMBER)));
    if (rows.isEmpty()) {
      return null;
    }
    Object v = rows.get(0).get(FRESHNESS_MEMBER);
    BigDecimal ms = number(v);
    return ms == null ? null : Instant.ofEpochMilli(ms.longValue());
  }

  static Map<String, Object> cubeQuery(SemanticCatalog catalog, AnalyticsRequest request) {
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("measures", request.measures());
    List<String> dimensions = new ArrayList<>();
    List<Map<String, Object>> time = new ArrayList<>();
    for (String d : request.dimensions()) {
      if (request.timeGranularity() != null && isTime(catalog, d)) {
        time.add(Map.of("dimension", d, "granularity", request.timeGranularity()));
      } else {
        dimensions.add(d);
      }
    }
    q.put("dimensions", dimensions);
    if (!time.isEmpty()) {
      q.put("timeDimensions", time);
    }
    List<Map<String, Object>> filters = new ArrayList<>();
    for (Filter f : request.filters()) {
      filters.add(
          Map.of(
              "member",
              f.member(),
              "operator",
              "in".equals(f.operator()) ? "equals" : f.operator(),
              "values",
              f.values().stream().map(String::valueOf).toList()));
    }
    if (request.periodId() != null) {
      filters.add(
          Map.of(
              "member",
              PERIOD_MEMBER,
              "operator",
              "equals",
              "values",
              List.of(request.periodId().toString())));
    }
    if (!filters.isEmpty()) {
      q.put("filters", filters);
    }
    q.put("limit", request.limit());
    return q;
  }

  private static List<String> memberKeys(SemanticCatalog catalog, AnalyticsRequest request) {
    List<String> keys = new ArrayList<>();
    for (String d : request.dimensions()) {
      keys.add(
          request.timeGranularity() != null && isTime(catalog, d)
              ? d + "." + request.timeGranularity()
              : d);
    }
    keys.addAll(request.measures());
    return keys;
  }

  private static List<Column> columns(SemanticCatalog catalog, AnalyticsRequest request) {
    List<Column> out = new ArrayList<>();
    for (String d : request.dimensions()) {
      SemanticCatalog.Dimension def = catalog.dimensions().get(d);
      String type =
          switch (def.type()) {
            case "time" -> "time";
            case "number" -> "number";
            default -> "string";
          };
      out.add(new Column(d, def.title(), type, null));
    }
    for (String m : request.measures()) {
      SemanticCatalog.Measure def = catalog.measures().get(m);
      out.add(new Column(m, def.title(), columnType(def), def.unit()));
    }
    return out;
  }

  private static List<Object> values(
      SemanticCatalog catalog,
      AnalyticsRequest request,
      List<String> keys,
      Map<String, Object> row) {
    List<Object> out = new ArrayList<>(keys.size());
    int d = request.dimensions().size();
    for (int i = 0; i < keys.size(); i++) {
      Object v = row.get(keys.get(i));
      if (v == null && i < d) {
        v = row.get(request.dimensions().get(i));
      }
      boolean numeric =
          i >= d || "number".equals(catalog.dimensions().get(request.dimensions().get(i)).type());
      out.add(numeric ? number(v) : (v == null ? null : String.valueOf(v)));
    }
    return out;
  }

  private static String columnType(SemanticCatalog.Measure m) {
    return "count".equals(m.type()) ? "count" : "number";
  }

  private static boolean isTime(SemanticCatalog catalog, String dimension) {
    SemanticCatalog.Dimension d = catalog.dimensions().get(dimension);
    return d != null && "time".equals(d.type());
  }

  static BigDecimal number(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof BigDecimal b) {
      return b;
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return new BigDecimal(v.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Khoá đệm {@code <đơn vị>:<kỳ>:<băm>}: đơn vị, kỳ của yêu cầu ({@code all} khi không chọn) và
   * băm SHA-256 của phiên bản lớp ngữ nghĩa, nhánh phạm vi và toàn bộ truy vấn.
   */
  static List<String> cacheKey(String version, String orgPath, AnalyticsRequest request) {
    return List.of(
        request.unitId() == null ? "all" : request.unitId().toString(),
        request.periodId() == null ? "all" : request.periodId().toString(),
        sha256(version + "|" + orgPath + "|" + request));
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ dịch kết quả đánh giá từng phần OPA ({@code POST /v1/compile}) sang mệnh đề SQL có tham số
 * ({@link SqlPredicate}) và đệm theo {@code (người dùng, hành động, phiên bản bundle)} (NEN-014,
 * ADR-21, TL-01 "Đánh giá từng phần cho truy vấn danh sách").
 *
 * <p>Hỗ trợ các điều kiện nguyên tử chuẩn:
 *
 * <ul>
 *   <li>Bằng ({@code eq(input.resource.<field>, <value>)}) → {@code <col> = ?}
 *   <li>Thuộc tập ({@code internal.member_2(input.resource.<field>, [...])}) → {@code <col> IN (?,
 *       ...)}
 *   <li>Thuộc cây đơn vị ({@code eq(input.resource.ancestor_unit_id, <unit_id>)} hoặc {@code
 *       eq(input.resource.owner_unit_id, data.org[<tenant>].subtree[<unit_id>][_])}) → {@code
 *       <owner_unit_col> IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc WHERE
 *       uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)}
 * </ul>
 *
 * <p>Mọi biểu thức khác (phủ định, toán tử lạ, cột không khai báo, tập {@code support} không rỗng)
 * đều ném {@link UntranslatablePolicyException} để từ chối truy vấn thay vì bỏ qua điều kiện.
 */
public final class OpaSqlFilter {

  public static final String DEFAULT_PARTIAL_QUERY = "data.okrkpi_partial.allow == true";
  public static final String PARTIAL_REGO_RESOURCE = "vn/viettel/okrkpi/shared/authz/partial.rego";

  private final OpaClient opaClient;
  private final OpaProperties properties;
  private final Clock clock;
  private final ConcurrentHashMap<String, CachedPredicate> cache = new ConcurrentHashMap<>();

  public OpaSqlFilter(OpaClient opaClient, OpaProperties properties) {
    this(opaClient, properties, Clock.systemUTC());
  }

  public OpaSqlFilter(OpaClient opaClient, OpaProperties properties, Clock clock) {
    this.opaClient = Objects.requireNonNull(opaClient, "opaClient must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Đánh giá từng phần cho {@code (subject, action)} với truy vấn mặc định {@link
   * #DEFAULT_PARTIAL_QUERY} và dịch sang {@link SqlPredicate}.
   */
  public SqlPredicate buildPredicate(
      OpaInput.Subject subject, String action, ResourceColumnMapping mapping) {
    return buildPredicate(DEFAULT_PARTIAL_QUERY, subject, action, mapping);
  }

  /**
   * Đánh giá từng phần với câu truy vấn Rego chỉ định (ví dụ {@code data.okrkpi_partial.allow ==
   * true} hoặc {@code data.okrkpi.task.allow == true}) và dịch sang {@link SqlPredicate}.
   */
  public SqlPredicate buildPredicate(
      String query, OpaInput.Subject subject, String action, ResourceColumnMapping mapping) {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(mapping, "mapping must not be null");

    String rev = opaClient.currentBundleRevision();
    Duration ttl = properties.getSqlFilterCacheTtl();
    boolean cacheEnabled = ttl != null && !ttl.isZero() && !ttl.isNegative();
    String cacheKey =
        cacheEnabled
            ? (rev
                + "|"
                + query
                + "|"
                + subject
                + "|"
                + action
                + "|"
                + mapping.ownerUnitColumn()
                + "|"
                + mapping.defaultTree()
                + "|"
                + mapping.uuidIds())
            : null;

    if (cacheEnabled && cacheKey != null) {
      CachedPredicate hit = cache.get(cacheKey);
      if (hit != null) {
        if (clock.instant().isBefore(hit.expiresAt())) {
          return hit.predicate();
        }
        cache.remove(cacheKey, hit);
      }
    }

    Map<String, Object> input = Map.of("subject", subject, "action", action);
    JsonNode compileResult = opaClient.compile(query, input, List.of("input.resource"));
    SqlPredicate predicate = translateCompileResult(compileResult, subject, mapping);

    if (cacheEnabled && cacheKey != null) {
      cache.put(cacheKey, new CachedPredicate(predicate, clock.instant().plus(ttl)));
    }
    return predicate;
  }

  /**
   * Dịch trực tiếp cây AST {@code result} của {@code POST /v1/compile} sang {@link SqlPredicate}.
   */
  public SqlPredicate translateCompileResult(
      JsonNode resultNode, OpaInput.Subject subject, ResourceColumnMapping mapping) {
    Objects.requireNonNull(resultNode, "resultNode must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(mapping, "mapping must not be null");

    JsonNode support = resultNode.get("support");
    if (support != null && support.isArray() && !support.isEmpty()) {
      throw new UntranslatablePolicyException(
          "OPA /v1/compile trả về support rules không rỗng, từ chối dịch SQL");
    }

    JsonNode queries = resultNode.get("queries");
    if (queries == null || !queries.isArray() || queries.isEmpty()) {
      return SqlPredicate.FALSE;
    }

    Map<String, SqlPredicate> uniqueDisjuncts = new LinkedHashMap<>();
    for (JsonNode queryNode : queries) {
      if (queryNode == null || !queryNode.isArray()) {
        throw new UntranslatablePolicyException("Phần tử queries trong AST không phải mảng");
      }
      if (queryNode.isEmpty()) {
        // Mảng con rỗng trong queries của OPA nghĩa là điều kiện đã thoả vô điều kiện.
        return SqlPredicate.TRUE;
      }
      SqlPredicate conjunct = translateConjunctiveQuery(queryNode, subject, mapping);
      uniqueDisjuncts.putIfAbsent(conjunct.sql() + "|" + conjunct.params(), conjunct);
    }

    if (uniqueDisjuncts.size() == 1) {
      return uniqueDisjuncts.values().iterator().next();
    }

    StringBuilder sql = new StringBuilder("(");
    List<Object> allParams = new ArrayList<>();
    int idx = 0;
    for (SqlPredicate disjunct : uniqueDisjuncts.values()) {
      if (idx++ > 0) {
        sql.append(" OR ");
      }
      sql.append("(").append(disjunct.sql()).append(")");
      allParams.addAll(disjunct.params());
    }
    sql.append(")");
    return new SqlPredicate(sql.toString(), allParams);
  }

  public void clearCache() {
    cache.clear();
  }

  private SqlPredicate translateConjunctiveQuery(
      JsonNode queryNode, OpaInput.Subject subject, ResourceColumnMapping mapping) {
    StringBuilder sql = new StringBuilder();
    List<Object> params = new ArrayList<>();
    for (int i = 0; i < queryNode.size(); i++) {
      JsonNode expr = queryNode.get(i);
      SqlPredicate atom = translateExpression(expr, subject, mapping);
      if (i > 0) {
        sql.append(" AND ");
      }
      sql.append(atom.sql());
      params.addAll(atom.params());
    }
    return new SqlPredicate(sql.toString(), params);
  }

  private SqlPredicate translateExpression(
      JsonNode expr, OpaInput.Subject subject, ResourceColumnMapping mapping) {
    if (expr == null || !expr.isObject()) {
      throw new UntranslatablePolicyException("Biểu thức AST không hợp lệ");
    }
    if (expr.path("negated").asBoolean(false)) {
      throw new UntranslatablePolicyException(
          "Không hỗ trợ biểu thức phủ định (negated) trong bộ dịch SQL");
    }
    JsonNode terms = expr.get("terms");
    if (terms == null || !terms.isArray() || terms.size() != 3) {
      throw new UntranslatablePolicyException(
          "Chỉ hỗ trợ biểu thức nhị phân 3 phần tử [operator, lhs, rhs] trong AST");
    }
    String op = extractOperatorName(terms.get(0));
    JsonNode lhs = terms.get(1);
    JsonNode rhs = terms.get(2);

    if ("eq".equals(op) || "equal".equals(op)) {
      return translateEquals(lhs, rhs, subject, mapping);
    }
    if ("internal.member_2".equals(op)) {
      return translateMemberOf(lhs, rhs, mapping);
    }
    throw new UntranslatablePolicyException("Toán tử Rego không hỗ trợ dịch SQL: " + op);
  }

  private SqlPredicate translateEquals(
      JsonNode lhs, JsonNode rhs, OpaInput.Subject subject, ResourceColumnMapping mapping) {
    String lhsField = extractResourceField(lhs);
    String rhsField = extractResourceField(rhs);
    if (lhsField != null && rhsField == null) {
      return translateFieldEqualsTerm(lhsField, rhs, subject, mapping);
    }
    if (rhsField != null && lhsField == null) {
      return translateFieldEqualsTerm(rhsField, lhs, subject, mapping);
    }
    throw new UntranslatablePolicyException(
        "Biểu thức eq không chứa đúng một thuộc tính input.resource.<field>");
  }

  private SqlPredicate translateFieldEqualsTerm(
      String resourceField,
      JsonNode valueTerm,
      OpaInput.Subject subject,
      ResourceColumnMapping mapping) {
    // Trường hợp thuộc cây đơn vị qua input.resource.ancestor_unit_id == "<unit_id>"
    if ("ancestor_unit_id".equals(resourceField)) {
      String ancestorUnitId = extractScalarString(valueTerm);
      return buildUnitSubtreePredicate(subject.tenantId(), ancestorUnitId, mapping);
    }

    // Trường hợp tham chiếu data.org[tenant].subtree[ancestor][_] như ví dụ TL-01
    SubtreeRef subtreeRef = extractOrgSubtreeRef(valueTerm);
    if (subtreeRef != null && "owner_unit_id".equals(resourceField)) {
      return buildUnitSubtreePredicate(subtreeRef.tenantId(), subtreeRef.ancestorUnitId(), mapping);
    }

    ResourceColumnMapping.ColumnSpec col =
        mapping
            .columnFor(resourceField)
            .orElseThrow(
                () ->
                    new UntranslatablePolicyException(
                        "Thuộc tính tài nguyên chưa được ánh xạ cột SQL: " + resourceField));
    Object boundValue = extractBoundScalar(valueTerm, col.uuidType());
    return new SqlPredicate(col.sqlColumn() + " = ?", List.of(boundValue));
  }

  private SqlPredicate translateMemberOf(
      JsonNode itemTerm, JsonNode arrayTerm, ResourceColumnMapping mapping) {
    String resourceField = extractResourceField(itemTerm);
    if (resourceField == null) {
      throw new UntranslatablePolicyException(
          "Toán tử internal.member_2 yêu cầu vế trái là input.resource.<field>");
    }
    ResourceColumnMapping.ColumnSpec col =
        mapping
            .columnFor(resourceField)
            .orElseThrow(
                () ->
                    new UntranslatablePolicyException(
                        "Thuộc tính tài nguyên chưa được ánh xạ cột SQL: " + resourceField));
    if (arrayTerm == null
        || !"array".equals(arrayTerm.path("type").asText())
        || !arrayTerm.path("value").isArray()) {
      throw new UntranslatablePolicyException(
          "Toán tử internal.member_2 yêu cầu vế phải là mảng hằng");
    }
    JsonNode elements = arrayTerm.get("value");
    if (elements.isEmpty()) {
      return SqlPredicate.FALSE;
    }
    StringBuilder placeholders = new StringBuilder();
    List<Object> params = new ArrayList<>(elements.size());
    for (int i = 0; i < elements.size(); i++) {
      if (i > 0) {
        placeholders.append(", ");
      }
      placeholders.append("?");
      params.add(extractBoundScalar(elements.get(i), col.uuidType()));
    }
    return new SqlPredicate(col.sqlColumn() + " IN (" + placeholders + ")", params);
  }

  private SqlPredicate buildUnitSubtreePredicate(
      String tenantIdRaw, String ancestorUnitIdRaw, ResourceColumnMapping mapping) {
    if (tenantIdRaw == null || tenantIdRaw.isBlank()) {
      throw new UntranslatablePolicyException(
          "Thiếu tenant_id khi dựng mệnh đề cây đơn vị org_replica.unit_closure_v");
    }
    if (ancestorUnitIdRaw == null || ancestorUnitIdRaw.isBlank()) {
      throw new UntranslatablePolicyException(
          "Thiếu ancestor_id khi dựng mệnh đề cây đơn vị org_replica.unit_closure_v");
    }
    Object tenantParam = mapping.uuidIds() ? parseUuid(tenantIdRaw, "tenant_id") : tenantIdRaw;
    Object ancestorParam =
        mapping.uuidIds() ? parseUuid(ancestorUnitIdRaw, "ancestor_id") : ancestorUnitIdRaw;
    String sql =
        mapping.ownerUnitColumn()
            + " IN (SELECT uc.descendant_id FROM "
            + mapping.unitClosureTable()
            + " uc WHERE uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)";
    return new SqlPredicate(sql, List.of(tenantParam, mapping.defaultTree(), ancestorParam));
  }

  private static String extractOperatorName(JsonNode opNode) {
    if (opNode == null || !"ref".equals(opNode.path("type").asText())) {
      throw new UntranslatablePolicyException("Toán tử AST không phải kiểu ref");
    }
    JsonNode val = opNode.get("value");
    if (val == null || !val.isArray() || val.isEmpty()) {
      throw new UntranslatablePolicyException("Toán tử ref rỗng");
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < val.size(); i++) {
      if (i > 0) {
        sb.append(".");
      }
      sb.append(val.get(i).path("value").asText());
    }
    return sb.toString();
  }

  private static String extractResourceField(JsonNode term) {
    if (term == null || !"ref".equals(term.path("type").asText())) {
      return null;
    }
    JsonNode parts = term.get("value");
    if (parts == null || !parts.isArray() || parts.size() != 3) {
      return null;
    }
    String p0 = parts.get(0).path("value").asText();
    String p1 = parts.get(1).path("value").asText();
    String p2Type = parts.get(2).path("type").asText();
    String p2 = parts.get(2).path("value").asText();
    if ("input".equals(p0) && "resource".equals(p1) && "string".equals(p2Type)) {
      return p2;
    }
    return null;
  }

  private static SubtreeRef extractOrgSubtreeRef(JsonNode term) {
    if (term == null || !"ref".equals(term.path("type").asText())) {
      return null;
    }
    JsonNode parts = term.get("value");
    if (parts == null || !parts.isArray() || parts.size() != 6) {
      return null;
    }
    if ("data".equals(parts.get(0).path("value").asText())
        && "org".equals(parts.get(1).path("value").asText())
        && "string".equals(parts.get(2).path("type").asText())
        && "subtree".equals(parts.get(3).path("value").asText())
        && "string".equals(parts.get(4).path("type").asText())
        && "var".equals(parts.get(5).path("type").asText())) {
      return new SubtreeRef(
          parts.get(2).path("value").asText(), parts.get(4).path("value").asText());
    }
    return null;
  }

  private static String extractScalarString(JsonNode term) {
    if (term == null || !"string".equals(term.path("type").asText())) {
      throw new UntranslatablePolicyException(
          "Giá trị AST không phải hằng chuỗi (string): " + term);
    }
    return term.path("value").asText();
  }

  private static Object extractBoundScalar(JsonNode term, boolean uuidType) {
    if (term == null || !term.isObject()) {
      throw new UntranslatablePolicyException("Giá trị AST rỗng hoặc không hợp lệ");
    }
    String type = term.path("type").asText();
    JsonNode val = term.get("value");
    if ("string".equals(type) && val != null && val.isTextual()) {
      String text = val.asText();
      return uuidType ? parseUuid(text, "uuid") : text;
    }
    if (!uuidType && "number".equals(type) && val != null && val.isNumber()) {
      return val.numberValue();
    }
    if (!uuidType && "boolean".equals(type) && val != null && val.isBoolean()) {
      return val.booleanValue();
    }
    throw new UntranslatablePolicyException(
        "Không thể trích giá trị hằng SQL từ nút AST kiểu: " + type);
  }

  private static UUID parseUuid(String raw, String fieldName) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new UntranslatablePolicyException(
          "Giá trị " + fieldName + " trong AST không phải UUID hợp lệ: " + raw, e);
    }
  }

  private record SubtreeRef(String tenantId, String ancestorUnitId) {}

  private record CachedPredicate(SqlPredicate predicate, Instant expiresAt) {}
}

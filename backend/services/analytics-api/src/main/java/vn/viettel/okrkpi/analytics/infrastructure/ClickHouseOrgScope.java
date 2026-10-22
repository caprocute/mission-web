package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.OrgScopePort;

/**
 * Đọc cây tổ chức, kỳ từ kho phân tích qua giao diện HTTP của ClickHouse, bằng tài khoản Cube của
 * cell (DV-15-03) và {@code SQL_tenant_id} để row policy V009 lọc thêm theo tenant. Giá trị luôn
 * truyền bằng tham số {@code {x:Type}}, không ghép vào câu SQL.
 */
final class ClickHouseOrgScope implements OrgScopePort {

  private static final String PATHS =
      "SELECT org_path_dang, org_path_chuyen_mon FROM dim_org_scope"
          + " WHERE tenant_id = {t:UUID} AND id = {u:UUID} LIMIT 1";
  private static final String ANCESTORS =
      "SELECT toString(ancestor_id) AS ma, min(depth) AS sau FROM dim_org_closure FINAL"
          + " WHERE tenant_id = {t:UUID} AND descendant_id = {u:UUID}"
          + " GROUP BY ancestor_id ORDER BY sau, ma LIMIT {n:UInt32}";
  private static final String PREVIOUS =
      "SELECT toString(p.id) AS ma FROM cube_period AS p"
          + " CROSS JOIN (SELECT kind AS k, flow AS f, start_date AS s FROM cube_period"
          + " WHERE tenant_id = {t:UUID} AND id = {p:UUID}) AS c"
          + " WHERE p.tenant_id = {t:UUID} AND p.kind = c.k AND p.flow = c.f AND p.start_date < c.s"
          + " ORDER BY p.start_date DESC LIMIT 1";

  private final ClickHouseHttp ch;

  ClickHouseOrgScope(ClickHouseHttp ch) {
    this.ch = ch;
  }

  @Override
  public List<String> paths(UUID tenant, UUID unit) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : rows(tenant, PATHS, Map.of("u", unit.toString()))) {
      for (String col : List.of("org_path_dang", "org_path_chuyen_mon")) {
        String p = row.path(col).asText("");
        if (!p.isEmpty() && !out.contains(p)) {
          out.add(p);
        }
      }
    }
    return out;
  }

  @Override
  public List<UUID> unitAndAncestors(UUID tenant, UUID unit, int limit) {
    List<UUID> out = new ArrayList<>(List.of(unit));
    Map<String, String> p = Map.of("u", unit.toString(), "n", String.valueOf(limit));
    for (JsonNode row : rows(tenant, ANCESTORS, p)) {
      UUID id = UUID.fromString(row.path("ma").asText());
      if (!out.contains(id) && out.size() < limit) {
        out.add(id);
      }
    }
    return out;
  }

  @Override
  public Optional<UUID> previousPeriod(UUID tenant, UUID period) {
    return rows(tenant, PREVIOUS, Map.of("p", period.toString())).stream()
        .findFirst()
        .map(r -> UUID.fromString(r.path("ma").asText()));
  }

  private List<JsonNode> rows(UUID tenant, String sql, Map<String, String> params) {
    return ch.rows(tenant, sql, params);
  }
}

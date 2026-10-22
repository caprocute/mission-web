package vn.viettel.okrkpi.platform.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Đọc thời hạn của bảy lớp từ {@code config.tenant_setting} (khoá {@code retention.<LỚP>}, giá trị
 * toàn tenant) trong ngữ cảnh RLS của từng tenant. Một phân vùng ở CSDL bridge hay pool chứa nhiều
 * tenant nên job dùng quy tắc giữ lâu nhất của các tenant đó (suy luận, wiki 14).
 */
public final class RetentionRules {

  private final JdbcTemplate config;
  private final ObjectMapper json;

  public RetentionRules(JdbcTemplate config, ObjectMapper json) {
    this.config = config;
    this.json = json;
  }

  public Map<RetentionClass, RetentionClass.Rule> forTenant(UUID tenant) {
    Map<RetentionClass, RetentionClass.Rule> out = new EnumMap<>(RetentionClass.class);
    for (RetentionClass c : RetentionClass.values()) {
      out.put(c, c.defaults());
    }
    TenantTx.run(
        config,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT setting_key, value::text FROM config.tenant_setting"
                      + " WHERE tenant_id = ? AND scope_unit_id IS NULL AND value_schema = ?"
                      + " AND setting_key LIKE 'retention.%'")) {
            ps.setObject(1, tenant);
            ps.setString(2, RetentionClass.SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                RetentionClass c = classOf(rs.getString(1));
                if (c != null) {
                  out.put(c, c.parse(read(rs.getString(2))));
                }
              }
            }
          }
          return null;
        });
    return out;
  }

  /** Quy tắc giữ lâu nhất của một lớp trên các tenant cho trước; không có tenant thì mặc định. */
  public RetentionClass.Rule longest(RetentionClass c, Collection<UUID> tenants) {
    RetentionClass.Rule r = null;
    for (UUID t : tenants) {
      RetentionClass.Rule x = forTenant(t).get(c);
      r = r == null ? x : r.longest(x);
    }
    return r == null ? c.defaults() : r;
  }

  private static RetentionClass classOf(String key) {
    try {
      return RetentionClass.valueOf(key.substring("retention.".length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private JsonNode read(String v) {
    try {
      return json.readTree(v);
    } catch (Exception e) {
      return json.createObjectNode();
    }
  }
}

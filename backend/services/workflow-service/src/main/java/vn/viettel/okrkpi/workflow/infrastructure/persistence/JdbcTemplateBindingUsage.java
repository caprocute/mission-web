package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.TemplateBindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;

/**
 * {@link TemplateBindingUsage} đọc {@code flowable.wf_binding} của mọi tenant. Chính sách RLS đọc
 * chéo tenant của V9 chỉ mở khi giao dịch tự đặt {@code app.platform_scope = 'wf_template_usage'};
 * cờ đặt cục bộ giao dịch và được xoá ngay sau câu đọc, chỉ cho SELECT.
 */
public class JdbcTemplateBindingUsage implements TemplateBindingUsage {

  static final String SCOPE_SETTING = "app.platform_scope";
  static final String SCOPE_VALUE = "wf_template_usage";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcTemplateBindingUsage(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<BindingUsage> bindings(String templateCode) {
    open();
    try {
      return jdbc.query(
          "SELECT b.tenant_id, b.id, b.process_kind, b.flow, b.params_json::text AS params"
              + " FROM flowable.wf_binding b JOIN flowable.wf_template t ON t.id = b.wf_template_id"
              + " WHERE t.template_code = ? AND b.status IN ('NHAP', 'HIEU_LUC')"
              + " ORDER BY b.tenant_id, b.id",
          (rs, row) ->
              new BindingUsage(
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("id", UUID.class),
                  rs.getString("process_kind"),
                  rs.getString("flow"),
                  read(rs.getString("params"))),
          templateCode);
    } finally {
      close();
    }
  }

  @Override
  public Map<UUID, Integer> tenantCounts(Collection<UUID> templateIds) {
    if (templateIds.isEmpty()) {
      return Map.of();
    }
    open();
    try {
      Map<UUID, Integer> result = new HashMap<>();
      jdbc.query(
          "SELECT wf_template_id, count(DISTINCT tenant_id) FROM flowable.wf_binding"
              + " WHERE status IN ('NHAP', 'HIEU_LUC') AND wf_template_id IN ("
              + JdbcWorkflowTemplateStore.placeholders(templateIds.size())
              + ") GROUP BY wf_template_id",
          rs -> {
            result.put(rs.getObject(1, UUID.class), rs.getInt(2));
          },
          templateIds.toArray());
      return result;
    } finally {
      close();
    }
  }

  private void open() {
    TenantBinding.bind(jdbc, TenantContext.require().value());
    jdbc.queryForObject("select set_config(?, ?, true)", String.class, SCOPE_SETTING, SCOPE_VALUE);
  }

  private void close() {
    jdbc.queryForObject("select set_config(?, '', true)", String.class, SCOPE_SETTING);
  }

  private Map<String, Object> read(String text) {
    try {
      return text == null ? Map.of() : json.readValue(text, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("params_json không đọc được", e);
    }
  }
}

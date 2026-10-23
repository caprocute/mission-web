package vn.viettel.okrkpi.workflow.infrastructure.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantSql;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;

/**
 * Bộ chuyển mặc định của {@link TemplateCodeHost} (dev, test, môi trường chưa nối hệ quản lý mã):
 * ghi lệnh mở và duyệt merge request vào hộp thư {@code flowable.wf_code_host_command}; tác vụ đồng
 * bộ với repo mẫu luồng đọc lệnh, thực hiện rồi đánh dấu {@code DA_XU_LY}. Mã merge request sinh từ
 * mã phiên bản mẫu nên gọi lại không tạo trùng; đường dẫn ghép từ địa chỉ gốc cấu hình (rỗng khi
 * chưa cấu hình).
 */
public class MailboxTemplateCodeHost implements TemplateCodeHost {

  static final String OPEN = "MO_MR";
  static final String APPROVE = "DUYET_MR";

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final String baseUrl;

  public MailboxTemplateCodeHost(JdbcTemplate jdbc, ObjectMapper json, String baseUrl) {
    this.jdbc = jdbc;
    this.json = json;
    this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
  }

  @Override
  public MergeRequest open(ReleasePackage pkg, UUID actor) {
    String ref = "mr-" + pkg.templateId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("branch", pkg.branch());
    payload.put("title", pkg.title());
    payload.put("templateCode", pkg.templateCode());
    payload.put("versionNo", pkg.versionNo());
    payload.put("files", new TreeMap<>(pkg.files()));
    write(pkg.templateId(), OPEN, ref, payload, actor);
    return new MergeRequest(ref, baseUrl == null ? null : baseUrl + "/" + ref);
  }

  @Override
  public void approve(UUID templateId, String mergeRequestRef, UUID approver) {
    write(
        templateId, APPROVE, mergeRequestRef, Map.of("approvedBy", approver.toString()), approver);
  }

  private void write(
      UUID templateId, String command, String ref, Map<String, Object> payload, UUID actor) {
    UUID tenant = TenantContext.require().value();
    jdbc.queryForObject(
        "select set_config(?, ?, true)",
        String.class,
        TenantSql.DEFAULT_SETTING,
        tenant.toString());
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Không ghi được lệnh gửi hệ quản lý mã", e);
    }
    jdbc.update(
        "INSERT INTO flowable.wf_code_host_command (id, tenant_id, wf_template_id, command,"
            + " merge_request_ref, payload_json, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)"
            + " ON CONFLICT (wf_template_id, command) DO NOTHING",
        Ids.newUuidV7(),
        tenant,
        templateId,
        command,
        ref,
        body,
        actor);
  }
}

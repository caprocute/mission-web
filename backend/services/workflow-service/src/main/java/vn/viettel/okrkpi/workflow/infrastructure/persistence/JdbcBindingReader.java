package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * {@link BindingReader} trên {@code flowable.wf_binding} (TL-03). Chọn dòng của tenant cho loại hồ
 * sơ ({@code process_kind}), ưu tiên dòng khớp luồng rồi dòng không ghi luồng; dòng gắn khối đối
 * tượng hoặc loại kỳ cụ thể chưa được xét ở DV-06-03 (suy luận, ghi ở wiki trang 14).
 *
 * <p>Khoá của {@code params_json}: {@code remindAfter}, {@code allowDelegation}, {@code
 * signMethod} (TL-01 "Bảng tham số theo tenant") và {@code cap} là danh sách cấp {@code {vai_tro,
 * pham_vi, han_ngay_lam_viec}} hoặc {@code thoi_han_moi_buoc} dạng {@code P3D_LV} (TL-02b
 * SCR-1504). Khoá thiếu lấy mặc định của khối.
 */
@Repository
public class JdbcBindingReader implements BindingReader {

  private static final Pattern WORKING_DAYS = Pattern.compile("P(\\d{1,2})D_LV");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcBindingReader(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<ApprovalParams> find(UUID tenantId, String processKind, ApprovalFlow flow) {
    TenantBinding.bind(jdbc, tenantId);
    List<String> rows =
        jdbc.queryForList(
            "SELECT params_json::text FROM flowable.wf_binding"
                + " WHERE tenant_id = ? AND process_kind = ? AND (flow = ? OR flow IS NULL)"
                + " AND subject_group_id IS NULL AND period_type IS NULL"
                + " ORDER BY (flow IS NULL) LIMIT 1",
            String.class,
            tenantId,
            processKind,
            flow.name());
    return rows.stream().findFirst().map(raw -> parse(raw, flow));
  }

  /** Đọc tham số; khoá thiếu lấy mặc định của khối. */
  ApprovalParams parse(String raw, ApprovalFlow flow) {
    ApprovalParams defaults = ApprovalParams.defaults(flow);
    if (raw == null || raw.isBlank()) {
      return defaults;
    }
    try {
      JsonNode root = json.readTree(raw);
      String remind = text(root, "remindAfter", defaults.remindAfter());
      boolean delegation =
          root.hasNonNull("allowDelegation")
              ? root.get("allowDelegation").asBoolean()
              : defaults.allowDelegation();
      String sign = text(root, "signMethod", defaults.signMethod());
      return new ApprovalParams(remind, delegation, levels(root), sign);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new ApprovalSetupException("Tham số mẫu luồng của tenant không hợp lệ", e);
    }
  }

  private static List<ApprovalRule> levels(JsonNode root) {
    JsonNode cap = root.get("cap");
    if (cap == null || !cap.isArray()) {
      return List.of();
    }
    int defaultDays = days(root.path("thoi_han_moi_buoc").asText(""), 3);
    List<ApprovalRule> rules = new ArrayList<>(cap.size());
    int order = 1;
    for (JsonNode level : cap) {
      ApprovalScope scope =
          ApprovalScope.parse(level.path("pham_vi").asText(ApprovalScope.DON_VI_TRUC_TIEP.name()))
              .orElseThrow(() -> new IllegalArgumentException("Phạm vi cấp duyệt sai"));
      int workingDays =
          level.hasNonNull("han_ngay_lam_viec")
              ? level.get("han_ngay_lam_viec").asInt()
              : defaultDays;
      rules.add(new ApprovalRule(order++, level.path("vai_tro").asText(""), scope, workingDays));
    }
    return rules;
  }

  private static int days(String duration, int fallback) {
    Matcher m = WORKING_DAYS.matcher(duration);
    return m.matches() ? Integer.parseInt(m.group(1)) : fallback;
  }

  private static String text(JsonNode root, String field, String fallback) {
    return root.hasNonNull(field) ? root.get(field).asText() : fallback;
  }
}

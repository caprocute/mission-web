package vn.viettel.okrkpi.workflow.application.dual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.binding.ParamsLinter;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval.Branch;
import vn.viettel.okrkpi.workflow.domain.stage.OverdueAction;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;

/**
 * Kế hoạch hai nhánh của mẫu duyệt kép từ {@code params_json} của {@code flowable.wf_binding} loại
 * {@value DualApproval#PROCESS_KIND}, cùng khoá với các mẫu khác (SCR-1504): {@code cap[]} có
 * {@code buoc} ({@code CHI_HUY}, {@code CAP_UY}), {@code vai_tro}, {@code pham_vi}, {@code
 * han_ngay_lam_viec}, {@code ky_so}; {@code nhac_truoc}, {@code khi_qua_han}, {@code
 * allowDelegation}, {@code nguoi_thay}. Thiếu khoá nào thì dùng mặc định của mẫu; giá trị ngoài
 * miền là lỗi cấu hình.
 */
@Component
public class DualApprovalPlanner {

  private static final Pattern WORKING_DAYS = Pattern.compile("P(\\d{1,2})D_LV");

  private final ObjectMapper json;

  public DualApprovalPlanner(ObjectMapper json) {
    this.json = json;
  }

  /**
   * Kế hoạch.
   *
   * @param plan tham số hai nhánh (mã phần tử là mã user task của nhánh)
   * @param signs nhánh có bước ký số
   */
  public record Planned(StagePlan plan, Map<Branch, Boolean> signs) {

    public Planned {
      signs = Map.copyOf(signs);
    }
  }

  /** Kế hoạch từ tham số thô của tenant (rỗng: mặc định của mẫu). */
  public Planned plan(Optional<String> rawParams) {
    JsonNode root = parse(rawParams.orElse("{}"));
    int remind =
        Optional.ofNullable(days(root.path("nhac_truoc").asText("")))
            .orElse(DualApproval.DEFAULT_REMIND);
    OverdueAction overdue =
        root.hasNonNull("khi_qua_han")
            ? OverdueAction.parse(root.path("khi_qua_han").asText())
                .orElseThrow(() -> new ApprovalSetupException("Hành vi khi quá hạn không hợp lệ"))
            : OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN;
    boolean delegation = root.path("allowDelegation").asBoolean(true);
    if (!delegation && overdue == OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN) {
      overdue = OverdueAction.CHUYEN_CAP_TREN;
    }
    Map<String, StagePlan.Planned> steps = new LinkedHashMap<>();
    Map<Branch, Boolean> signs = new EnumMap<>(Branch.class);
    for (Branch b : Branch.values()) {
      JsonNode level = level(root, b);
      String role = level.path("vai_tro").asText(Branch.DEFAULT_ROLE);
      if (!ParamsLinter.ROLES.contains(role)) {
        throw new ApprovalSetupException("Vai trò của nhánh duyệt không thuộc danh mục");
      }
      ApprovalScope scope =
          level.hasNonNull("pham_vi")
              ? ApprovalScope.parse(level.path("pham_vi").asText())
                  .orElseThrow(() -> new ApprovalSetupException("Phạm vi của nhánh không hợp lệ"))
              : b.defaultScope();
      int days = level.path("han_ngay_lam_viec").asInt(DualApproval.DEFAULT_DAYS);
      if (days < DualApproval.MIN_DAYS || days > DualApproval.MAX_DAYS) {
        throw new ApprovalSetupException("Thời hạn mỗi nhánh từ 1 đến 15 ngày làm việc");
      }
      UUID substitute = delegation ? substitute(root, b) : null;
      steps.put(
          b.elementId(),
          new StagePlan.Planned(b.elementId(), role, scope, days, remind, overdue, substitute));
      signs.put(b, level.path("ky_so").asBoolean(false));
    }
    return new Planned(new StagePlan(steps), signs);
  }

  private JsonNode parse(String raw) {
    try {
      return json.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new ApprovalSetupException("Tham số luồng duyệt kép không đọc được", e);
    }
  }

  private static JsonNode level(JsonNode root, Branch b) {
    for (JsonNode level : root.path("cap")) {
      if (b.name().equals(level.path("buoc").asText())) {
        return level;
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private static UUID substitute(JsonNode root, Branch b) {
    String value = root.path("nguoi_thay").path(b.name()).asText("");
    if (value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ApprovalSetupException("Người được uỷ quyền của nhánh không hợp lệ", e);
    }
  }

  private static Integer days(String duration) {
    Matcher m = WORKING_DAYS.matcher(duration);
    return m.matches() ? Integer.valueOf(m.group(1)) : null;
  }
}

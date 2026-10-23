package vn.viettel.okrkpi.workflow.application.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import vn.viettel.okrkpi.workflow.domain.stage.OverdueAction;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;
import vn.viettel.okrkpi.workflow.domain.stage.StageStep;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Lập kế hoạch bước lúc khởi tạo: mặc định của mẫu ghi đè bằng {@code params_json} của {@code
 * wf_binding} (TL-02b SCR-1504): {@code cap[]} theo {@code buoc} ({@code vai_tro}, {@code pham_vi},
 * {@code han_ngay_lam_viec}), {@code thoi_han_moi_buoc}, {@code nhac_truoc} dạng {@code P1D_LV},
 * {@code khi_qua_han}, {@code nguoi_thay} (bước → người thay, suy luận) và {@code so_lan_tra_lai}.
 * Bước có hạn tổng thể (khiếu nại) không nhận {@code thoi_han_moi_buoc}.
 */
@Component
public class StagePlanner {

  /** Số lần trả lại tối đa mặc định (TL-02b QT-09 "số lần trả lại ≤ 2 (tham số)"). */
  public static final int DEFAULT_MAX_RETURNS = 2;

  private static final Pattern WORKING_DAYS = Pattern.compile("P(\\d{1,2})D_LV");

  private final ObjectMapper json;

  public StagePlanner(ObjectMapper json) {
    this.json = json;
  }

  /**
   * Kế hoạch và số lần trả lại tối đa.
   *
   * @param plan kế hoạch bước
   * @param maxReturns số lần trả lại tối đa
   */
  public record Planned(StagePlan plan, int maxReturns) {}

  /** Lập kế hoạch từ tham số thô của tenant (rỗng thì dùng mặc định của mẫu). */
  public Planned plan(StageTemplate template, Optional<String> rawParams) {
    JsonNode root = read(rawParams.orElse(null));
    Integer stepDays = days(root.path("thoi_han_moi_buoc").asText(""));
    Integer remind = days(root.path("nhac_truoc").asText(""));
    Optional<OverdueAction> overdue = OverdueAction.parse(root.path("khi_qua_han").asText(""));
    Map<String, StagePlan.Planned> steps = new LinkedHashMap<>();
    for (StageStep step : template.steps()) {
      JsonNode level = level(root, step.code());
      String role = level.path("vai_tro").asText(step.role());
      if (!ParamsLinter.ROLES.contains(role)) {
        throw new ApprovalSetupException("Vai trò của bước không thuộc danh mục");
      }
      ApprovalScope scope =
          ApprovalScope.parse(level.path("pham_vi").asText(step.scope().name()))
              .orElseThrow(() -> new ApprovalSetupException("Phạm vi của bước sai"));
      int workingDays = step.workingDays();
      if (level.hasNonNull("han_ngay_lam_viec")) {
        workingDays = level.get("han_ngay_lam_viec").asInt();
      } else if (stepDays != null && !step.fromStart()) {
        workingDays = stepDays;
      }
      int before = remind == null ? step.remindBefore() : remind;
      if (workingDays < 1 || workingDays > StageStep.MAX_DAYS) {
        throw new ApprovalSetupException("Hạn của bước phải từ 1 đến 15 ngày làm việc");
      }
      before = Math.min(before, workingDays - 1);
      steps.put(
          step.elementId(),
          new StagePlan.Planned(
              step.elementId(),
              role,
              scope,
              workingDays,
              Math.max(0, before),
              overdue.orElse(step.overdue()),
              substitute(root, step.code())));
    }
    int maxReturns =
        root.hasNonNull("so_lan_tra_lai")
            ? Math.max(0, Math.min(5, root.get("so_lan_tra_lai").asInt()))
            : DEFAULT_MAX_RETURNS;
    return new Planned(new StagePlan(steps), maxReturns);
  }

  /** Điều kiện bỏ qua ({@code bo_qua_khi}) của bước, nếu tenant khai. */
  public Optional<String> skipCondition(StageTemplate template, String code, String rawParams) {
    JsonNode level = level(read(rawParams), code);
    String expr = level.path("bo_qua_khi").asText("");
    boolean allowed = template.step(code).map(StageStep::skippable).orElse(false);
    return allowed && !expr.isBlank() ? Optional.of(expr) : Optional.empty();
  }

  private JsonNode read(String raw) {
    if (raw == null || raw.isBlank()) {
      return json.createObjectNode();
    }
    try {
      return json.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new ApprovalSetupException("Tham số mẫu luồng của tenant không hợp lệ", e);
    }
  }

  private static JsonNode level(JsonNode root, String code) {
    JsonNode cap = root.get("cap");
    if (cap != null && cap.isArray()) {
      for (JsonNode level : cap) {
        if (code.equals(level.path("buoc").asText())) {
          return level;
        }
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private static UUID substitute(JsonNode root, String code) {
    String value = root.path("nguoi_thay").path(code).asText("");
    if (value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ApprovalSetupException("Người thay của bước không hợp lệ", e);
    }
  }

  private static Integer days(String duration) {
    Matcher m = WORKING_DAYS.matcher(duration);
    return m.matches() ? Integer.valueOf(m.group(1)) : null;
  }
}

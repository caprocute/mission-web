package vn.viettel.okrkpi.workflow.application.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalLevel;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * Dựng biến {@code approvalLevels} ở bước "Chọn cấp duyệt" của mẫu: lấy các dòng của DMN {@code
 * chon-cap-duyet} (hoặc các cấp tuỳ chỉnh của tenant trong {@code wf_binding}), rồi phân giải bằng
 * {@link ApprovalLevelResolver}. Chỉ tính toán và đọc bản sao cục bộ, không gọi hệ ngoài (TL-01 G2
 * quy tắc 4).
 */
@Service
public class ApprovalLevelsAssembler {

  private final ApprovalLevelResolver resolver;

  public ApprovalLevelsAssembler(ApprovalLevelResolver resolver) {
    this.resolver = resolver;
  }

  /** Các cấp đã phân giải từ biến của tiến trình. */
  public List<ApprovalLevel> assemble(Inputs inputs) {
    ApprovalFlow flow =
        ApprovalFlow.parse(inputs.khoi())
            .orElseThrow(() -> new ApprovalSetupException("Luồng đánh giá không thuộc danh mục"));
    List<ApprovalRule> rules;
    try {
      rules =
          inputs.customLevels() == null || inputs.customLevels().isBlank()
              ? fromDecision(inputs.decisionRows())
              : ApprovalVariables.decodeRules(inputs.customLevels());
    } catch (IllegalArgumentException e) {
      throw new ApprovalSetupException("Cấp duyệt không hợp lệ: " + e.getMessage(), e);
    }
    ApprovalLevelResolver.Subject subject =
        new ApprovalLevelResolver.Subject(
            uuid(inputs.tenantId(), "tenant"), flow, uuid(inputs.unitId(), "đơn vị"), inputs.head());
    return resolver.resolve(subject, rules);
  }

  /** Đổi các dòng DMN ({@code thuTu}, {@code role}, {@code scope}, {@code hanNgayLamViec}). */
  static List<ApprovalRule> fromDecision(List<Map<String, Object>> rows) {
    if (rows == null) {
      return List.of();
    }
    List<ApprovalRule> rules = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      ApprovalScope scope =
          ApprovalScope.parse(String.valueOf(row.get("scope")))
              .orElseThrow(() -> new IllegalArgumentException("phạm vi không thuộc danh mục"));
      rules.add(
          new ApprovalRule(
              number(row.get("thuTu")),
              String.valueOf(row.get("role")),
              scope,
              number(row.get("hanNgayLamViec"))));
    }
    return rules;
  }

  private static int number(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s) {
      try {
        return (int) Double.parseDouble(s);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("giá trị số sai dạng", e);
      }
    }
    throw new IllegalArgumentException("thiếu giá trị số");
  }

  private static UUID uuid(String value, String what) {
    if (value == null) {
      throw new ApprovalSetupException("Thiếu mã " + what + " của tiến trình");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ApprovalSetupException("Mã " + what + " của tiến trình không hợp lệ", e);
    }
  }

  /**
   * Biến đầu vào của bước chọn cấp duyệt.
   *
   * @param tenantId biến {@code tenantId}
   * @param khoi biến {@code khoi}
   * @param unitId biến {@code donViId}
   * @param head biến {@code laNguoiDungDau}
   * @param customLevels biến {@code capTuyChinh}, có thể {@code null}
   * @param decisionRows các dòng kết quả DMN, có thể rỗng
   */
  public record Inputs(
      String tenantId,
      String khoi,
      String unitId,
      boolean head,
      String customLevels,
      List<Map<String, Object>> decisionRows) {}
}

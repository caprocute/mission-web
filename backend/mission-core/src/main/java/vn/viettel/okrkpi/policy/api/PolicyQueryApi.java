package vn.viettel.okrkpi.policy.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng truy vấn công thức và chính sách quy đổi ({@code policy :: api}) của {@code MOD-05 · Policy
 * & Formula} mở cho {@code MOD-02 (task)}, {@code MOD-03 (product)} và {@code MOD-04 (appraisal)}
 * (TL-01 mục 2.1, ADR-19). CORE-05-03: phân giải phiên bản Policy Pack áp cho tenant, kỳ, luồng,
 * khối, đơn vị theo {@code policy.policy_binding} (khớp cụ thể nhất trước, không có thì theo khoảng
 * hiệu lực của TL-01 "Hiệu lực theo kỳ"), đọc tham số, bảng {@code task_lifecycle} và quy tắc của
 * phiên bản. Kết quả đọc qua bộ đệm trong pod, vô hiệu khi phiên bản đổi trạng thái.
 */
public interface PolicyQueryApi {

  /**
   * Phiên bản Policy Pack áp cho tổ hợp; rỗng khi không có gói nào (POLICY-404-NO-BINDING).
   *
   * @param flow luồng {@code DANG} hoặc {@code NHA_NUOC}
   * @param subjectGroupId khối đối tượng, có thể {@code null}
   * @param orgUnitId đơn vị, có thể {@code null}
   */
  Optional<ResolvedPolicyPackView> resolve(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId);

  /**
   * Giá trị tham số {@code key} của phiên bản (khối {@code params} của gói, lấy {@code mac_dinh}).
   */
  Optional<Object> parameter(TenantId tenantId, UUID policyPackVersionId, String key);

  /**
   * Bảng chuyển trạng thái nhiệm vụ (khoá {@code task_lifecycle}, TL-01, ADR-04); rỗng nếu gói
   * không khai.
   */
  List<TaskTransitionView> taskLifecycle(TenantId tenantId, UUID policyPackVersionId);

  /** Quy tắc CEL theo thứ tự tính và bảng quyết định của phiên bản. */
  PolicyRulesView rules(TenantId tenantId, UUID policyPackVersionId);

  /**
   * Phiên bản đã phân giải; {@code contentHash} và {@code bundleObjectKey} để bên dùng tự kiểm toàn
   * vẹn trước khi tính (TL-01 X-PP-09).
   */
  record ResolvedPolicyPackView(
      UUID policyPackId,
      String code,
      UUID versionId,
      String version,
      String contentHash,
      String bundleObjectKey,
      UUID bindingId,
      boolean frozen) {

    public ResolvedPolicyPackView {
      Objects.requireNonNull(policyPackId, "policyPackId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(versionId, "versionId");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(bindingId, "bindingId");
    }
  }

  /**
   * Một dòng {@code Transition(from, command, to, allowedRoles, guardCel, reasonRequired)} của
   * TL-01.
   */
  record TaskTransitionView(
      String from,
      String command,
      String to,
      List<String> allowedRoles,
      String guardCel,
      boolean reasonRequired) {

    public TaskTransitionView {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(to, "to");
      allowedRoles = List.copyOf(allowedRoles);
    }
  }

  /** Quy tắc và bảng quyết định của một phiên bản; biên dịch CEL thuộc CORE-05-04. */
  record PolicyRulesView(UUID versionId, List<RuleView> rules, List<DecisionTableView> tables) {

    public PolicyRulesView {
      Objects.requireNonNull(versionId, "versionId");
      rules = List.copyOf(rules);
      tables = List.copyOf(tables);
    }
  }

  /** Quy tắc CEL ({@code policy.policy_rule}). */
  record RuleView(
      String ruleKey,
      String expression,
      String resultType,
      String roundingJson,
      int evalOrder,
      String paramsJson,
      String legalRef) {}

  /** Bảng quyết định ({@code policy.decision_table}). */
  record DecisionTableView(
      String tableKey, String hitPolicy, String dmnHash, String legalRef, String rulesJson) {}

  /** Tham số dạng map để dựng biến {@code tham_so} của CEL (khoá có dấu chấm giữ nguyên). */
  Map<String, Object> parameters(TenantId tenantId, UUID policyPackVersionId);

  /**
   * Tra cứu công thức chính sách đang hiệu lực theo kỳ đánh giá và mã chính sách.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param code mã chính sách quy đổi
   * @return thông tin công thức nếu tồn tại
   */
  Optional<PolicyFormulaView> findActivePolicy(TenantId tenantId, PeriodId periodId, String code);

  /**
   * Ảnh chụp công thức chính sách công bố qua cổng {@link PolicyQueryApi}.
   *
   * @param id định danh chính sách
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param code mã chính sách
   * @param celExpression biểu thức CEL tính điểm / xếp loại
   * @param version phiên bản chính sách
   */
  record PolicyFormulaView(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      String code,
      String celExpression,
      int version) {

    public PolicyFormulaView {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(tenantId, "tenantId must not be null");
      Objects.requireNonNull(periodId, "periodId must not be null");
      Objects.requireNonNull(code, "code must not be null");
      Objects.requireNonNull(celExpression, "celExpression must not be null");
    }
  }
}

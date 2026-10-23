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
 * & Formula} mở cho {@code MOD-03 (product)} và {@code MOD-04 (appraisal)} (TL-01 mục 2.1).
 */
public interface PolicyQueryApi {

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
   * Phiên bản Policy Pack áp cho tổ hợp tenant, kỳ, luồng, khối, đơn vị (CORE-05-03, BR-08-07):
   * {@code policy_binding} khớp cụ thể nhất trước (đơn vị, rồi khối, rồi toàn tenant); chưa có gắn
   * thì chọn theo khoảng hiệu lực và ghim gắn toàn tenant cho kỳ. Rỗng khi không có gói nào.
   *
   * @param flow {@code DANG} hoặc {@code NHA_NUOC}
   * @param subjectGroupId khối đối tượng, có thể {@code null}
   * @param orgUnitId đơn vị, có thể {@code null}
   */
  default Optional<ResolvedPack> resolve(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    return Optional.empty();
  }

  /**
   * Giá trị mặc định của tham số {@code key} trong khối {@code params} của manifest (ví dụ {@code
   * tru_tien_do}, {@code acceptance_sla_days}); giá trị là số, chuỗi, danh sách hoặc bảng.
   */
  default Optional<Object> parameter(TenantId tenantId, UUID versionId, String key) {
    return Optional.empty();
  }

  /** Bảng chuyển trạng thái nhiệm vụ (khoá {@code task_lifecycle}, ADR-04) cho MOD-02. */
  default Optional<Map<String, Object>> taskLifecycle(TenantId tenantId, UUID versionId) {
    return Optional.empty();
  }

  /** Quy tắc CEL và bảng quyết định của phiên bản, theo thứ tự đánh giá. */
  default Optional<PolicyContent> content(TenantId tenantId, UUID versionId) {
    return Optional.empty();
  }

  /**
   * Phiên bản hồ sơ tập khách hàng ghim cùng gói áp cho tổ hợp tenant, kỳ, luồng, khối, đơn vị
   * (CORE-05-12, BS-03 ADR-33) kèm cờ sáu điểm cắm DC-01..DC-06. Rỗng khi gắn của kỳ không ghim hồ
   * sơ: bên gọi dùng {@link PluginPoints#DEFAULTS}, giữ đúng hành vi hiện tại.
   */
  default Optional<ResolvedProfile> customerProfile(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    return Optional.empty();
  }

  /**
   * Cờ sáu điểm cắm của lõi ({@code CustomerProfilePluginPoints}): vắng là tắt, chế độ phân công
   * vắng là {@code CHU_TRI_PHOI_HOP}.
   */
  record PluginPoints(
      boolean workItemScore,
      boolean boundedInheritance,
      boolean responsibilityScope,
      boolean milestoneMetric,
      boolean classificationChain,
      String assignmentMode) {

    public static final String DEFAULT_ASSIGNMENT_MODE = "CHU_TRI_PHOI_HOP";
    public static final PluginPoints DEFAULTS =
        new PluginPoints(false, false, false, false, false, DEFAULT_ASSIGNMENT_MODE);

    public PluginPoints {
      assignmentMode = assignmentMode == null ? DEFAULT_ASSIGNMENT_MODE : assignmentMode;
    }
  }

  /**
   * Hồ sơ đã phân giải: phiên bản đã phát hành, băm nội dung, bản đồ thành phần ({@code components}
   * của bản kê) và cờ điểm cắm.
   */
  record ResolvedProfile(
      UUID profileId,
      String code,
      UUID versionId,
      String version,
      String contentHash,
      Map<String, Object> components,
      PluginPoints pluginPoints) {

    public ResolvedProfile {
      Objects.requireNonNull(profileId, "profileId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(versionId, "versionId");
      Objects.requireNonNull(version, "version");
      components =
          components == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(components));
      pluginPoints = pluginPoints == null ? PluginPoints.DEFAULTS : pluginPoints;
    }
  }

  /**
   * Kết quả phân giải: gói, phiên bản, băm nội dung và khoá gói đã ký để bên gọi kiểm toàn vẹn
   * trước khi dùng (X-PP-09).
   */
  record ResolvedPack(
      UUID policyPackId,
      String code,
      UUID versionId,
      String version,
      String contentHash,
      String bundleObjectKey,
      UUID bindingId,
      boolean frozen) {

    public ResolvedPack {
      Objects.requireNonNull(policyPackId, "policyPackId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(versionId, "versionId");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(contentHash, "contentHash");
      Objects.requireNonNull(bindingId, "bindingId");
    }
  }

  /** Một quy tắc CEL của phiên bản. */
  record RuleView(
      String ruleKey,
      String expression,
      String resultType,
      String roundingJson,
      String legalRef,
      int evalOrder,
      String paramsJson,
      boolean overridable) {}

  /** Một bảng quyết định DMN của phiên bản. */
  record DecisionTableView(
      String tableKey,
      String hitPolicy,
      String dmnXml,
      String rulesJson,
      String explanationTemplatesJson,
      String legalRef) {}

  /** Nội dung đánh giá của một phiên bản. */
  record PolicyContent(
      UUID versionId,
      String version,
      String contentHash,
      List<RuleView> rules,
      List<DecisionTableView> decisionTables) {}

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

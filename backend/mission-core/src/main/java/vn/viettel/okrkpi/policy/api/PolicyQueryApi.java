package vn.viettel.okrkpi.policy.api;

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

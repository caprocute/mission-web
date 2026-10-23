package vn.viettel.okrkpi.config.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng {@code truong_hop_dac_thu} (13 trường hợp đặc thù PL 6, BR-11-05) của Policy Pack áp cho kỳ.
 * {@code config} là mô-đun gốc nên chỉ khai báo cổng; MOD-05 hiện thực (đảo phụ thuộc như {@link
 * PolicySchemaPort}) để MOD-06 đọc bảng mà không phụ thuộc {@code policy :: api}, vì ma trận phụ
 * thuộc của TL-01 không cho {@code dossier → policy} (CORE-06-05).
 */
public interface SpecialCaseRulesPort {

  /**
   * Bảng của phiên bản gói phân giải cho (tenant, kỳ, luồng) ở gắn toàn tenant; rỗng khi không phân
   * giải được gói hoặc gói không khai bảng.
   *
   * @param flow {@code DANG} hoặc {@code NHA_NUOC}
   */
  Optional<List<SpecialCaseRuleEntry>> rules(TenantId tenantId, PeriodId periodId, String flow);

  /**
   * Một dòng bảng: {@code ma}, {@code muc}, {@code legal_ref}, {@code cach_xu_ly}, {@code
   * hieu_ung}, {@code hieu_ung_khi_khong_du} (có thể rỗng), {@code tham_so} (giá trị có thể rỗng
   * khi gói chưa quy định).
   */
  record SpecialCaseRuleEntry(
      String code,
      int section,
      String legalRef,
      String handling,
      String effect,
      String effectWhenInsufficient,
      Map<String, Object> params) {

    public SpecialCaseRuleEntry {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(legalRef, "legalRef");
      Objects.requireNonNull(effect, "effect");
      params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(params);
    }
  }
}

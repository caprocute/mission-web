package vn.viettel.okrkpi.appraisal.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng trường hợp đặc thù PL 6 mà MOD-04 khai báo và MOD-06 hiện thực từ {@code
 * dossier.special_case} (CORE-04-02, QT-09 ngoại lệ 2). Đảo phụ thuộc vì ma trận chỉ cho {@code
 * dossier → appraisal} (wiki trang 14 mục 59). Chưa có bean thì không có trường hợp đặc thù.
 */
public interface AppraisalSpecialCases {

  /** Trường hợp đặc thù có hiệu lực trong kỳ. */
  List<SpecialCase> forPeriod(TenantId tenant, UUID periodId);

  /** Cách xử lý của Policy Pack cho một trường hợp đặc thù. */
  enum Treatment {
    /** Không lập hồ sơ quý. */
    KHONG_LAP_HO_SO,
    /** Lập hồ sơ nhưng không xếp loại. */
    KHONG_XEP_LOAI,
    /** Đơn vị cũ đánh giá, đơn vị mới cho ý kiến. */
    DON_VI_CU_DANH_GIA
  }

  /**
   * Một trường hợp đặc thù.
   *
   * @param personId cán bộ
   * @param flow luồng áp dụng ({@code DANG}, {@code NHA_NUOC}), {@code null} khi áp cả hai
   * @param treatment cách xử lý
   */
  record SpecialCase(UUID personId, String flow, Treatment treatment) {
    public SpecialCase {
      Objects.requireNonNull(personId, "personId");
      Objects.requireNonNull(treatment, "treatment");
    }

    public boolean appliesTo(String f) {
      return flow == null || flow.equals(f);
    }
  }
}

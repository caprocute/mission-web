package vn.viettel.okrkpi.appraisal.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đảo phụ thuộc: MOD-04 khai báo, MOD-06 hiện thực (CORE-06-05, TL-03 "Schema dossier": MOD-04
 * đọc trường hợp đặc thù qua cổng công bố của MOD-06). MOD-04 gọi khi lập hồ sơ quý (consumer
 * EVT-09) để biết hệ quả xếp loại mà không phụ thuộc ngược {@code dossier}.
 */
public interface SpecialCaseQuery {

  /**
   * Đối chiếu lại và trả các trường hợp đặc thù của người trong kỳ; gọi lặp không tạo bản ghi trùng
   * (idempotent theo người, kỳ, mã trường hợp).
   */
  List<SpecialCaseView> casesOf(TenantId tenantId, PeriodId periodId, UUID personId);

  /**
   * Một trường hợp: mã theo Policy Pack, hệ quả ({@code KHONG_XEP_LOAI}, {@code
   * XEP_LOAI_TAI_DON_VI_CU}, {@code BAO_LUU}, {@code XEP_LOAI_BINH_THUONG}, {@code CHO_KET_LUAN}),
   * căn cứ, khoảng áp dụng và đã được cơ quan tổ chức xác nhận chưa.
   */
  record SpecialCaseView(
      UUID id,
      String caseCode,
      String effect,
      String legalRef,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean confirmed) {}
}

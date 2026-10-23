package vn.viettel.okrkpi.oversight.domain.complaint;

import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.CHO_KET_LUAN;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DANG_GIAI_TRINH;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DANG_THI_HANH;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DA_TIEP_NHAN;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DA_TRA_LOI;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DONG;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.KHONG_THU_LY;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.MOI;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.RUT;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Bảng chuyển trạng thái khiếu nại của TL-02b QT-12 "Trạng thái đối tượng", thêm {@code RUT} theo
 * TL-03 và ngoại lệ "rút trước khi có kết luận". Nháp ({@code null}) chỉ sang {@code MOI} khi ký
 * xong.
 */
public final class ComplaintTransitions {

  private static final Map<ComplaintStatus, Set<ComplaintStatus>> NEXT =
      new EnumMap<>(ComplaintStatus.class);

  static {
    NEXT.put(MOI, EnumSet.of(DA_TIEP_NHAN, KHONG_THU_LY, RUT));
    NEXT.put(DA_TIEP_NHAN, EnumSet.of(DANG_GIAI_TRINH, CHO_KET_LUAN, RUT));
    NEXT.put(DANG_GIAI_TRINH, EnumSet.of(CHO_KET_LUAN, RUT));
    NEXT.put(CHO_KET_LUAN, EnumSet.of(DA_TRA_LOI, RUT));
    NEXT.put(DA_TRA_LOI, EnumSet.of(DANG_THI_HANH, DONG));
    NEXT.put(DANG_THI_HANH, EnumSet.of(DONG));
    NEXT.put(KHONG_THU_LY, EnumSet.of(DONG));
    NEXT.put(RUT, EnumSet.of(DONG));
    NEXT.put(DONG, EnumSet.noneOf(ComplaintStatus.class));
  }

  private ComplaintTransitions() {}

  /** {@code from} là {@code null} khi khiếu nại còn là nháp. */
  public static boolean allowed(ComplaintStatus from, ComplaintStatus to) {
    if (from == null) {
      return to == MOI;
    }
    return NEXT.get(from).contains(to);
  }

  /** Chưa có văn bản trả lời: người khiếu nại còn rút được. */
  public static boolean beforeConclusion(ComplaintStatus s) {
    return allowed(s, RUT);
  }
}

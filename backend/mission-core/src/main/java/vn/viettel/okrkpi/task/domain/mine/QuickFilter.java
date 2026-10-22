package vn.viettel.okrkpi.task.domain.mine;

import java.time.LocalDate;
import java.util.Set;

/**
 * Bộ lọc nhanh của SCR-0503 trường 1, tính từ {@code task.task.status}, {@code is_overdue} và hạn
 * hiệu lực. "Chậm" và "Sắp hạn" chỉ xét việc còn mở ({@code DA_GIAO}, {@code DANG_THUC_HIEN}); "Sắp
 * hạn" là hạn từ hôm nay tới hết số ngày làm việc tham số (suy luận: TL-02a không ghi ngưỡng).
 */
public enum QuickFilter {
  TAT_CA,
  CHAM,
  SAP_HAN,
  CHO_NGHIEM_THU,
  HOAN_THANH;

  public static final Set<String> OPEN = Set.of("DA_GIAO", "DANG_THUC_HIEN");

  /** Nhiệm vụ thuộc bộ lọc này với hôm nay và ngày cuối của khoảng "sắp hạn". */
  public boolean matches(
      String status, boolean overdue, LocalDate due, LocalDate today, LocalDate soonUntil) {
    boolean open = OPEN.contains(status);
    boolean late = open && (overdue || (due != null && due.isBefore(today)));
    return switch (this) {
      case TAT_CA -> true;
      case CHAM -> late;
      case SAP_HAN -> open && !late && due != null && !due.isAfter(soonUntil);
      case CHO_NGHIEM_THU -> "CHO_NGHIEM_THU".equals(status);
      case HOAN_THANH -> "HOAN_THANH".equals(status);
    };
  }
}

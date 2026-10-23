package vn.viettel.okrkpi.workflow.domain.replica;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tình trạng công tác của nhân sự theo CHECK của {@code org_replica.person_v.employment_status}
 * (TL-03 "Bảng org_replica.person_v") và trường {@code status} của EVT-16 {@code
 * org.person.changed.v1}.
 */
public enum EmploymentStatus {
  /** Đang công tác. */
  DANG_CONG_TAC(true),
  /** Tạm nghỉ (nghỉ dài ngày, tạm đình chỉ...): vẫn còn là cán bộ của đơn vị. */
  TAM_NGHI(true),
  /** Đã nghỉ hưu. */
  NGHI_HUU(false),
  /** Đã thôi việc. */
  THOI_VIEC(false);

  private final boolean stillWorking;

  EmploymentStatus(boolean stillWorking) {
    this.stillWorking = stillWorking;
  }

  /** Đúng khi người còn làm việc, nên còn nhận được bước xử lý chuyển tới. */
  public boolean stillWorking() {
    return stillWorking;
  }

  /** Giá trị đúng tên hằng; rỗng khi giá trị lạ hoặc {@code null}. */
  public static Optional<EmploymentStatus> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String v = raw.trim();
    return Arrays.stream(values()).filter(s -> s.name().equals(v)).findFirst();
  }

  /**
   * Người có tình trạng {@code raw} trong bản sao có còn nhận được bước xử lý hay không. Giá trị lạ
   * (ngoài CHECK của TL-03) bị coi là không còn làm việc để không giao việc cho người không rõ tình
   * trạng (suy luận, đóng an toàn).
   */
  public static boolean allowsAssignment(String raw) {
    return parse(raw).map(EmploymentStatus::stillWorking).orElse(false);
  }
}

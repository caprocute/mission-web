package vn.viettel.okrkpi.tools.buildsmoke;

import java.time.LocalDate;
import vn.viettel.okrkpi.tools.buildsmoke.domain.WorkingDayCounter;

/** Điểm vào của ảnh mẫu (kiểm Jib): in số ngày làm việc của tuần đầu tháng 10/2026. */
public final class BuildSmokeMain {

  private BuildSmokeMain() {}

  public static void main(String[] args) {
    int days = WorkingDayCounter.count(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11));
    System.out.println("build-smoke: " + days + " ngày làm việc");
  }
}

package vn.viettel.okrkpi.workflow.infrastructure.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Bộ chuyển mặc định: lịch khung cấm từ cấu hình, luồng nền chạy test có hàng đợi giới hạn. */
class ReleaseAdaptersTest {

  @Test
  void lichTuCauHinh() {
    ConfiguredRolloutCalendar calendar =
        new ConfiguredRolloutCalendar(
            "Asia/Ho_Chi_Minh",
            List.of("08:00-11:30", " ", "13:30-17:00"),
            false,
            List.of("2026-12-31", ""),
            3);
    assertThat(calendar.window().peakHours()).hasSize(2);
    assertThat(calendar.window().blockedAt(Instant.parse("2026-12-30T15:00:00Z"))).isPresent();
    assertThat(calendar.window().blockedAt(Instant.parse("2026-10-20T15:00:00Z"))).isEmpty();
    assertThat(TemplateReleaseConfiguration.split(" CELL-A, ,CELL-B "))
        .containsExactly("CELL-A", "CELL-B");
    assertThat(TemplateReleaseConfiguration.split(null)).isEmpty();
  }

  @Test
  void luongNenChayViecVaBoQuaLoi() throws InterruptedException {
    try (ExecutorTemplateTestDispatcher dispatcher = new ExecutorTemplateTestDispatcher(2)) {
      CountDownLatch done = new CountDownLatch(1);
      dispatcher.dispatch(
          () -> {
            throw new IllegalStateException("lỗi giả lập");
          });
      dispatcher.dispatch(done::countDown);
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}

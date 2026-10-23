package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;

/** Lấy mẫu R2 tất định theo (ngày, đối tượng), ít nhất một đối tượng, không vượt trần. */
class LedgerSourceReconcilerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

  private static LedgerSourceReconciler withRate(double rate) {
    return new LedgerSourceReconciler(
        List.of(),
        (t, a, f, x) -> List.of(),
        new ReconcileMetrics(new SimpleMeterRegistry(), Clock.systemUTC()),
        new ObjectMapper(),
        Clock.systemUTC(),
        rate,
        3,
        Duration.ofHours(1));
  }

  @Test
  void layMauTatDinhItNhatMotDoiTuong() {
    // Mã cố định để kết quả không phụ thuộc lần chạy; tỷ lệ 1e-9 thì gần như chắc chắn không mã nào có
    // điểm dưới ngưỡng, nên chỉ còn quy tắc "ít nhất một đối tượng".
    List<UUID> ids =
        IntStream.range(0, 50)
            .mapToObj(i -> UUID.nameUUIDFromBytes(("doi-tuong-" + i).getBytes(StandardCharsets.UTF_8)))
            .toList();
    LedgerSourceReconciler few = withRate(1e-9);
    assertThat(few.sample(DAY, ids)).hasSize(1).isEqualTo(few.sample(DAY, ids.reversed()));
    assertThat(withRate(1.0).sample(DAY, ids)).hasSize(3);
  }
}

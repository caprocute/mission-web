package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.port.AlertMetrics;

/**
 * Đếm kết quả xử lý cảnh báo theo dấu hiệu ({@code mission.oversight.alert.decisions}, xuất qua
 * OpenTelemetry); tỷ lệ hợp lệ của một dấu hiệu là số {@code XAC_NHAN_HOP_LE} cộng {@code
 * KHONG_VI_PHAM} trên tổng (BR-14-09, suy luận).
 */
public class MicrometerAlertMetrics implements AlertMetrics {

  static final String METER = "mission.oversight.alert.decisions";

  private final MeterRegistry registry;

  public MicrometerAlertMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public void decided(String signalCode, String outcome) {
    Counter.builder(METER)
        .description("Kết quả xử lý cảnh báo bất thường theo dấu hiệu")
        .tag("signal", signalCode)
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }
}

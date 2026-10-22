package vn.viettel.okrkpi.docgen.infrastructure.render;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import vn.viettel.okrkpi.docgen.application.render.RenderMetrics;

/**
 * {@code docgen_render_seconds{form_code, outcome}}: thời gian sinh mỗi tệp và tỷ lệ lỗi (đếm theo
 * {@code outcome}); độ dài hàng đợi {@code docgen_render_queue} đăng ký ở cấu hình worker.
 */
public class MicrometerRenderMetrics implements RenderMetrics {

  private final MeterRegistry registry;

  public MicrometerRenderMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void rendered(String formCode, String outcome, Duration took) {
    Timer.builder("docgen.render")
        .description("Thời gian sinh một biểu mẫu")
        .tag("form_code", formCode)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)
        .record(took);
  }
}

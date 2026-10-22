package vn.viettel.okrkpi.docgen.application.render;

import java.time.Duration;

/** Số đo đường ống sinh: thời gian sinh mỗi tệp và kết quả (tỷ lệ lỗi). */
public interface RenderMetrics {

  /**
   * @param outcome {@code XONG}, {@code LOI} hoặc {@code THU_LAI}
   */
  void rendered(String formCode, String outcome, Duration took);

  RenderMetrics NONE = (formCode, outcome, took) -> {};
}

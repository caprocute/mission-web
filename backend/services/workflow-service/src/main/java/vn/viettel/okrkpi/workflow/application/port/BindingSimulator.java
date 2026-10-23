package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;

/**
 * Engine Flowable thử tách biệt, không dùng CSDL của cell: chạy mẫu với tham số cho một hồ sơ mẫu.
 */
public interface BindingSimulator {

  /**
   * Chạy một hồ sơ mẫu.
   *
   * @param template mẫu
   * @param params tham số đã qua linter
   * @param sample ca mẫu
   * @param skippedOrders thứ tự (từ 1) các cấp bị bỏ qua với biến của ca
   */
  SampleRun run(
      TemplateInfo template,
      Map<String, Object> params,
      SimulationSample sample,
      List<Integer> skippedOrders);
}

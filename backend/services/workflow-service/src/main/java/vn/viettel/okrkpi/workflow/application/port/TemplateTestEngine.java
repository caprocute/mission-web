package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;

/**
 * Engine Flowable kiểm thử dùng riêng, không phải engine của cell (BS-01 "Kiểm thử"): nạp mẫu, chạy
 * mọi kịch bản với từng bộ tham số và đếm câu SQL của mỗi lần hoàn thành user task.
 */
public interface TemplateTestEngine {

  /**
   * Chạy bộ test.
   *
   * @param templateCode mã mẫu (id process)
   * @param bpmnXml nội dung BPMN
   * @param bindings các bộ tham số; rỗng thì chạy với tham số mặc định
   * @return kết quả từng ca
   */
  List<TestCaseResult> run(String templateCode, String bpmnXml, List<BindingUsage> bindings);

  /** Số kịch bản của bộ test mặc định ({@code testCount}). */
  int scenarioCount();
}

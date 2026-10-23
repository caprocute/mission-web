package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;

/**
 * Linter máy chủ của mẫu luồng soạn trên SCR-1505: quy tắc chống mạng nhện của TL-01 G2 và giới hạn
 * tải của BS-01. Máy chủ là nơi quyết định; linter trên trình duyệt chỉ để phản hồi nhanh.
 */
public interface TemplateBpmnLinter {

  List<TemplateLintFinding> lint(String templateCode, String bpmnXml);
}

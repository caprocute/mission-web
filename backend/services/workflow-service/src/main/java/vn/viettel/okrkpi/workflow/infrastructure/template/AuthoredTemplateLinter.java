package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.List;
import vn.viettel.okrkpi.workflow.application.port.TemplateBpmnLinter;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;

/**
 * Linter máy chủ của mẫu soạn trên SCR-1505 dùng lại {@link BpmnTemplateLinter} (TL-01 G2 và giới
 * hạn tải BS-01): chỉ external worker task hoặc DMN, không signal phát rộng, không định nghĩa gắn
 * tenant, một tầng call activity, ≤ 12 user task mỗi nhánh, ≤ 50 phần tử đa thể hiện.
 */
public class AuthoredTemplateLinter implements TemplateBpmnLinter {

  private final BpmnTemplateLinter linter =
      new BpmnTemplateLinter(TemplateDeployer.PURE_DELEGATES, test -> true);

  @Override
  public List<TemplateLintFinding> lint(String templateCode, String bpmnXml) {
    return linter.lintAuthored(templateCode, bpmnXml).stream()
        .map(v -> new TemplateLintFinding(v.rule().name(), null, v.detail()))
        .toList();
  }
}

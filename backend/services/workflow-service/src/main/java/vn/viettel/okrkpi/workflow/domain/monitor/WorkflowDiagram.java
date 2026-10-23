package vn.viettel.okrkpi.workflow.domain.monitor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;

/**
 * Sơ đồ tiến trình với bước hiện tại ({@code getWorkflowDiagram}, BS-01 SCR-1506): định nghĩa BPMN
 * của phiên bản đang chạy và các phần tử đã qua, đang chờ dạng dữ liệu (không HTML, không SVG).
 *
 * @param tenantId tenant của tiến trình
 * @param businessKey đối tượng nghiệp vụ
 * @param templateCode mã mẫu luồng
 * @param templateVersion phiên bản định nghĩa
 * @param bpmnXml BPMN 2.0 XML
 * @param activeActivityIds phần tử đang chờ
 * @param completedActivityIds phần tử đã qua, theo thứ tự đi qua, không lặp
 */
public record WorkflowDiagram(
    UUID tenantId,
    BusinessKey businessKey,
    String templateCode,
    Integer templateVersion,
    String bpmnXml,
    List<String> activeActivityIds,
    List<String> completedActivityIds) {

  public WorkflowDiagram {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(businessKey, "businessKey");
    Objects.requireNonNull(bpmnXml, "bpmnXml");
    activeActivityIds = activeActivityIds == null ? List.of() : List.copyOf(activeActivityIds);
    completedActivityIds =
        completedActivityIds == null ? List.of() : List.copyOf(completedActivityIds);
  }
}

package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalLevelsAssembler;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalLevel;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * Listener phân giải cấp duyệt ({@code ${phanGiaiCapDuyet}}) gắn vào cuối bước "Chọn cấp duyệt" của
 * mẫu (TL-01 G2): đọc kết quả DMN {@code chon-cap-duyet} (engine luôn trả mảng JSON cho chính sách
 * COLLECT), dựng {@code approvalLevels} dạng mảng JSON nhỏ ({@code thuTu}, {@code role}, {@code
 * scope}, {@code orgUnitId}, {@code dueDate}, {@code hanNgayLamViec}) rồi xoá biến kết quả DMN.
 *
 * <p>Chỉ tính toán và đọc bản sao cục bộ trong giao dịch khởi tạo, không gọi hệ ngoài (quy tắc 4).
 * Mảng vượt ngân sách {@link ApprovalVariables#MAX_LEVELS_BYTES} làm hỏng khởi tạo (quy tắc 5).
 */
@Component("phanGiaiCapDuyet")
public class ApprovalLevelsListener implements ExecutionListener {

  private static final long serialVersionUID = 1L;

  private final transient ApprovalLevelsAssembler assembler;
  private final transient ObjectMapper json;

  public ApprovalLevelsListener(ApprovalLevelsAssembler assembler, ObjectMapper json) {
    this.assembler = assembler;
    this.json = json;
  }

  @Override
  public void notify(DelegateExecution execution) {
    ApprovalLevelsAssembler.Inputs inputs =
        new ApprovalLevelsAssembler.Inputs(
            text(execution, ApprovalVariables.TENANT_ID),
            text(execution, ApprovalVariables.KHOI),
            text(execution, ApprovalVariables.DON_VI_ID),
            Boolean.TRUE.equals(execution.getVariable(ApprovalVariables.LA_NGUOI_DUNG_DAU)),
            text(execution, ApprovalVariables.CAP_TUY_CHINH),
            rows(execution.getVariable(ApprovalVariables.DMN_RESULT)));
    ArrayNode levels = toJson(assembler.assemble(inputs));
    int size = levels.toString().getBytes(StandardCharsets.UTF_8).length;
    if (size > ApprovalVariables.MAX_LEVELS_BYTES) {
      throw new ApprovalSetupException("Biến cấp duyệt vượt ngân sách kích thước");
    }
    execution.setVariable(ApprovalVariables.APPROVAL_LEVELS, levels);
    execution.removeVariable(ApprovalVariables.DMN_RESULT);
  }

  ArrayNode toJson(List<ApprovalLevel> levels) {
    ArrayNode array = json.createArrayNode();
    for (ApprovalLevel level : levels) {
      ObjectNode node = array.addObject();
      node.put("thuTu", level.order());
      node.put("role", level.role());
      node.put("scope", level.scope().name());
      node.put("orgUnitId", level.orgUnitId().toString());
      node.put("dueDate", level.dueAt().toString());
      node.put("hanNgayLamViec", level.workingDays());
    }
    return array;
  }

  private List<Map<String, Object>> rows(Object raw) {
    if (raw == null) {
      return List.of();
    }
    JsonNode node = raw instanceof JsonNode j ? j : json.valueToTree(raw);
    List<Map<String, Object>> rows = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(row -> rows.add(row(row)));
    } else if (node.isObject()) {
      rows.add(row(node));
    }
    return rows;
  }

  private static Map<String, Object> row(JsonNode node) {
    Map<String, Object> row = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            e -> row.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
    return row;
  }

  private static String text(DelegateExecution execution, String name) {
    Object value = execution.getVariable(name);
    return value == null ? null : value.toString();
  }
}

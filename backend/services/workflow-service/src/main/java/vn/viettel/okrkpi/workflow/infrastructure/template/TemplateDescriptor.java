package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.List;
import java.util.Map;

/**
 * Khai báo đi kèm mỗi mẫu luồng ({@code processes/<mã>.mau.json}): mô tả nghiệp vụ, người sở hữu,
 * bộ kiểm thử, bảng quyết định dùng, ngân sách biến (TL-01 G2 quy tắc 5, 7) và giới hạn đa thể hiện
 * song song (quy tắc 6).
 *
 * @param templateCode mã mẫu, trùng {@code id} của process
 * @param majorVersion phiên bản chính
 * @param description mô tả nghiệp vụ
 * @param owner người hoặc nhóm sở hữu
 * @param trace truy vết QT → SCR → API → MOD
 * @param decisionTables khoá bảng quyết định mẫu dùng
 * @param flowTests lớp kiểm thử Flowable của mẫu
 * @param decisionTests lớp kiểm thử DMN của mẫu
 * @param variableBudget ngân sách byte của từng biến tiến trình
 * @param parallelLimits giới hạn phần tử của từng đa thể hiện song song theo mã phần tử
 */
public record TemplateDescriptor(
    String templateCode,
    int majorVersion,
    String description,
    String owner,
    String trace,
    List<String> decisionTables,
    List<String> flowTests,
    List<String> decisionTests,
    Map<String, Integer> variableBudget,
    Map<String, Integer> parallelLimits) {

  public TemplateDescriptor {
    decisionTables = decisionTables == null ? List.of() : List.copyOf(decisionTables);
    flowTests = flowTests == null ? List.of() : List.copyOf(flowTests);
    decisionTests = decisionTests == null ? List.of() : List.copyOf(decisionTests);
    variableBudget = variableBudget == null ? Map.of() : Map.copyOf(variableBudget);
    parallelLimits = parallelLimits == null ? Map.of() : Map.copyOf(parallelLimits);
  }

  /** Tổng ngân sách biến. */
  public int totalVariableBytes() {
    return variableBudget.values().stream().mapToInt(Integer::intValue).sum();
  }
}

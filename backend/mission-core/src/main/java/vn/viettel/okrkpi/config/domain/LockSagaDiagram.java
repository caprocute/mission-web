package vn.viettel.okrkpi.config.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Sơ đồ BPMN 2.0 của mẫu saga {@code chot-ky} dựng từ sáu bước chiếu ở {@code
 * config.period_lock_step} ({@code getPeriodLockDiagram}, SCR-0801 "Xem BPMN"). MOD-08 không đọc
 * định nghĩa tiến trình của SVC-06 nên dựng sơ đồ tuyến tính sáu hoạt động, mã hoạt động theo ví dụ
 * của hợp đồng ({@code buoc0TienDieuKien} … , suy luận); có kèm phần hình (BPMNDI) để trình xem
 * BPMN vẽ được. Tên bước lấy từ danh mục cố định {@link LockStep#NAMES}, không lấy từ dữ liệu.
 *
 * @param bpmnXml định nghĩa BPMN
 * @param activeActivityIds hoạt động đang chạy
 * @param completedActivityIds hoạt động đã xong
 * @param failedActivityIds hoạt động lỗi
 */
public record LockSagaDiagram(
    String bpmnXml,
    List<String> activeActivityIds,
    List<String> completedActivityIds,
    List<String> failedActivityIds) {

  /** Mẫu saga của hợp đồng. */
  public static final String TEMPLATE_KEY = "chot-ky";

  /** Mã hoạt động BPMN của sáu bước, theo thứ tự bước 0–5. */
  public static final List<String> ACTIVITY_IDS =
      List.of(
          "buoc0TienDieuKien",
          "buoc1KhoaKy",
          "buoc2TinhDiem",
          "buoc3SinhMau",
          "buoc4KySo",
          "buoc5HoanTat");

  private static final int TASK_W = 120;
  private static final int TASK_H = 80;
  private static final int GAP = 50;
  private static final int TOP = 60;
  private static final int EVENT = 36;

  public LockSagaDiagram {
    activeActivityIds = List.copyOf(activeActivityIds);
    completedActivityIds = List.copyOf(completedActivityIds);
    failedActivityIds = List.copyOf(failedActivityIds);
  }

  /** Dựng sơ đồ và tô trạng thái từ sáu bước (thiếu bước nào coi như {@code CHO}). */
  public static LockSagaDiagram of(List<LockStep> steps) {
    List<String> active = new ArrayList<>();
    List<String> completed = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (LockStep s : steps) {
      if (s.stepNo() < 0 || s.stepNo() >= ACTIVITY_IDS.size()) {
        continue;
      }
      String id = ACTIVITY_IDS.get(s.stepNo());
      if (s.done()) {
        completed.add(id);
      } else if ("DANG_CHAY".equals(s.status())) {
        active.add(id);
      } else if ("LOI".equals(s.status())) {
        failed.add(id);
      }
    }
    return new LockSagaDiagram(xml(), active, completed, failed);
  }

  private static String xml() {
    StringBuilder process = new StringBuilder();
    StringBuilder shapes = new StringBuilder();
    process.append("<startEvent id=\"batDau\" name=\"Bắt đầu\"/>");
    int x = 40;
    shapes.append(shape("batDau", x, TOP + (TASK_H - EVENT) / 2, EVENT, EVENT));
    String previous = "batDau";
    int previousRight = x + EVENT;
    x = previousRight + GAP;
    for (int i = 0; i < ACTIVITY_IDS.size(); i++) {
      String id = ACTIVITY_IDS.get(i);
      process
          .append("<serviceTask id=\"")
          .append(id)
          .append("\" name=\"")
          .append(escape(LockStep.NAMES.get(i)))
          .append("\"/>");
      process.append(flow(previous, id));
      shapes.append(shape(id, x, TOP, TASK_W, TASK_H));
      shapes.append(edge(previous, id, previousRight, x));
      previous = id;
      previousRight = x + TASK_W;
      x = previousRight + GAP;
    }
    process.append("<endEvent id=\"ketThuc\" name=\"Kết thúc\"/>");
    process.append(flow(previous, "ketThuc"));
    shapes.append(shape("ketThuc", x, TOP + (TASK_H - EVENT) / 2, EVENT, EVENT));
    shapes.append(edge(previous, "ketThuc", previousRight, x));
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
        + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
        + " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
        + " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
        + " id=\"dinhNghiaChotKy\" targetNamespace=\"https://okrkpi.vn/bpmn/chot-ky\">"
        + "<process id=\"chotKy\" name=\"Chốt kỳ\" isExecutable=\"false\">"
        + process
        + "</process>"
        + "<bpmndi:BPMNDiagram id=\"soDoChotKy\"><bpmndi:BPMNPlane id=\"matPhangChotKy\""
        + " bpmnElement=\"chotKy\">"
        + shapes
        + "</bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>";
  }

  private static String flow(String from, String to) {
    return "<sequenceFlow id=\""
        + flowId(from, to)
        + "\" sourceRef=\""
        + from
        + "\" targetRef=\""
        + to
        + "\"/>";
  }

  private static String flowId(String from, String to) {
    return "luong_" + from + "_" + to;
  }

  private static String shape(String id, int x, int y, int w, int h) {
    return "<bpmndi:BPMNShape id=\""
        + id
        + "_hinh\" bpmnElement=\""
        + id
        + "\"><dc:Bounds x=\""
        + x
        + "\" y=\""
        + y
        + "\" width=\""
        + w
        + "\" height=\""
        + h
        + "\"/></bpmndi:BPMNShape>";
  }

  private static String edge(String from, String to, int x1, int x2) {
    int y = TOP + TASK_H / 2;
    String id = flowId(from, to);
    return "<bpmndi:BPMNEdge id=\""
        + id
        + "_hinh\" bpmnElement=\""
        + id
        + "\"><di:waypoint x=\""
        + x1
        + "\" y=\""
        + y
        + "\"/><di:waypoint x=\""
        + x2
        + "\" y=\""
        + y
        + "\"/></bpmndi:BPMNEdge>";
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}

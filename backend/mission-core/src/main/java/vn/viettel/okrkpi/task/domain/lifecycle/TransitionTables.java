package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bảng chuyển mặc định đúng TL-02a QT-05 (cùng nội dung với {@code
 * data/policy-pack/khoi-dang/quy-dinh-moi/task_lifecycle.yaml} 2.1.0) và bộ đọc khối {@code
 * task_lifecycle} của Policy Pack.
 */
public final class TransitionTables {

  /** Phiên bản ghi vào {@code state_machine_version} khi tenant chưa có Policy Pack. */
  public static final String DEFAULT_VERSION = "mac-dinh@2.1.0";

  private static final String HUY_DA_DUYET =
      "change_request.request_type == 'HUY' && change_request.status == 'DA_DUYET'"
          + " && !deliverables.exists(d, d.status == 'DA_XAC_NHAN')";

  private TransitionTables() {}

  public static TransitionTable defaults() {
    Set<String> manager = Set.of("manager");
    Set<String> member = Set.of("member");
    Set<String> managerOrLeader = Set.of("manager", "leader");
    return new TransitionTable(
        List.of(
            new Transition(TaskStatus.NHAP, "ASSIGN", TaskStatus.DA_GIAO, manager, null, false),
            new Transition(
                TaskStatus.DA_GIAO,
                "ACCEPT",
                TaskStatus.DANG_THUC_HIEN,
                member,
                "actor.person_id == task.chu_tri_id",
                false),
            new Transition(
                TaskStatus.DANG_THUC_HIEN,
                "SUBMIT",
                TaskStatus.CHO_NGHIEM_THU,
                member,
                "task.so_san_pham_da_nop >= task.ke_hoach",
                false),
            new Transition(
                TaskStatus.CHO_NGHIEM_THU,
                "REWORK",
                TaskStatus.DANG_THUC_HIEN,
                manager,
                "actor.person_id == task.nguoi_nghiem_thu_id",
                true),
            new Transition(
                TaskStatus.CHO_NGHIEM_THU,
                "APPROVE",
                TaskStatus.HOAN_THANH,
                manager,
                "deliverables.all(d, d.status == 'DA_XAC_NHAN')",
                false),
            new Transition(
                TaskStatus.DANG_THUC_HIEN,
                "SUSPEND",
                TaskStatus.TAM_DUNG,
                managerOrLeader,
                "change_request.request_type == 'TAM_DUNG' && change_request.status == 'DA_DUYET'",
                true),
            new Transition(
                TaskStatus.TAM_DUNG,
                "RESUME",
                TaskStatus.DANG_THUC_HIEN,
                manager,
                "actor.person_id == task.nguoi_giao_id",
                false),
            new Transition(
                TaskStatus.DA_GIAO, "CANCEL", TaskStatus.HUY, managerOrLeader, HUY_DA_DUYET, true),
            new Transition(
                TaskStatus.DANG_THUC_HIEN,
                "CANCEL",
                TaskStatus.HUY,
                managerOrLeader,
                HUY_DA_DUYET,
                true),
            new Transition(
                TaskStatus.TAM_DUNG,
                "CANCEL",
                TaskStatus.HUY,
                managerOrLeader,
                HUY_DA_DUYET,
                true)),
        DEFAULT_VERSION);
  }

  /**
   * Đọc khối {@code task_lifecycle} ({@code transitions[]} với {@code from}, {@code command},
   * {@code to}, {@code allowedRoles}, {@code guardCel}, {@code reasonRequired}). Trạng thái hoặc
   * lệnh ngoài tập đóng làm hỏng cả bảng ({@code IllegalArgumentException}) vì Policy Pack không
   * được thêm trạng thái.
   */
  public static TransitionTable fromPolicy(Map<String, Object> lifecycle, String policyVersion) {
    if (!(lifecycle.get("transitions") instanceof Collection<?> rows)) {
      throw new IllegalArgumentException("task_lifecycle thiếu danh sách transitions");
    }
    List<Transition> transitions = new ArrayList<>();
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> m)) {
        throw new IllegalArgumentException("Dòng transitions không hợp lệ: " + row);
      }
      String command = string(m.get("command"));
      TaskCommand.of(command, null);
      Set<String> roles = new LinkedHashSet<>();
      if (m.get("allowedRoles") instanceof Collection<?> rs) {
        rs.forEach(r -> roles.add(String.valueOf(r)));
      }
      transitions.add(
          new Transition(
              TaskStatus.valueOf(string(m.get("from"))),
              command,
              TaskStatus.valueOf(string(m.get("to"))),
              roles,
              m.get("guardCel") == null ? null : String.valueOf(m.get("guardCel")),
              Boolean.TRUE.equals(m.get("reasonRequired"))
                  || "true".equalsIgnoreCase(String.valueOf(m.get("reasonRequired")))));
    }
    return new TransitionTable(transitions, policyVersion);
  }

  private static String string(Object v) {
    if (v == null) {
      throw new IllegalArgumentException("Dòng transitions thiếu trường bắt buộc");
    }
    return String.valueOf(v);
  }
}

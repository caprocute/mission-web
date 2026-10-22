package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Objects;
import java.util.Set;

/**
 * Một dòng của bảng chuyển trạng thái, do Policy Pack cung cấp.
 *
 * @param from trạng thái nguồn
 * @param command mã lệnh ({@link TaskCommand#code()})
 * @param to trạng thái đích
 * @param allowedRoles vai trò realm được thực hiện (ma trận phân quyền)
 * @param guardCel điều kiện CEL trả {@code bool}, {@code null} khi không có
 * @param reasonRequired lệnh bắt buộc nêu lý do
 */
public record Transition(
    TaskStatus from,
    String command,
    TaskStatus to,
    Set<String> allowedRoles,
    String guardCel,
    boolean reasonRequired) {

  public Transition {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(to, "to");
    allowedRoles = Set.copyOf(allowedRoles == null ? Set.of() : allowedRoles);
    guardCel = guardCel == null || guardCel.isBlank() ? null : guardCel.strip();
  }

  /** Khoá {@code from:command} của bảng. */
  public String key() {
    return key(from, command);
  }

  static String key(TaskStatus from, String command) {
    return from.name() + ":" + command;
  }
}

package vn.viettel.okrkpi.shared.audit;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Người (hoặc tác vụ hệ thống) đang giải mã C2, kèm ngữ cảnh phiên BR-00-08.
 *
 * @param actorId người xem; {@code null} khi là tác vụ hệ thống
 * @param assignmentId phân công đang chọn, {@code null} khi không có
 * @param role vai trò đang chọn, {@code null} khi không có
 * @param system {@code true} khi do tác vụ hệ thống của dịch vụ (tính điểm, sinh biểu mẫu)
 */
public record C2Actor(ActorId actorId, UUID assignmentId, String role, boolean system) {

  /** Tác vụ hệ thống: tác nhân là tài khoản dịch vụ, ghi trong tiêu đề {@code actorid} là rỗng. */
  public static final C2Actor SYSTEM = new C2Actor(null, null, null, true);
}

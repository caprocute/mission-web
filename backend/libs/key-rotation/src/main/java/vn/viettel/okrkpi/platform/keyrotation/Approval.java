package vn.viettel.okrkpi.platform.keyrotation;

import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Phê duyệt hai người cho thao tác mã hoá lại khi nghi lộ DEK (nguyên tắc bốn mắt của thao tác nhạy
 * cảm, suy luận theo TL-04): người đề nghị và người duyệt phải khác nhau.
 *
 * @param requestedBy người đề nghị (thường là quản trị khoá)
 * @param approvedBy người duyệt
 * @param reference số phiếu sự cố hoặc phiếu thay đổi, ghi vào sự kiện kiểm toán
 */
public record Approval(ActorId requestedBy, ActorId approvedBy, String reference) {

  public Approval {
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(approvedBy, "approvedBy");
    if (requestedBy.equals(approvedBy)) {
      throw new IllegalArgumentException("Mã hoá lại DEK cần hai người khác nhau đề nghị và duyệt");
    }
    if (reference == null || reference.isBlank()) {
      throw new IllegalArgumentException("Thiếu số phiếu sự cố cho thao tác mã hoá lại");
    }
  }
}

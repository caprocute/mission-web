package vn.viettel.okrkpi.platform.retention;

import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Phê duyệt hai người cho việc khôi phục dữ liệu đã lưu trữ (TL-03 "Cơ chế lưu trữ": truy cập dữ
 * liệu lưu trữ cần yêu cầu có phê duyệt; nguyên tắc bốn mắt, suy luận): người đề nghị và người
 * duyệt phải khác nhau, có số phiếu yêu cầu.
 */
public record Approval(ActorId requestedBy, ActorId approvedBy, String reference) {
  public Approval {
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(approvedBy, "approvedBy");
    if (requestedBy.equals(approvedBy)) {
      throw new IllegalArgumentException("Khôi phục cần hai người khác nhau đề nghị và duyệt");
    }
    if (reference == null || !reference.matches("[A-Za-z0-9-]{1,40}")) {
      throw new IllegalArgumentException(
          "Số phiếu yêu cầu khôi phục bắt buộc, chỉ gồm chữ, số, '-' và tối đa 40 ký tự");
    }
  }
}

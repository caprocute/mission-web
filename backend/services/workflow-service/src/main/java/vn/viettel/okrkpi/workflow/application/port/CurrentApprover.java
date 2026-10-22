package vn.viettel.okrkpi.workflow.application.port;

import vn.viettel.okrkpi.workflow.domain.Approver;

/** Người gọi hiện tại lấy từ token đã xác thực và ngữ cảnh tenant (không đọc header client). */
public interface CurrentApprover {

  /**
   * Người gọi hiện tại.
   *
   * @throws IllegalStateException khi yêu cầu không có người dùng đã xác thực
   */
  Approver current();
}

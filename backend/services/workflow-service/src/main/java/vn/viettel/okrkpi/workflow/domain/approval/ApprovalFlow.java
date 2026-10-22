package vn.viettel.okrkpi.workflow.domain.approval;

import java.util.Optional;

/**
 * Luồng đánh giá của đối tượng (đầu vào {@code khoi} của DMN {@code chon-cap-duyet}; baseline:
 * {@code DANG}, {@code NHA_NUOC}).
 */
public enum ApprovalFlow {
  DANG,
  NHA_NUOC;

  /** Đọc mã luồng; rỗng khi không thuộc danh mục. */
  public static Optional<ApprovalFlow> parse(String code) {
    for (ApprovalFlow flow : values()) {
      if (flow.name().equals(code)) {
        return Optional.of(flow);
      }
    }
    return Optional.empty();
  }
}

package vn.viettel.okrkpi.evidence.application;

import java.util.UUID;

/**
 * Hỏi MOD-03 người gọi còn giữ bản bọc khoá của tệp C3 không (CORE-07-06, TL-01 "Chia sẻ và thu
 * hồi": thu hồi chặn tải bản mã). Lỗi kết nối ném {@code DependencyUnavailableException} để đóng an
 * toàn (503).
 */
@FunctionalInterface
public interface E2eeFileAccess {

  /**
   * @param wrapped tệp đã có bản bọc nào trên phiên bản minh chứng chưa
   * @param active người gọi đang giữ bản bọc còn hiệu lực
   */
  record Decision(boolean wrapped, boolean active) {}

  Decision check(UUID tenantId, UUID objectId, UUID personId);
}

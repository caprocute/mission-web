package vn.viettel.okrkpi.search.application;

import java.util.List;

/** Kiểm quyền của người gọi hiện tại (hiện thực: OPA cùng pod, NEN-014). */
public interface AccessPort {

  /** Người gọi có được dùng API tìm kiếm trong tenant của mình không. */
  boolean maySearch(String tenantId);

  /** Kết quả kiểm quyền xem từng ứng viên, cùng thứ tự với {@code hits}. Lỗi OPA là từ chối. */
  List<Boolean> mayView(String tenantId, List<SearchHit> hits);
}

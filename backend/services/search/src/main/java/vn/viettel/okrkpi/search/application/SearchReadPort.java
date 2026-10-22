package vn.viettel.okrkpi.search.application;

import java.util.List;

/** Cổng đọc chỉ mục tìm kiếm. */
public interface SearchReadPort {

  /**
   * Lấy tối đa {@code size} ứng viên xếp theo độ khớp, sau vị trí {@code after}; chỉ đọc qua bí
   * danh của {@code query.tenantId()}. Tenant chưa có bí danh trả danh sách rỗng.
   */
  List<SearchHit> find(SearchQuery query, List<Object> after, int size);
}

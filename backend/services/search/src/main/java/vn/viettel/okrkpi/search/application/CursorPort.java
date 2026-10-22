package vn.viettel.okrkpi.search.application;

import java.util.List;
import java.util.Optional;

/**
 * Mã hoá con trỏ trang để không lộ giá trị sắp xếp và gắn con trỏ với đúng người gọi và truy vấn.
 */
public interface CursorPort {

  String seal(String binding, List<Object> sort);

  /** Rỗng khi con trỏ hỏng, bị sửa hoặc thuộc người, truy vấn khác. */
  Optional<List<Object>> open(String binding, String cursor);
}

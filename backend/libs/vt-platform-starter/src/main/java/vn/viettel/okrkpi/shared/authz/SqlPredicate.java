package vn.viettel.okrkpi.shared.authz;

import java.util.List;
import java.util.Objects;

/**
 * Mệnh đề SQL có tham số ({@code ?}) sinh từ kết quả đánh giá từng phần OPA {@code POST
 * /v1/compile} (NEN-014, TL-01 "Đánh giá từng phần cho truy vấn danh sách").
 *
 * <p>Tuyệt đối không nối chuỗi giá trị người dùng vào {@link #sql()}; mọi giá trị phải nằm trong
 * {@link #params()} để gắn qua {@code PreparedStatement} hoặc {@code JdbcClient}.
 */
public record SqlPredicate(String sql, List<Object> params) {

  public static final SqlPredicate FALSE = new SqlPredicate("1 = 0", List.of());
  public static final SqlPredicate TRUE = new SqlPredicate("1 = 1", List.of());

  public SqlPredicate {
    Objects.requireNonNull(sql, "sql must not be null");
    if (sql.isBlank()) {
      throw new IllegalArgumentException("sql must not be blank");
    }
    params = params == null ? List.of() : List.copyOf(params);
  }

  public boolean isAlwaysFalse() {
    return "1 = 0".equals(sql);
  }
}

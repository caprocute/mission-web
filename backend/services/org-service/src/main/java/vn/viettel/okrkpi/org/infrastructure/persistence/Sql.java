package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.ValidPeriod;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/** Tiện ích SQL dùng chung cho các adapter hai trục thời gian. */
final class Sql {

  private Sql() {
  }

  /**
   * Điều kiện trục ghi nhận cho bí danh {@code alias}: hiểu biết hiện tại dùng {@code recorded_to = 'infinity'} để
   * khớp chỉ mục một phần; thời điểm quá khứ dùng {@code recorded_period @> :rec}.
   */
  static String recorded(String alias, TemporalQuery query) {
    return query.currentKnowledge()
        ? alias + ".recorded_to = 'infinity'"
        : alias + ".recorded_period @> CAST(:rec AS timestamptz)";
  }

  /** Mảng uuid dạng hằng văn bản (giá trị đã là UUID nên không có nguy cơ chèn mã). */
  static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  static String textArray(List<String> values) {
    for (String v : values) {
      if (!v.matches("[A-Z_]+")) {
        throw new IllegalArgumentException("Giá trị liệt kê không hợp lệ");
      }
    }
    return String.join(",", values.stream().toList()).isEmpty() ? "{}" : "{" + String.join(",", values) + "}";
  }

  static UUID tenant() {
    return TenantContextHolder.require();
  }

  static UUID uuid(ResultSet rs, String col) throws SQLException {
    return rs.getObject(col, UUID.class);
  }

  static LocalDate date(ResultSet rs, String col) throws SQLException {
    return rs.getObject(col, LocalDate.class);
  }

  static OffsetDateTime ts(ResultSet rs, String col) throws SQLException {
    return rs.getObject(col, OffsetDateTime.class);
  }

  static ValidPeriod period(ResultSet rs) throws SQLException {
    return new ValidPeriod(date(rs, "valid_from"), date(rs, "valid_to"));
  }

  static List<String> strings(ResultSet rs, String col) throws SQLException {
    Array array = rs.getArray(col);
    return array == null ? List.of() : List.of((String[]) array.getArray());
  }

  /** Cột trục thời gian của bí danh {@code a}; {@code infinity} trả về null. */
  static String axes(String a) {
    return a + ".change_order_id, " + a + ".valid_from, nullif(" + a + ".valid_to, 'infinity'::date) AS valid_to, "
        + a + ".recorded_from, nullif(" + a + ".recorded_to, 'infinity'::timestamptz) AS recorded_to, "
        + a + ".row_version";
  }
}

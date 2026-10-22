package vn.viettel.okrkpi.search.application;

import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.search.domain.DocType;

/**
 * Một ứng viên từ chỉ mục.
 *
 * @param source trường đã lập chỉ mục (C0, C1)
 * @param marked đoạn trích có dấu đánh dấu của highlighter, {@code null} khi không có
 * @param sort giá trị sắp xếp dùng cho {@code search_after}
 */
public record SearchHit(
    DocType type,
    String entityId,
    double score,
    Map<String, Object> source,
    String marked,
    List<Object> sort) {

  public String text(String field) {
    Object v = source.get(field);
    return v == null ? null : v.toString();
  }

  @SuppressWarnings("unchecked")
  public List<String> list(String field) {
    Object v = source.get(field);
    return v instanceof List<?> l ? (List<String>) l : List.of();
  }
}

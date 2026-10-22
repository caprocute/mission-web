package vn.viettel.okrkpi.search.application;

import java.util.List;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.Snippet;

/** Trang kết quả đã kiểm quyền; không có tổng số bản ghi. */
public record SearchPage(List<Item> items, String nextCursor) {

  /** Một kết quả, chỉ trường C0, C1. */
  public record Item(
      DocType type,
      String id,
      String code,
      String title,
      Snippet snippet,
      String status,
      String deepLink,
      double score) {}
}

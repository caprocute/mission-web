package vn.viettel.okrkpi.org.application;

import java.util.List;

/** Trang theo con trỏ; {@code nextCursor == null} khi đã hết (baseline mục 13). */
public record Page<T>(List<T> items, String nextCursor) {

  public Page {
    items = List.copyOf(items);
  }
}

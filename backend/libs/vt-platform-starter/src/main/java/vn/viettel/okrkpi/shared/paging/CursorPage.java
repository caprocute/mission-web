package vn.viettel.okrkpi.shared.paging;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * Một trang kết quả đúng dạng hợp đồng: {@code items} và {@code nextCursor} (bắt buộc, {@code null}
 * khi đã hết; {@code common.yaml} lược đồ {@code NextCursor}). {@code nextCursor} luôn được tuần tự
 * hoá kể cả khi ứng dụng đặt {@code NON_NULL}.
 *
 * @param items các dòng của trang
 * @param nextCursor con trỏ trang kế tiếp, {@code null} khi hết
 */
public record CursorPage<T>(
    List<T> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

  public CursorPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
  }

  public static <T> CursorPage<T> last(List<T> items) {
    return new CursorPage<>(items, null);
  }

  public boolean hasNext() {
    return nextCursor != null;
  }
}

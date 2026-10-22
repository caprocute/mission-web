package vn.viettel.okrkpi.platform.reconcile;

import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 256 bucket băm của đối soát theo nhóm (TL-03: "chia mã thực thể thành 256 bucket theo băm, so băm
 * tổng hợp từng bucket, đi sâu vào bucket lệch"). Bucket của thực thể là byte đầu của SHA-256 trên
 * mã dạng chuẩn {@link RowHash#key}, trùng cách tính của org-service ({@code Buckets.of}).
 */
public final class Buckets {

  /** Số bucket. */
  public static final int COUNT = 256;

  private Buckets() {}

  public static int of(Object id) {
    return RowHash.sha256(RowHash.key(id))[0] & 0xff;
  }

  /**
   * Các bucket lệch trong phạm vi {@code expected}: khác giá trị hoặc phía đích không có. Bucket
   * chỉ có ở {@code actual} nằm ngoài phạm vi nên không tính.
   */
  public static SortedSet<Integer> mismatched(
      Map<Integer, String> expected, Map<Integer, String> actual) {
    SortedSet<Integer> out = new TreeSet<>();
    for (Map.Entry<Integer, String> e : expected.entrySet()) {
      String a = actual.get(e.getKey());
      if (a == null || !a.equalsIgnoreCase(Objects.requireNonNull(e.getValue(), "hash"))) {
        out.add(e.getKey());
      }
    }
    return out;
  }
}

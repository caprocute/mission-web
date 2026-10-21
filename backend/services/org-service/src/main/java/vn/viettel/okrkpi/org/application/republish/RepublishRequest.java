package vn.viettel.okrkpi.org.application.republish;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Tham số lệnh {@code org-republish} (DV-04-08).
 *
 * @param tenant tenant cần phát lại
 * @param entities loại thực thể; rỗng nghĩa là cả ba
 * @param buckets bucket băm 0..255 cần phát; rỗng nghĩa là mọi bucket
 * @param asOf thời điểm ghi nhận của ảnh chụp; null là hiện tại
 * @param dryRun chỉ đếm và tính băm, không phát
 * @param operator người hoặc hệ thống chạy lệnh (ghi kiểm toán)
 */
public record RepublishRequest(
    UUID tenant, Set<EntityKind> entities, Set<Integer> buckets, Instant asOf, boolean dryRun, String operator) {

  public RepublishRequest {
    Objects.requireNonNull(tenant, "tenant");
    entities = entities == null || entities.isEmpty() ? EnumSet.allOf(EntityKind.class) : EnumSet.copyOf(entities);
    SortedSet<Integer> b = new TreeSet<>();
    if (buckets == null || buckets.isEmpty()) {
      for (int i = 0; i < Buckets.COUNT; i++) {
        b.add(i);
      }
    } else {
      for (int i : buckets) {
        if (i < 0 || i >= Buckets.COUNT) {
          throw new IllegalArgumentException("--bucket phải trong khoảng 0..255");
        }
        b.add(i);
      }
    }
    buckets = java.util.Collections.unmodifiableSortedSet(b);
    operator = operator == null || operator.isBlank() ? "system" : operator;
  }

  /** Phát lại toàn bộ (mọi bucket). */
  public boolean allBuckets() {
    return buckets.size() == Buckets.COUNT;
  }
}

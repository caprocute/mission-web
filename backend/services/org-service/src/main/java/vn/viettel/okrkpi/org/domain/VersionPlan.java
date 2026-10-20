package vn.viettel.okrkpi.org.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kế hoạch ghi một thực thể hai trục: các phiên bản phải đóng trục ghi nhận ({@code recorded_to = now()}) và các
 * phiên bản mới chèn với {@code recorded_from = now()} trong cùng giao dịch (TL-03 "Quy tắc ghi").
 */
public record VersionPlan<T>(List<UUID> toClose, List<T> toInsert) {

  public VersionPlan {
    toClose = List.copyOf(toClose);
    toInsert = List.copyOf(toInsert);
  }

  public static <T> VersionPlan<T> empty() {
    return new VersionPlan<>(List.of(), List.of());
  }

  public boolean isEmpty() {
    return toClose.isEmpty() && toInsert.isEmpty();
  }

  public VersionPlan<T> plusInsert(T version) {
    List<T> inserts = new ArrayList<>(toInsert);
    inserts.add(version);
    return new VersionPlan<>(toClose, inserts);
  }
}

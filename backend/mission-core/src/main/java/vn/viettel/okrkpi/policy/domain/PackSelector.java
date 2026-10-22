package vn.viettel.okrkpi.policy.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chọn gói cho một kỳ khi chưa có {@code policy_binding} (TL-01 "Hiệu lực theo kỳ", X-PP-10): lọc
 * phiên bản có băm nội dung, cùng luồng, khoảng hiệu lực phủ ngày đầu kỳ; ưu tiên {@code tu_ky} lớn
 * nhất, rồi gói của tenant trước gói gốc (suy luận, vì gói tenant kế thừa và siết gói gốc), rồi
 * phiên bản lớn nhất.
 */
public final class PackSelector {

  private static final Comparator<PackCandidate> PREFERENCE =
      Comparator.<PackCandidate, LocalDate>comparing(
              c -> c.range().from(), Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(c -> c.origin() == PackOrigin.TENANT)
          .thenComparing(c -> SemVer.parse(c.version()).orElse(new SemVer(0, 0, 0)));

  private PackSelector() {}

  public static Optional<PackCandidate> select(
      List<PackCandidate> candidates, String flow, LocalDate periodStart) {
    return candidates.stream()
        .filter(c -> c.contentHash() != null)
        .filter(c -> flow.equals(c.flow()))
        .filter(c -> c.range().covers(periodStart))
        .max(PREFERENCE);
  }

  /**
   * Các cặp phiên bản cùng mã gói, cùng luồng có khoảng hiệu lực chồng nhau; CSDL chưa có ràng buộc
   * này nên tầng ứng dụng kiểm (CORE-05-10 gọi khi phát hành).
   */
  public static List<List<PackCandidate>> overlapping(List<PackCandidate> candidates) {
    List<List<PackCandidate>> pairs = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      for (int j = i + 1; j < candidates.size(); j++) {
        PackCandidate a = candidates.get(i);
        PackCandidate b = candidates.get(j);
        if (a.code().equals(b.code())
            && a.flow().equals(b.flow())
            && a.range().overlaps(b.range())) {
          pairs.add(List.of(a, b));
        }
      }
    }
    return pairs;
  }
}

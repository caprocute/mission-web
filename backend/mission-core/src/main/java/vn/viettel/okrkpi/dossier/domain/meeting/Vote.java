package vn.viettel.okrkpi.dossier.domain.meeting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết quả biểu quyết mức xếp loại tại hội nghị ({@code vote_json}). Chỉ ghi vào biên bản và Mẫu 3,
 * không thay mức quyết định của cấp có thẩm quyền (BR-11-07); tổng phiếu không vượt số người có
 * mặt.
 */
public record Vote(Map<String, Integer> counts) {

  /** Bốn mức xếp loại theo thứ tự trên biểu mẫu. */
  public static final List<String> LEVELS = List.of("HTXSNV", "HTTNV", "HTNV", "KHTNV");

  public Vote {
    Map<String, Integer> all = new LinkedHashMap<>();
    for (String level : LEVELS) {
      Integer n = counts == null ? null : counts.get(level);
      all.put(level, n == null ? 0 : n);
    }
    counts = Map.copyOf(all);
  }

  public int total() {
    return counts.values().stream().mapToInt(Integer::intValue).sum();
  }

  public boolean anyNegative() {
    return counts.values().stream().anyMatch(n -> n < 0);
  }

  public int count(String level) {
    return counts.getOrDefault(level, 0);
  }

  /** Thứ tự cố định của {@link #LEVELS} để ghi JSON ổn định. */
  public Map<String, Integer> ordered() {
    Map<String, Integer> out = new LinkedHashMap<>();
    LEVELS.forEach(l -> out.put(l, count(l)));
    return out;
  }
}

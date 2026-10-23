package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Người đã tham gia hồ sơ theo nhóm của ràng buộc 21.1 ({@code acceptor}, {@code appraiser}, {@code
 * proposer}, {@code decider}), giữ trong biến {@code thamGiaHoSo} dạng chuỗi ngắn {@code
 * nhóm=id|id;nhóm=id} để tách vai trò khi giao việc (BR-12-04, BR-13-03).
 */
public final class Participants {

  /** Bốn nhóm theo thứ tự của ràng buộc 21.1. */
  public static final List<String> GROUPS = List.of("acceptor", "appraiser", "proposer", "decider");

  private Participants() {}

  /** Mã hoá; nhóm ngoài danh mục bị bỏ. */
  public static String encode(Map<String, ? extends java.util.Collection<UUID>> groups) {
    StringBuilder out = new StringBuilder();
    for (String g : GROUPS) {
      java.util.Collection<UUID> people = groups.get(g);
      if (people == null || people.isEmpty()) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append(';');
      }
      out.append(g).append('=');
      List<String> ids = new ArrayList<>();
      new LinkedHashSet<>(people).forEach(p -> ids.add(p.toString()));
      out.append(String.join("|", ids));
    }
    return out.toString();
  }

  /** Giải mã; chuỗi rỗng hoặc {@code null} cho bản đồ rỗng. */
  public static Map<String, List<UUID>> decode(Object raw) {
    Map<String, List<UUID>> out = new TreeMap<>();
    if (raw == null || raw.toString().isBlank()) {
      return out;
    }
    for (String part : raw.toString().split(";")) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String group = part.substring(0, eq);
      if (!GROUPS.contains(group)) {
        continue;
      }
      List<UUID> people = new ArrayList<>();
      for (String id : part.substring(eq + 1).split("\\|")) {
        if (!id.isBlank()) {
          people.add(UUID.fromString(id));
        }
      }
      out.put(group, List.copyOf(people));
    }
    return out;
  }

  /** Mọi người đã tham gia. */
  public static Set<UUID> all(Map<String, List<UUID>> groups) {
    Set<UUID> out = new LinkedHashSet<>();
    groups.values().forEach(out::addAll);
    return out;
  }
}

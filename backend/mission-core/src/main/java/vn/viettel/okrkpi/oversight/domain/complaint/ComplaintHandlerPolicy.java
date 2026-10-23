package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Chọn người xử lý khiếu nại (QT-12 bước 3, BR-12-04): người giữ vai trò {@code appraiser} ở đơn vị
 * xử lý, loại người đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ (PL 4 V.1); không còn
 * ai thì lên đơn vị cấp trên gần nhất có ứng viên. Cùng quy tắc với {@code
 * backend/opa/okrkpi/complaint_handler.rego} mà SVC-06 gọi khi giao task; MOD-07 kiểm lại ở đây khi
 * nhận lệnh ghi vì {@code OpaClient} chỉ hỏi được quyết định allow hoặc deny.
 */
public final class ComplaintHandlerPolicy {

  /** Bốn nhóm tham gia hồ sơ theo thứ tự của ràng buộc 21.1 ({@code subject_not_in}). */
  public static final List<String> GROUPS = List.of("acceptor", "appraiser", "proposer", "decider");

  public static final String NO_CANDIDATE = "KHONG_CO_UNG_VIEN";
  public static final String ESCALATED = "NANG_CAP_TREN";

  private ComplaintHandlerPolicy() {}

  /**
   * @param unit đơn vị có ứng viên, {@code null} khi không còn ai
   * @param candidates ứng viên hợp lệ, sắp tăng dần
   * @param reason {@link #ESCALATED}, {@link #NO_CANDIDATE} hoặc {@code null}
   */
  public record Choice(UUID unit, List<UUID> candidates, boolean escalated, String reason) {
    public Choice {
      candidates = List.copyOf(candidates);
    }
  }

  /**
   * @param units đơn vị xử lý rồi các cấp trên, gần trước
   * @param appraisers người giữ vai trò {@code appraiser} có hiệu lực tại từng đơn vị
   * @param participants nhóm tham gia → người
   */
  public static Choice choose(
      List<UUID> units,
      Map<UUID, ? extends Collection<UUID>> appraisers,
      Map<String, ? extends Collection<UUID>> participants) {
    Set<UUID> excluded = new TreeSet<>();
    for (String g : GROUPS) {
      excluded.addAll(of(participants, g));
    }
    for (int i = 0; i < units.size(); i++) {
      Set<UUID> eligible = new TreeSet<>(of(appraisers, units.get(i)));
      eligible.removeAll(excluded);
      if (!eligible.isEmpty()) {
        return new Choice(units.get(i), new ArrayList<>(eligible), i > 0, i > 0 ? ESCALATED : null);
      }
    }
    return new Choice(null, List.of(), false, NO_CANDIDATE);
  }

  /** Các nhóm mà {@code subject} đã tham gia, theo thứ tự {@link #GROUPS}. */
  public static List<String> conflicts(
      UUID subject, Map<String, ? extends Collection<UUID>> participants) {
    List<String> out = new ArrayList<>();
    for (String g : GROUPS) {
      if (of(participants, g).contains(subject)) {
        out.add(g);
      }
    }
    return out;
  }

  private static <K> Collection<UUID> of(Map<K, ? extends Collection<UUID>> map, K key) {
    Collection<UUID> v = map.get(key);
    return v == null ? Set.of() : v;
  }
}

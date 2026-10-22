package vn.viettel.okrkpi.task.domain.tree;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Quy tắc cây phân rã và chế độ phân công (BS-03 DC-04, DC-06). Khuôn phân rã và chế độ đọc từ tham
 * số của Policy Pack; không có tham số thì giữ hành vi cũ: bốn loại nút theo thứ tự chuẩn và chế độ
 * "một chủ trì, nhiều phối hợp".
 */
public final class TaskTree {

  /** Loại nút theo thứ tự từ gốc xuống lá ({@code TaskNodeKind}). */
  public static final List<String> KINDS = List.of("NHIEM_VU", "KPI", "CONG_VIEC", "CONG_VIEC_CON");

  public static final String ONE_PERSON = "Mỗi công việc chỉ giao cho đúng một người thực hiện.";
  public static final String SCORE_MISSING = "Việc con phải có điểm thành phần.";
  public static final String SCORE_OVER =
      "Tổng điểm thành phần của các việc con vượt quá điểm của việc cha.";
  public static final String SCORE_SHORT =
      "Tổng điểm thành phần của các việc con chưa bằng điểm của việc cha.";

  /** Chế độ phân công của hồ sơ (tham số {@code che_do_phan_cong}). */
  public enum AssignmentMode {
    CHU_TRI_PHOI_HOP,
    MOT_NGUOI_MOT_VIEC;

    /** Giá trị lạ hoặc thiếu là mặc định {@code CHU_TRI_PHOI_HOP}. */
    public static AssignmentMode parse(Object value) {
      return value != null && MOT_NGUOI_MOT_VIEC.name().equals(String.valueOf(value).strip())
          ? MOT_NGUOI_MOT_VIEC
          : CHU_TRI_PHOI_HOP;
    }
  }

  /** Một việc con khi tổng hợp: trạng thái và điểm thành phần. */
  public record Child(String status, BigDecimal componentScore) {}

  /**
   * Tổng hợp việc cha: số việc con còn hiệu lực (không tính {@code HUY}), số đã {@code HOAN_THANH},
   * tổng điểm thành phần; {@code done} khi có việc con và mọi việc con đã xong, với chế độ {@code
   * MOT_NGUOI_MOT_VIEC} còn cần tổng điểm thành phần bằng điểm việc cha.
   */
  public record Rollup(
      int children, int completed, BigDecimal componentSum, BigDecimal parentScore, boolean done) {}

  private TaskTree() {}

  /** Khuôn phân rã từ tham số {@code khuon_phan_ra}: danh sách loại nút hợp lệ, không trùng. */
  public static List<String> template(Object value) {
    if (!(value instanceof Collection<?> list) || list.isEmpty()) {
      return KINDS;
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      String k = String.valueOf(o).strip();
      if (!KINDS.contains(k) || out.contains(k)) {
        return KINDS;
      }
      out.add(k);
    }
    return List.copyOf(out);
  }

  /**
   * Kiểm vị trí của nút {@code kind} dưới đường dẫn {@code parentPath} (loại nút từ gốc tới việc
   * cha; rỗng là nút gốc). Trả lời lỗi hoặc {@code null} khi hợp lệ.
   */
  public static String placement(List<String> template, List<String> parentPath, String kind) {
    int self = template.indexOf(kind);
    if (self < 0) {
      return "Loại công việc không thuộc khuôn phân rã đang áp dụng.";
    }
    if (parentPath.isEmpty()) {
      return null;
    }
    int parent = template.indexOf(parentPath.get(parentPath.size() - 1));
    if (parent < 0 || self <= parent) {
      return "Loại công việc phải ở bậc dưới việc cha theo khuôn phân rã.";
    }
    if (parentPath.size() + 1 > template.size()) {
      return "Cây công việc vượt quá số bậc của khuôn phân rã.";
    }
    return null;
  }

  /**
   * Vi phạm của chế độ phân công khi giao một nút: việc lá đúng một người (không giao cho đơn vị);
   * việc con có điểm thành phần và tổng cùng các việc con khác không vượt điểm việc cha. Chế độ mặc
   * định không có vi phạm.
   *
   * @param siblingsSum tổng điểm thành phần của các việc con khác còn hiệu lực cùng việc cha
   */
  public static List<String> violations(
      AssignmentMode mode,
      boolean leaf,
      int people,
      boolean unitLead,
      boolean hasParent,
      BigDecimal parentScore,
      BigDecimal siblingsSum,
      BigDecimal componentScore) {
    if (mode != AssignmentMode.MOT_NGUOI_MOT_VIEC) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    if (leaf && (unitLead || people != 1)) {
      out.add(ONE_PERSON);
    }
    if (hasParent && parentScore != null) {
      if (componentScore == null) {
        out.add(SCORE_MISSING);
      } else if (nz(siblingsSum).add(componentScore).compareTo(parentScore) > 0) {
        out.add(SCORE_OVER);
      }
    }
    return List.copyOf(out);
  }

  /** Tổng hợp trạng thái việc cha từ các việc con. */
  public static Rollup rollup(AssignmentMode mode, BigDecimal parentScore, List<Child> children) {
    int n = 0;
    int completed = 0;
    BigDecimal sum = BigDecimal.ZERO;
    for (Child c : children) {
      if ("HUY".equals(c.status())) {
        continue;
      }
      n++;
      if ("HOAN_THANH".equals(c.status())) {
        completed++;
      }
      sum = sum.add(nz(c.componentScore()));
    }
    boolean done = n > 0 && completed == n;
    if (done && mode == AssignmentMode.MOT_NGUOI_MOT_VIEC && parentScore != null) {
      done = sum.compareTo(parentScore) == 0;
    }
    return new Rollup(n, completed, sum, parentScore, done);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}

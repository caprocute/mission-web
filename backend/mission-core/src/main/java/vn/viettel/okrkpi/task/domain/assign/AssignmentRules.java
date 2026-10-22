package vn.viettel.okrkpi.task.domain.assign;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Danh sách kiểm tra trước khi giao của QT-05 bước 5 ({@code AssignCheckItem}): BR-05-01 đúng một
 * chủ trì, BR-05-02 người nghiệm thu khác người thực hiện, BR-05-03 đủ sản phẩm và hạn trong kỳ,
 * BR-05-04 dòng kế hoạch thuộc phiên bản đã duyệt hiệu lực, BR-05-09 nhiệm vụ phát sinh có căn cứ
 * (mức {@code CHAN}); tên chứa từ mơ hồ là cảnh báo theo BR-04-03 (mức {@code CANH_BAO}).
 */
public final class AssignmentRules {

  public enum Level {
    CHAN,
    CANH_BAO
  }

  /** Một dòng của danh sách kiểm tra. */
  public record CheckItem(String rule, Level level, boolean passed, String message) {}

  /**
   * Ngữ cảnh kiểm: khoảng ngày của kỳ, dòng kế hoạch đã duyệt hiệu lực hay chưa, người nghiệm thu
   * mặc định khi bản nháp chưa chọn (người giao) và danh sách từ mơ hồ.
   */
  public record Context(
      LocalDate periodStart,
      LocalDate periodEnd,
      boolean planItemApproved,
      UUID defaultAcceptor,
      Collection<String> vagueWords) {}

  public static final String MSG_05_01 = "Mỗi nhiệm vụ chỉ có đúng một người chủ trì.";
  public static final String MSG_05_02 = "Người nghiệm thu phải khác người thực hiện nhiệm vụ.";
  static final String BR_05_03 =
      "Nhiệm vụ phải có sản phẩm, số lượng, đơn vị tính, tiêu chuẩn chất lượng, minh chứng yêu cầu"
          + " và hạn trong kỳ.";
  static final String BR_05_04 =
      "Nhiệm vụ từ kế hoạch phải thuộc phiên bản kế hoạch đã duyệt đang có hiệu lực.";
  static final String BR_05_09 =
      "Nhiệm vụ phát sinh ngoài kế hoạch phải có căn cứ (văn bản hoặc chỉ đạo ghi rõ).";
  static final String VAGUE = "Tên nhiệm vụ chứa từ mơ hồ, nên ghi rõ kết quả cần đạt.";

  private static final Set<String> OUTSIDE_PLAN = Set.of("DOT_XUAT", "VAN_BAN");

  private AssignmentRules() {}

  public static List<CheckItem> check(TaskDraftSpec s, Context c) {
    Objects.requireNonNull(s, "spec");
    Objects.requireNonNull(c, "context");
    List<CheckItem> items = new ArrayList<>();
    boolean leadOnce =
        s.leadPersonId() != null
            && s.coAssignees().stream()
                .noneMatch(a -> a.person() && s.leadPersonId().equals(a.id()));
    items.add(new CheckItem("BR-05-01", Level.CHAN, leadOnce, MSG_05_01));

    UUID acceptor = s.acceptorId() != null ? s.acceptorId() : c.defaultAcceptor();
    boolean independent =
        acceptor != null
            && !acceptor.equals(s.leadPersonId())
            && s.coAssignees().stream().noneMatch(a -> a.person() && acceptor.equals(a.id()));
    items.add(new CheckItem("BR-05-02", Level.CHAN, independent, MSG_05_02));

    boolean complete =
        filled(s.productName())
            && s.quantity() != null
            && s.quantity().signum() > 0
            && filled(s.unit())
            && filled(s.qualityCriteria())
            && filled(s.evidenceRequired())
            && s.dueDate() != null
            && c.periodStart() != null
            && !s.dueDate().isBefore(c.periodStart())
            && !s.dueDate().isAfter(c.periodEnd());
    items.add(new CheckItem("BR-05-03", Level.CHAN, complete, BR_05_03));

    boolean fromPlan = "KE_HOACH".equals(s.source());
    items.add(
        new CheckItem(
            "BR-05-04",
            Level.CHAN,
            !fromPlan || (s.planItemId() != null && c.planItemApproved()),
            BR_05_04));

    boolean basis =
        !OUTSIDE_PLAN.contains(s.source()) || filled(s.basis()) || filled(s.sourceDocNumber());
    items.add(new CheckItem("BR-05-09", Level.CHAN, basis, BR_05_09));

    items.add(new CheckItem("BR-04-03", Level.CANH_BAO, !vague(s.title(), c.vagueWords()), VAGUE));
    return List.copyOf(items);
  }

  /** Có dòng mức {@code CHAN} chưa đạt. */
  public static boolean blocked(List<CheckItem> items) {
    return items.stream().anyMatch(i -> i.level() == Level.CHAN && !i.passed());
  }

  static boolean vague(String title, Collection<String> words) {
    if (title == null || words == null) {
      return false;
    }
    String t = title.toLowerCase(Locale.ROOT);
    return words.stream().anyMatch(w -> !w.isBlank() && t.contains(w.toLowerCase(Locale.ROOT)));
  }

  private static boolean filled(String s) {
    return s != null && !s.isBlank();
  }
}

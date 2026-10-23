package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Bảng chuyển trạng thái khiếu nại đã gửi theo TL-02b QT-12 "Trạng thái đối tượng", thêm {@code
 * RUT} của TL-03. {@code KHONG_THU_LY} và {@code RUT} tự sang {@code DONG} ngay sau khi gửi thông
 * báo (bước {@link Step#CLOSE}).
 */
public final class ComplaintWorkflow {

  /** Hành động hoặc sự kiện làm đổi trạng thái. */
  public enum Step {
    /** Người xử lý hợp lệ tiếp nhận. */
    ACCEPT,
    /** Không thụ lý có lý do. */
    REJECT,
    /** Người khiếu nại rút trước khi có kết luận. */
    WITHDRAW,
    /** Yêu cầu ít nhất một bên giải trình. */
    REQUEST_EXPLANATIONS,
    /** Mọi yêu cầu giải trình đã trả lời hoặc quá hạn. */
    EXPLANATIONS_DONE,
    /** Kết luận thẳng không qua giải trình, có ghi chú. */
    SKIP_EXPLANATIONS,
    /** Văn bản trả lời đã ký số (EVT-18). */
    RESPOND,
    /** Kết luận có điều chỉnh gắn yêu cầu mở lại kỳ. */
    EXECUTE,
    /** Tự đóng sau không thụ lý hoặc rút. */
    CLOSE
  }

  private record Edge(Set<ComplaintStatus> from, ComplaintStatus to) {}

  private static final Map<Step, Edge> EDGES = new EnumMap<>(Step.class);

  static {
    EDGES.put(Step.ACCEPT, new Edge(EnumSet.of(ComplaintStatus.MOI), ComplaintStatus.DA_TIEP_NHAN));
    EDGES.put(Step.REJECT, new Edge(EnumSet.of(ComplaintStatus.MOI), ComplaintStatus.KHONG_THU_LY));
    EDGES.put(
        Step.WITHDRAW,
        new Edge(
            EnumSet.of(
                ComplaintStatus.MOI,
                ComplaintStatus.DA_TIEP_NHAN,
                ComplaintStatus.DANG_GIAI_TRINH,
                ComplaintStatus.CHO_KET_LUAN),
            ComplaintStatus.RUT));
    EDGES.put(
        Step.REQUEST_EXPLANATIONS,
        new Edge(EnumSet.of(ComplaintStatus.DA_TIEP_NHAN), ComplaintStatus.DANG_GIAI_TRINH));
    EDGES.put(
        Step.EXPLANATIONS_DONE,
        new Edge(EnumSet.of(ComplaintStatus.DANG_GIAI_TRINH), ComplaintStatus.CHO_KET_LUAN));
    EDGES.put(
        Step.SKIP_EXPLANATIONS,
        new Edge(EnumSet.of(ComplaintStatus.DA_TIEP_NHAN), ComplaintStatus.CHO_KET_LUAN));
    EDGES.put(
        Step.RESPOND,
        new Edge(EnumSet.of(ComplaintStatus.CHO_KET_LUAN), ComplaintStatus.DA_TRA_LOI));
    EDGES.put(
        Step.EXECUTE,
        new Edge(EnumSet.of(ComplaintStatus.DA_TRA_LOI), ComplaintStatus.DANG_THI_HANH));
    EDGES.put(
        Step.CLOSE,
        new Edge(
            EnumSet.of(ComplaintStatus.KHONG_THU_LY, ComplaintStatus.RUT), ComplaintStatus.DONG));
  }

  private ComplaintWorkflow() {}

  /**
   * Trạng thái kế tiếp; nháp ({@code null}) hoặc trạng thái không có cạnh thì ném {@link
   * InvalidState}.
   */
  public static ComplaintStatus next(ComplaintStatus from, Step step) {
    Edge e = EDGES.get(step);
    if (from == null || !e.from().contains(from)) {
      throw new InvalidState(from, step);
    }
    return e.to();
  }

  /** Bước được phép ở trạng thái đã cho. */
  public static boolean allows(ComplaintStatus from, Step step) {
    return from != null && EDGES.get(step).from().contains(from);
  }

  /** Chuyển trạng thái không hợp lệ (lớp ứng dụng trả 409 {@code OVERSIGHT-409-INVALID-STATE}). */
  public static final class InvalidState extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidState(ComplaintStatus from, Step step) {
      super(message(from, step));
    }

    private static String message(ComplaintStatus from, Step step) {
      return switch (step) {
        case ACCEPT, REJECT -> "Khiếu nại không còn ở trạng thái chờ tiếp nhận.";
        case WITHDRAW -> "Khiếu nại đã có kết luận hoặc đã đóng, không rút được nữa.";
        case REQUEST_EXPLANATIONS -> "Chỉ yêu cầu giải trình sau khi đã tiếp nhận khiếu nại.";
        case EXPLANATIONS_DONE -> "Khiếu nại không ở bước giải trình.";
        case SKIP_EXPLANATIONS -> "Khiếu nại chưa được tiếp nhận hoặc đang chờ giải trình.";
        case RESPOND -> "Khiếu nại chưa ở bước chờ kết luận.";
        case EXECUTE -> "Khiếu nại chưa có văn bản trả lời.";
        case CLOSE -> "Khiếu nại không ở trạng thái tự đóng được.";
      };
    }
  }
}

package vn.viettel.okrkpi.workflow.domain.dual;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * Mẫu {@code duyet-kep-chi-huy-cap-uy} v1 (DV-06-12, TL-02b SCR-1504 vùng 1): chỉ huy và cấp uỷ
 * cùng duyệt song song ở một bước duyệt của luồng cha. Tên biến tiến trình của từng nhánh và quy
 * tắc tách vai trò của lượt duyệt kép (BR-01-18, BR-02-08): người trình không duyệt, một người
 * không duyệt cả hai nhánh.
 *
 * <p>Ngữ nghĩa là mặc định suy luận chờ xác nhận (wiki trang 14): hai nhánh luôn song song, bên
 * vắng mặt xử lý bằng quá hạn chuyển người được uỷ quyền; một bên trả lại thì huỷ việc bên kia,
 * không chờ ý kiến; bất đồng (một duyệt, một trả lại) là trả lại, không chuyển cấp trên.
 */
public final class DualApproval {

  /** Mã mẫu. */
  public static final String TEMPLATE = "duyet-kep-chi-huy-cap-uy";

  /** Loại nghiệp vụ của dòng tham số {@code flowable.wf_binding}. */
  public static final String PROCESS_KIND = "DUYET_KEP_CHI_HUY_CAP_UY";

  /** Kế hoạch hai nhánh chụp lúc khởi tạo (khuôn {@code StagePlan}). */
  public static final String PLAN = "keHoachKep";

  /** Số lần quá hạn theo nhánh. */
  public static final String OVERDUE_COUNTS = "soLanQuaHanKep";

  /** Phần tử bắt đầu của tiến trình (listener lập kế hoạch). */
  public static final String START = "start";

  /** Tiền tố của sự kiện biên quá hạn. */
  public static final String OVERDUE_PREFIX = "quaHan_";

  /** Tiền tố của sự kiện chờ ký. */
  public static final String SIGN_WAIT_PREFIX = "choKy_";

  /** Hạn mặc định (ngày làm việc). */
  public static final int DEFAULT_DAYS = 3;

  /** Nhắc trước hạn mặc định (ngày làm việc, EVT-21). */
  public static final int DEFAULT_REMIND = 1;

  /** Miền thời hạn của mỗi nhánh (SCR-1504 bảng trường dòng 5). */
  public static final int MIN_DAYS = 1;

  public static final int MAX_DAYS = 15;

  private DualApproval() {}

  /** Nhánh duyệt. */
  public enum Branch {
    /** Chỉ huy đơn vị. */
    CHI_HUY("chiHuy", ApprovalScope.DON_VI_TRUC_TIEP),
    /** Cấp uỷ (chính uỷ, chính trị viên). */
    CAP_UY("capUy", ApprovalScope.CAP_UY_CO_THAM_QUYEN);

    /** Vai trò mặc định của cả hai nhánh (suy luận: {@code leader} theo đơn vị của nhánh). */
    public static final String DEFAULT_ROLE = "leader";

    private final String elementId;
    private final ApprovalScope defaultScope;

    Branch(String elementId, ApprovalScope defaultScope) {
      this.elementId = elementId;
      this.defaultScope = defaultScope;
    }

    /** Mã user task của nhánh. */
    public String elementId() {
      return elementId;
    }

    /** Phạm vi đơn vị mặc định. */
    public ApprovalScope defaultScope() {
      return defaultScope;
    }

    /** Nhánh còn lại. */
    public Branch other() {
      return this == CHI_HUY ? CAP_UY : CHI_HUY;
    }

    public String groupVariable() {
      return "nhom_" + elementId;
    }

    public String assigneeVariable() {
      return "nguoi_" + elementId;
    }

    public String dueVariable() {
      return "han_" + elementId;
    }

    public String remindVariable() {
      return "nhac_" + elementId;
    }

    public String signRequiredVariable() {
      return "canKy_" + elementId;
    }

    public String signedVariable() {
      return "daKy_" + elementId;
    }

    /** Nhánh theo mã bước của tham số ({@code CHI_HUY}, {@code CAP_UY}). */
    public static Optional<Branch> byCode(String code) {
      for (Branch b : values()) {
        if (b.name().equals(code)) {
          return Optional.of(b);
        }
      }
      return Optional.empty();
    }

    /** Nhánh theo mã phần tử: user task, sự kiện quá hạn hoặc chờ ký của nhánh. */
    public static Optional<Branch> byElement(String elementId) {
      if (elementId == null) {
        return Optional.empty();
      }
      String id = elementId;
      if (id.startsWith(OVERDUE_PREFIX)) {
        id = id.substring(OVERDUE_PREFIX.length());
      } else if (id.startsWith(SIGN_WAIT_PREFIX)) {
        id = id.substring(SIGN_WAIT_PREFIX.length());
      }
      for (Branch b : values()) {
        if (b.elementId.equals(id)) {
          return Optional.of(b);
        }
      }
      return Optional.empty();
    }
  }

  /** Vi phạm tách vai trò của lượt duyệt kép. */
  public enum Violation {
    /** Người duyệt là người trình. */
    INITIATOR,
    /** Người duyệt đã duyệt nhánh còn lại. */
    OTHER_BRANCH
  }

  /**
   * Kiểm tách vai trò khi {@code approver} hoàn thành một nhánh.
   *
   * @param initiator người trình, có thể {@code null}
   * @param otherBranchApprovers người đã duyệt nhánh còn lại của cùng lượt
   */
  public static Optional<Violation> check(
      UUID approver, UUID initiator, Collection<UUID> otherBranchApprovers) {
    if (approver == null) {
      return Optional.empty();
    }
    if (approver.equals(initiator)) {
      return Optional.of(Violation.INITIATOR);
    }
    if (otherBranchApprovers.contains(approver)) {
      return Optional.of(Violation.OTHER_BRANCH);
    }
    return Optional.empty();
  }
}

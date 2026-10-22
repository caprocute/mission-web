package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Set;

/**
 * Lệnh của bảng chuyển trạng thái TL-02b QT-09 ("Trạng thái đối tượng"), mỗi lệnh một dòng; {@code
 * stage} là bước báo trong EVT-11 (một trong bốn mức của BR-09-12).
 */
public enum ProfileCommand {
  /** EVT-10 có ảnh chụp điểm hợp lệ. */
  OPEN_SELF_ASSESSMENT(
      Set.of(AppraisalStatus.CHUA_LAP), AppraisalStatus.TU_DANH_GIA, LevelKind.TU_DE_XUAT),
  /** Phiếu tự đánh giá đã ký và nộp (EVT-18 của phiếu). */
  SUBMIT(Set.of(AppraisalStatus.TU_DANH_GIA), AppraisalStatus.CHO_THAM_DINH, LevelKind.TU_DE_XUAT),
  /** Trả lại bổ sung, có lý do, không quá số lần tham số. */
  RETURN_FOR_REVISION(
      Set.of(AppraisalStatus.CHO_THAM_DINH), AppraisalStatus.TU_DANH_GIA, LevelKind.THAM_DINH),
  /** Kết quả thẩm định đã ký. */
  APPRAISE(
      Set.of(AppraisalStatus.CHO_THAM_DINH), AppraisalStatus.DA_THAM_DINH, LevelKind.THAM_DINH),
  /** Đề xuất mức (cấp 3). */
  PROPOSE(Set.of(AppraisalStatus.DA_THAM_DINH), AppraisalStatus.CHO_QUYET_DINH, LevelKind.DE_XUAT),
  /** Bỏ qua bước đề xuất theo điều kiện của mẫu luồng. */
  SKIP_PROPOSAL(
      Set.of(AppraisalStatus.DA_THAM_DINH), AppraisalStatus.CHO_QUYET_DINH, LevelKind.DE_XUAT),
  /** Người quyết định yêu cầu xem lại, có lý do. */
  REQUEST_REVIEW(
      Set.of(AppraisalStatus.CHO_QUYET_DINH), AppraisalStatus.DA_THAM_DINH, LevelKind.QUYET_DINH),
  /** Quyết định xếp loại. */
  DECIDE(
      Set.of(AppraisalStatus.CHO_QUYET_DINH), AppraisalStatus.DA_QUYET_DINH, LevelKind.QUYET_DINH),
  /** EVT-18 của quyết định. */
  MARK_SIGNED(
      Set.of(AppraisalStatus.DA_QUYET_DINH), AppraisalStatus.DA_KY_SO, LevelKind.QUYET_DINH),
  /** EVT-25 trong thời hạn khiếu nại. */
  FILE_COMPLAINT(Set.of(AppraisalStatus.DA_KY_SO), AppraisalStatus.KHIEU_NAI, LevelKind.QUYET_DINH),
  /** Kết luận khiếu nại giữ nguyên. */
  CONCLUDE_COMPLAINT_KEEP(
      Set.of(AppraisalStatus.KHIEU_NAI), AppraisalStatus.DA_KY_SO, LevelKind.QUYET_DINH),
  /** Kết luận khiếu nại có điều chỉnh (thi hành qua QT-13). */
  CONCLUDE_COMPLAINT_ADJUST(
      Set.of(AppraisalStatus.KHIEU_NAI), AppraisalStatus.DA_KHOA, LevelKind.QUYET_DINH),
  /** Hết hạn khiếu nại hoặc khoá gói, không còn khiếu nại mở. */
  LOCK(Set.of(AppraisalStatus.DA_KY_SO), AppraisalStatus.DA_KHOA, LevelKind.QUYET_DINH);

  private final Set<AppraisalStatus> from;
  private final AppraisalStatus to;
  private final LevelKind stage;

  ProfileCommand(Set<AppraisalStatus> from, AppraisalStatus to, LevelKind stage) {
    this.from = from;
    this.to = to;
    this.stage = stage;
  }

  public Set<AppraisalStatus> from() {
    return from;
  }

  public AppraisalStatus to() {
    return to;
  }

  public LevelKind stage() {
    return stage;
  }
}

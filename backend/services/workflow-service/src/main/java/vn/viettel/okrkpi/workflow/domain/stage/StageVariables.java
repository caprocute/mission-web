package vn.viettel.okrkpi.workflow.domain.stage;

/**
 * Tên biến của bốn mẫu phần 2 (TL-01 G2 quy tắc 5: chỉ id, mã và tham số nhỏ). Biến chung với phần
 * 1 ({@code tenantId}, {@code khoi}, {@code donViId}, {@code tieuDe}, {@code phienBanDoiTuong}) lấy
 * từ {@code ApprovalVariables}.
 */
public final class StageVariables {

  /** Kế hoạch bước ({@link StagePlan#encode()}). */
  public static final String KE_HOACH_BUOC = "keHoachBuoc";

  /** Chế độ kỳ của mẫu hồ sơ đánh giá: {@code QUY} hoặc {@code NAM}. */
  public static final String CHE_DO = "cheDo";

  /** Người được đánh giá (người làm bước tự đánh giá). */
  public static final String NGUOI_DUOC_DANH_GIA = "nguoiDuocDanhGia";

  /** Chức danh của người được đánh giá, dùng cho điều kiện bỏ qua cấp. */
  public static final String CHUC_DANH = "chucDanh";

  /** Người tạo đối tượng: người khiếu nại, người tạo yêu cầu mở lại, người soạn Policy Pack. */
  public static final String NGUOI_TAO = "nguoiTao";

  /** Hồ sơ đánh giá liên quan (khiếu nại, mở lại kỳ). */
  public static final String HO_SO_ID = "hoSoId";

  /**
   * Người đã tham gia hồ sơ theo nhóm (tách vai trò), khuôn {@code nhóm=id|id;nhóm=id}; nhóm theo
   * ràng buộc 21.1: {@code acceptor}, {@code appraiser}, {@code proposer}, {@code decider}.
   */
  public static final String THAM_GIA = "thamGiaHoSo";

  /** Saga phát hành Policy Pack đã xong (EVT-13 về trước khi tiến trình tới bước chờ). */
  public static final String DA_PHAT_HANH = "daPhatHanh";

  /** Hạn tổng thể (ISO-8601) của mẫu khiếu nại, từ EVT-25 {@code responseDeadline}. */
  public static final String HAN_TONG_THE = "hanTongThe";

  /** Số lần đã trả lại bổ sung (QT-09 bước 9). */
  public static final String SO_LAN_TRA_LAI = "soLanTraLai";

  /** Số lần trả lại tối đa (tham số, mặc định 2). */
  public static final String MAX_TRA_LAI = "maxTraLai";

  /** Số lần quá hạn theo bước, khuôn {@code elementId=n,...}. */
  public static final String SO_LAN_QUA_HAN = "soLanQuaHan";

  /** Đơn vị nơi đã chọn người. */
  public static final String DON_VI_CHON = "donViChon";

  /** Người xử lý khiếu nại. */
  public static final String NGUOI_XU_LY = "nguoiXuLy";

  /** Người duyệt lượt 1, lượt 2 của mở lại kỳ. */
  public static final String NGUOI_DUYET_1 = "nguoiDuyet1";

  public static final String NGUOI_DUYET_2 = "nguoiDuyet2";

  /** Người phê duyệt phát hành Policy Pack. */
  public static final String NGUOI_PHE_DUYET = "nguoiPheDuyet";

  /**
   * Kết quả bước do lõi báo (ví dụ {@code NOP}, {@code DUYET}, {@code TRA_LAI}, {@code XEM_LAI}).
   */
  public static final String KET_QUA = "ketQua";

  /** Mức tự đề xuất và mức thẩm định (dữ kiện của điều kiện bỏ qua cấp). */
  public static final String MUC_TU_DE_XUAT = "mucTuDeXuat";

  public static final String MUC_THAM_DINH = "mucThamDinh";

  /** Kết quả của điều kiện bỏ qua bước đề xuất. */
  public static final String BO_QUA_DE_XUAT = "boQuaDeXuat";

  /** Biến do listener lập lịch đặt khi vào một bước. */
  public static final String NHOM_BUOC = "nhomBuoc";

  public static final String NGUOI_BUOC = "nguoiBuoc";
  public static final String HAN_BUOC = "hanBuoc";
  public static final String NHAC_BUOC = "nhacBuoc";

  /** Tên message chờ ký số của các mốc (EVT-18). */
  public static final String MESSAGE_KY_SO = "ky-so-hoan-tat";

  /** Tên message chờ saga phát hành Policy Pack (EVT-13). */
  public static final String MESSAGE_PHAT_HANH = "policy-pack-da-phat-hanh";

  private StageVariables() {}

  /** Tiền tố của worker chọn người trước một bước ({@code chon_<bước>}). */
  public static final String CHOOSER_PREFIX = "chon_";

  /** Phần tử chờ ký của bước. */
  public static String signWait(String elementId) {
    return "choKy_" + elementId;
  }

  /** Sự kiện biên quá hạn của bước. */
  public static String overdueTimer(String elementId) {
    return "quaHan_" + elementId;
  }

  /** Cờ đã nhận EVT-18 của bước, khi chữ ký về trước lúc lõi báo xong bước. */
  public static String signedFlag(String elementId) {
    return "daKy_" + elementId;
  }
}

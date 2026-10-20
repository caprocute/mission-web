package vn.viettel.okrkpi.shared.error;

import java.util.Map;

/**
 * Loại lỗi chuẩn theo HTTP status cho các lỗi khung (không có mã nghiệp vụ trong hợp đồng): URI
 * {@code type}, tiêu đề và mô tả tiếng Việt chung. Mô tả cố ý chung chung để không lộ thông tin nội
 * bộ (TL-01 "Định dạng lỗi chuẩn RFC 9457"; BACKEND.md mục 3 quy ước 2).
 */
public final class ProblemTypes {

  /** Gốc URI {@code type} của hợp đồng ({@code common.yaml}, TL-01). */
  public static final String BASE_URI = "https://okrkpi.vn/problems/";

  /** Slug của xung đột ghi đồng thời (BS-02 HD-01, {@code common.yaml} response Conflict). */
  public static final String CONCURRENT_MODIFICATION = BASE_URI + "concurrent-modification";

  /** Tiêu đề của xung đột ghi đồng thời, theo ví dụ của {@code common.yaml}. */
  public static final String CONCURRENT_MODIFICATION_TITLE = "Dữ liệu đã được người khác cập nhật";

  /** Mã lỗi khung đã có trong hợp đồng: kiểm dữ liệu theo trường. */
  public static final String VALIDATION_CODE = "COMMON-422-VALIDATION";

  /** Mã lỗi khung đã có trong hợp đồng: OPA/RBAC từ chối. */
  public static final String FORBIDDEN_CODE = "COMMON-403-OUT-OF-SCOPE";

  /**
   * Loại lỗi chuẩn.
   *
   * @param type URI
   * @param title tiêu đề tiếng Việt
   * @param detail mô tả chung, không chứa thông tin nội bộ
   */
  public record Standard(String type, String title, String detail) {}

  private static final Standard FALLBACK_4XX =
      new Standard(
          BASE_URI + "bad-request",
          "Yêu cầu không hợp lệ",
          "Yêu cầu không thực hiện được. Vui lòng kiểm tra lại.");

  private static final Standard INTERNAL =
      new Standard(
          BASE_URI + "internal-error",
          "Hệ thống đang gặp sự cố",
          "Đã có lỗi ngoài dự kiến. Vui lòng thử lại sau; nếu lỗi lặp lại, liên hệ quản trị và cung cấp mã tra cứu.");

  private static final Map<Integer, Standard> BY_STATUS =
      Map.ofEntries(
          Map.entry(400, FALLBACK_4XX),
          Map.entry(
              401,
              new Standard(
                  BASE_URI + "unauthorized",
                  "Chưa đăng nhập hoặc phiên làm việc đã hết hạn",
                  "Vui lòng đăng nhập lại để tiếp tục.")),
          Map.entry(
              403,
              new Standard(
                  BASE_URI + "forbidden",
                  "Không có quyền thực hiện thao tác này",
                  "Tài khoản không có quyền với dữ liệu hoặc thao tác này.")),
          Map.entry(
              404,
              new Standard(
                  BASE_URI + "not-found",
                  "Không tìm thấy dữ liệu",
                  "Dữ liệu không tồn tại hoặc nằm ngoài phạm vi được xem.")),
          Map.entry(
              405,
              new Standard(
                  BASE_URI + "method-not-allowed",
                  "Thao tác không được hỗ trợ",
                  "Thao tác này không áp dụng cho dữ liệu đã chọn.")),
          Map.entry(
              406,
              new Standard(
                  BASE_URI + "not-acceptable",
                  "Định dạng phản hồi không được hỗ trợ",
                  "Hệ thống không trả được dữ liệu theo định dạng yêu cầu.")),
          Map.entry(
              409,
              new Standard(
                  BASE_URI + "conflict",
                  "Xung đột dữ liệu",
                  "Thao tác không thực hiện được ở trạng thái hiện tại của dữ liệu.")),
          Map.entry(
              413,
              new Standard(
                  BASE_URI + "payload-too-large",
                  "Dữ liệu gửi lên quá lớn",
                  "Vui lòng giảm kích thước dữ liệu hoặc tệp đính kèm.")),
          Map.entry(
              415,
              new Standard(
                  BASE_URI + "unsupported-media-type",
                  "Định dạng dữ liệu không được hỗ trợ",
                  "Vui lòng gửi dữ liệu đúng định dạng.")),
          Map.entry(
              421,
              new Standard(
                  BASE_URI + "misdirected-request",
                  "Đơn vị đã chuyển sang máy chủ khác",
                  "Vui lòng tải lại trang để tiếp tục.")),
          Map.entry(
              422,
              new Standard(
                  BASE_URI + "validation",
                  "Dữ liệu chưa hợp lệ",
                  "Vui lòng kiểm tra lại dữ liệu.")),
          Map.entry(
              428,
              new Standard(
                  BASE_URI + "precondition-required",
                  "Thiếu thông tin phiên bản dữ liệu",
                  "Yêu cầu cập nhật phải kèm phiên bản dữ liệu đã đọc trước đó.")),
          Map.entry(
              429,
              new Standard(
                  BASE_URI + "too-many-requests",
                  "Thao tác quá nhanh",
                  "Vui lòng chờ một lát rồi thử lại.")),
          Map.entry(500, INTERNAL),
          Map.entry(
              502,
              new Standard(
                  BASE_URI + "bad-gateway",
                  "Hệ thống liên quan không phản hồi",
                  "Vui lòng thử lại sau.")),
          Map.entry(
              503,
              new Standard(
                  BASE_URI + "service-unavailable",
                  "Hệ thống đang bận",
                  "Hệ thống đang quá tải hoặc bảo trì. Vui lòng thử lại sau.")),
          Map.entry(
              504,
              new Standard(
                  BASE_URI + "gateway-timeout",
                  "Hệ thống phản hồi quá lâu",
                  "Vui lòng thử lại sau.")));

  private ProblemTypes() {}

  /** Loại lỗi chuẩn của một HTTP status; status lạ dùng loại chung của nhóm 4xx hoặc 5xx. */
  public static Standard forStatus(int status) {
    Standard known = BY_STATUS.get(status);
    if (known != null) {
      return known;
    }
    return status >= 500 ? INTERNAL : FALLBACK_4XX;
  }
}

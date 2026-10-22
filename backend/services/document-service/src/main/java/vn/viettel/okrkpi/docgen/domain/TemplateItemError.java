package vn.viettel.okrkpi.docgen.domain;

/** Lỗi nghiệp vụ thuần của thực thể mẫu (không phụ thuộc HTTP hay Spring). */
public enum TemplateItemError {
  TEN_TRONG("COMMON-422-VALIDATION", "Tên mục mẫu không được để trống"),
  TEN_QUA_DAI("COMMON-422-VALIDATION", "Tên mục mẫu không vượt quá 200 ký tự"),
  TRANG_THAI_KHONG_HOP_LE("COMMON-422-VALIDATION", "Chuyển trạng thái không hợp lệ"),
  KHONG_TIM_THAY("COMMON-422-VALIDATION", "Không tìm thấy mục mẫu");

  private final String catalogCode;
  private final String detail;

  TemplateItemError(String catalogCode, String detail) {
    this.catalogCode = catalogCode;
    this.detail = detail;
  }

  public String catalogCode() {
    return catalogCode;
  }

  public String detail() {
    return detail;
  }
}

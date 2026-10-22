package vn.viettel.okrkpi.docgen.internal;

/**
 * Tiện ích nội bộ của mô-đun (TL-01 "internalIsPrivateToModule": chỉ các lớp trong cùng mô-đun
 * {@code vn.viettel.okrkpi.docgen..} được phép truy cập gói {@code ..internal..}).
 */
public final class TemplateInternalSupport {

  private TemplateInternalSupport() {}

  /** Chuẩn hoá khoảng trắng liên tiếp trong tên mục mẫu. */
  public static String normalizeWhitespace(String input) {
    if (input == null) {
      return null;
    }
    return input.trim().replaceAll("\\s+", " ");
  }
}

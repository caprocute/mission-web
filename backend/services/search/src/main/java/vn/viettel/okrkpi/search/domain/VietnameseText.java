package vn.viettel.okrkpi.search.domain;

import java.text.Normalizer;

/**
 * Chuẩn hoá văn bản tiếng Việt trước khi lập chỉ mục: đưa về dạng dựng sẵn NFC (văn bản từ hệ ngoài
 * hay ở dạng tổ hợp NFD, làm cùng một từ thành hai chuỗi byte khác nhau), bỏ ký tự điều khiển, gộp
 * khoảng trắng và cắt độ dài. Bỏ dấu để tìm "giao duc" khớp "giáo dục" do bộ phân tích {@code
 * icu_folding} của chỉ mục đảm nhiệm, không làm ở đây, để kết quả vẫn hiển thị đúng dấu.
 */
public final class VietnameseText {

  public static final int MAX_LENGTH = 2000;

  private VietnameseText() {}

  public static String normalize(String text) {
    if (text == null) {
      return null;
    }
    String s =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "")
            .replaceAll("\\s+", " ")
            .strip();
    if (s.isEmpty()) {
      return null;
    }
    return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH);
  }
}

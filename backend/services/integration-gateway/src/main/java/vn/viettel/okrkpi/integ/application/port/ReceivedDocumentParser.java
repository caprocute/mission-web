package vn.viettel.okrkpi.integ.application.port;

import java.util.List;

/**
 * Đọc toàn văn văn bản chỉ đạo đã nhận (gói vOffice JSON hoặc EdXML) cho MOD-02 (CORE-02-16):
 * EVT-24 chỉ mang siêu dữ liệu, nội dung đọc lại từ thông điệp vào đã mã hoá.
 */
public interface ReceivedDocumentParser {

  /** Một đoạn văn bản, đánh số từ 1. */
  record Paragraph(int no, String text) {}

  /**
   * Nội dung đã đọc; {@code recipientExternalId} là mã đơn vị nhận phía ngoài (chưa ánh xạ).
   *
   * @param format {@code EdXML} hoặc {@code vOffice}
   */
  record Parsed(
      String format,
      String recipientExternalId,
      String issuerHeader,
      String title,
      String preamble,
      List<Paragraph> paragraphs,
      String recipients,
      String classification) {

    public Parsed {
      paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
    }
  }

  /**
   * @throws IllegalArgumentException khi gói sai cấu trúc
   */
  Parsed parse(String messageType, byte[] raw);
}

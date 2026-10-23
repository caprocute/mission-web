package vn.viettel.okrkpi.integ.infrastructure.voffice;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Bộ phân tích EdXML tối thiểu (NĐ 30/2020, gói tin liên thông văn bản) cho DV-13-03, để DV-13-05
 * dùng lại: chỉ đọc phần {@code MessageHeader} (số, ký hiệu, ngày ban hành, cơ quan ban hành, đơn
 * vị nhận đầu tiên, trích yếu), bỏ qua tệp đính kèm. Tắt DTD và thực thể ngoài để chống XXE; so
 * khớp theo tên cục bộ nên không phụ thuộc tiền tố không gian tên.
 */
final class EdxmlParser {

  static final int MAX_BYTES = 2 * 1024 * 1024;

  private EdxmlParser() {}

  static Directive parse(byte[] xml) {
    if (xml.length > MAX_BYTES) {
      throw new IllegalArgumentException("Gói EdXML vượt quá 2 MB");
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    String codeNumber = null;
    String codeNotation = null;
    String date = null;
    String issuer = null;
    String recipient = null;
    String subject = null;
    Deque<String> path = new ArrayDeque<>();
    try {
      XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        while (r.hasNext()) {
          int event = r.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            String name = r.getLocalName();
            String parent = path.peek();
            path.push(name);
            if (!inHeader(path)) {
              continue;
            }
            switch (name) {
              case "CodeNumber" -> codeNumber = text(r, path);
              case "CodeNotation" -> codeNotation = text(r, path);
              case "PromulgationDate" -> date = text(r, path);
              case "Subject" -> subject = text(r, path);
              case "OrganName" -> {
                String v = text(r, path);
                if ("From".equals(parent) && issuer == null) {
                  issuer = v;
                }
              }
              case "OrganId" -> {
                String v = text(r, path);
                if ("To".equals(parent) && recipient == null) {
                  recipient = v;
                }
              }
              default -> {
                // Bỏ qua phần tử khác của tiêu đề.
              }
            }
          } else if (event == XMLStreamConstants.END_ELEMENT) {
            path.pop();
          }
        }
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("Gói EdXML không đúng cấu trúc", e);
    }
    if (codeNumber == null || date == null || subject == null || issuer == null) {
      throw new IllegalArgumentException(
          "Gói EdXML thiếu số, ngày, trích yếu hoặc cơ quan ban hành");
    }
    String docNumber = codeNotation == null ? codeNumber : codeNumber + "/" + codeNotation;
    try {
      return new Directive(docNumber, LocalDate.parse(date), issuer, subject, recipient);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Ngày ban hành trong gói EdXML không hợp lệ", e);
    }
  }

  private static boolean inHeader(Deque<String> path) {
    return path.contains("MessageHeader");
  }

  /** Đọc văn bản của phần tử hiện tại; {@code getElementText} tiêu thụ luôn thẻ đóng. */
  private static String text(XMLStreamReader r, Deque<String> path) throws XMLStreamException {
    String v = r.getElementText().trim();
    path.pop();
    return v.isEmpty() ? null : v;
  }
}

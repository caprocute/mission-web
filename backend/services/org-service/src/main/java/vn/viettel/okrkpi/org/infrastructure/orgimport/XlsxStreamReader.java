package vn.viettel.okrkpi.org.infrastructure.orgimport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.port.SpreadsheetReader;

/**
 * Đọc trang tính đầu tiên của tệp .xlsx (Office Open XML) bằng {@code java.util.zip} và StAX của JDK, không thêm thư
 * viện (quy tắc 1 AGENTS.md): dòng được đọc và chuyển đi lần lượt, không dựng cây DOM. Chặn thực thể ngoài (XXE) và
 * giới hạn kích thước giải nén để chống tệp nén bom.
 *
 * <p>Hỗ trợ ô chuỗi dùng chung ({@code t="s"}), chuỗi nội tuyến ({@code inlineStr}), số, logic và chuỗi công thức;
 * ngày Excel trả về dạng số ngày để tầng ứng dụng tự quy đổi.
 */
@Component
public class XlsxStreamReader implements SpreadsheetReader {

  /** Giới hạn tổng dung lượng giải nén (suy luận): tệp 10 MB thường giải nén dưới 100 MB. */
  static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;

  private static final XMLInputFactory XML = XMLInputFactory.newFactory();

  static {
    XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    XML.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
  }

  @Override
  public void read(byte[] xlsx, RowHandler rowHandler) {
    try {
      byte[] sheet;
      List<String> shared = List.of();
      Map<String, byte[]> sheets = new HashMap<>();
      long total = 0;
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
          String name = e.getName();
          if (name.equals("xl/sharedStrings.xml")) {
            byte[] data = readLimited(zip, MAX_UNCOMPRESSED - total);
            total += data.length;
            shared = sharedStrings(new ByteArrayInputStream(data));
          } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
            byte[] data = readLimited(zip, MAX_UNCOMPRESSED - total);
            total += data.length;
            sheets.put(name, data);
          }
        }
      }
      // Chuỗi dùng chung có thể nằm sau trang tính trong gói ZIP, nên trang tính được giữ trong bộ nhớ dạng XML rồi mới duyệt
      // dòng; giới hạn 10 MB của tệp tải lên chặn kích thước. Mẫu do Excel hoặc LibreOffice lưu đặt trang đầu tiên là sheet1.xml; tệp khác lấy trang có số nhỏ nhất.
      sheet = sheets.get("xl/worksheets/sheet1.xml");
      if (sheet == null && !sheets.isEmpty()) {
        sheet = sheets.entrySet().stream().min(Map.Entry.comparingByKey()).get().getValue();
      }
      if (sheet == null) {
        throw new InvalidSpreadsheetException("Tệp không có trang tính", null);
      }
      rows(new ByteArrayInputStream(sheet), shared, rowHandler);
    } catch (IOException | XMLStreamException e) {
      throw new InvalidSpreadsheetException("Tệp không đọc được như bảng tính", e);
    }
  }

  private static byte[] readLimited(InputStream in, long limit) throws IOException {
    byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, Math.max(0, limit) + 1));
    if (data.length > limit) {
      throw new InvalidSpreadsheetException("Tệp giải nén vượt giới hạn", null);
    }
    return data;
  }

  private static List<String> sharedStrings(InputStream in) throws XMLStreamException {
    List<String> out = new ArrayList<>();
    XMLStreamReader x = XML.createXMLStreamReader(in);
    try {
      StringBuilder cur = null;
      boolean inPhonetic = false;
      while (x.hasNext()) {
        int ev = x.next();
        if (ev == XMLStreamConstants.START_ELEMENT) {
          switch (x.getLocalName()) {
            case "si" -> cur = new StringBuilder();
            case "rPh" -> inPhonetic = true;
            case "t" -> {
              if (cur != null && !inPhonetic) {
                cur.append(x.getElementText());
              }
            }
            default -> {
            }
          }
        } else if (ev == XMLStreamConstants.END_ELEMENT) {
          if ("si".equals(x.getLocalName()) && cur != null) {
            out.add(cur.toString());
            cur = null;
          } else if ("rPh".equals(x.getLocalName())) {
            inPhonetic = false;
          }
        }
      }
      return out;
    } finally {
      x.close();
    }
  }

  private static void rows(InputStream in, List<String> shared, RowHandler handler) throws XMLStreamException {
    XMLStreamReader x = XML.createXMLStreamReader(in);
    try {
      List<String> cells = null;
      int rowNumber = 0;
      int col = 0;
      String type = null;
      StringBuilder value = null;
      while (x.hasNext()) {
        int ev = x.next();
        if (ev == XMLStreamConstants.START_ELEMENT) {
          switch (x.getLocalName()) {
            case "row" -> {
              String r = x.getAttributeValue(null, "r");
              rowNumber = r == null ? rowNumber + 1 : Integer.parseInt(r);
              cells = new ArrayList<>();
              col = 0;
            }
            case "c" -> {
              String ref = x.getAttributeValue(null, "r");
              col = ref == null ? (cells == null ? 0 : cells.size()) : columnIndex(ref);
              type = x.getAttributeValue(null, "t");
              value = new StringBuilder();
            }
            case "v", "t" -> {
              if (value != null) {
                value.append(x.getElementText());
              }
            }
            default -> {
            }
          }
        } else if (ev == XMLStreamConstants.END_ELEMENT) {
          if ("c".equals(x.getLocalName()) && cells != null && value != null) {
            String raw = value.toString();
            String text = "s".equals(type) ? shared(shared, raw) : "b".equals(type) ? ("1".equals(raw) ? "TRUE"
                : "FALSE") : raw;
            while (cells.size() < col) {
              cells.add("");
            }
            cells.add(text);
            value = null;
          } else if ("row".equals(x.getLocalName()) && cells != null) {
            handler.row(rowNumber, cells);
            cells = null;
          }
        }
      }
    } finally {
      x.close();
    }
  }

  private static String shared(List<String> shared, String raw) {
    try {
      int i = Integer.parseInt(raw.trim());
      return i >= 0 && i < shared.size() ? shared.get(i) : "";
    } catch (NumberFormatException e) {
      return "";
    }
  }

  /** {@code "C12"} → 2 (cột tính từ 0). */
  static int columnIndex(String ref) {
    int n = 0;
    for (int i = 0; i < ref.length(); i++) {
      char ch = ref.charAt(i);
      if (ch < 'A' || ch > 'Z') {
        break;
      }
      n = n * 26 + (ch - 'A' + 1);
    }
    return Math.max(0, n - 1);
  }
}

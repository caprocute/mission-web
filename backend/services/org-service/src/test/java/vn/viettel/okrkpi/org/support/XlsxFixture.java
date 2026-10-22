package vn.viettel.okrkpi.org.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dựng tệp .xlsx tối thiểu (chuỗi nội tuyến) cho kiểm thử nhập cây tổ chức, không cần thư viện bảng tính. */
public final class XlsxFixture {

  private XlsxFixture() {
  }

  public static byte[] of(List<List<String>> rows) {
    StringBuilder sheet = new StringBuilder(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/"
            + "spreadsheetml/2006/main\"><sheetData>");
    for (int r = 0; r < rows.size(); r++) {
      sheet.append("<row r=\"").append(r + 1).append("\">");
      List<String> cells = rows.get(r);
      for (int c = 0; c < cells.size(); c++) {
        String v = cells.get(c);
        if (v == null || v.isEmpty()) {
          continue;
        }
        String ref = String.valueOf((char) ('A' + c)) + (r + 1);
        if (v.matches("\\d+")) {
          sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
        } else {
          sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(escape(v))
              .append("</t></is></c>");
        }
      }
      sheet.append("</row>");
    }
    sheet.append("</sheetData></worksheet>");
    try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
      put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas."
          + "openxmlformats.org/package/2006/content-types\"/>");
      put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas."
          + "openxmlformats.org/spreadsheetml/2006/main\"><sheets><sheet name=\"Đơn vị\" sheetId=\"1\"/></sheets>"
          + "</workbook>");
      put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
      zip.finish();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String escape(String v) {
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

package vn.viettel.okrkpi.integ.infrastructure.hrm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

/**
 * Đọc tệp lô CSV (UTF-8, RFC 4180, phân cách phẩy, chấm phẩy hoặc tab tự nhận theo dòng tiêu đề) và
 * Excel (.xlsx, trang đầu) thành bảng có số dòng gốc để báo lỗi theo dòng. Tên cột so khớp không
 * phân biệt hoa thường và khoảng trắng hai đầu.
 */
final class BatchFiles {

  static final String TYPE_JSON = "HRM_JSON";
  static final String TYPE_CSV = "HRM_CSV";
  static final String TYPE_XLSX = "HRM_XLSX";

  /** Tệp vượt dung lượng cho phép: chỉ lưu tên tệp để quản trị biết mà xử lý. */
  static final String TYPE_OVERSIZE = "HRM_QUA_CO";

  static final int MAX_ROWS = 200_000;

  private BatchFiles() {}

  /** Một dòng dữ liệu: số dòng trong tệp (tiêu đề là dòng 1 với CSV), ô theo tên cột đã chuẩn. */
  record RawRow(int line, Map<String, String> cells, boolean ragged) {}

  record Table(List<String> header, List<RawRow> rows) {}

  /** Lỗi cấu trúc cả tệp (mã hoá, tiêu đề, định dạng); thông điệp không chứa nội dung dòng. */
  static final class FormatException extends Exception {
    private static final long serialVersionUID = 1L;

    FormatException(String message) {
      super(message);
    }
  }

  static String column(String name) {
    return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
  }

  static Table csv(byte[] data) throws FormatException {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(data))
              .toString();
    } catch (CharacterCodingException e) {
      throw new FormatException("Tệp không phải văn bản UTF-8");
    }
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    char sep = separator(text);
    List<int[]> starts = new ArrayList<>();
    List<List<String>> records = parse(text, sep, starts);
    if (records.isEmpty()) {
      throw new FormatException("Tệp rỗng, thiếu dòng tiêu đề");
    }
    List<String> header = header(records.get(0));
    List<RawRow> rows = new ArrayList<>();
    for (int i = 1; i < records.size(); i++) {
      List<String> r = records.get(i);
      if (r.stream().allMatch(String::isBlank)) {
        continue;
      }
      rows.add(row(starts.get(i)[0], header, r));
      if (rows.size() > MAX_ROWS) {
        throw new FormatException("Tệp vượt " + MAX_ROWS + " dòng");
      }
    }
    return new Table(header, rows);
  }

  static Table xlsx(byte[] data) throws FormatException {
    try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(data))) {
      Optional<Sheet> first =
          wb.getFirstSheet() == null ? Optional.empty() : Optional.of(wb.getFirstSheet());
      if (first.isEmpty()) {
        throw new FormatException("Tệp Excel không có trang tính");
      }
      List<String> header = null;
      List<RawRow> rows = new ArrayList<>();
      try (Stream<Row> stream = first.get().openStream()) {
        for (Row row : (Iterable<Row>) stream::iterator) {
          List<String> cells = new ArrayList<>();
          for (int c = 0; c < row.getCellCount(); c++) {
            cells.add(text(row.getCell(c)));
          }
          if (cells.stream().allMatch(String::isBlank)) {
            continue;
          }
          if (header == null) {
            header = header(cells);
            continue;
          }
          rows.add(row(row.getRowNum(), header, cells));
          if (rows.size() > MAX_ROWS) {
            throw new FormatException("Tệp vượt " + MAX_ROWS + " dòng");
          }
        }
      }
      if (header == null) {
        throw new FormatException("Tệp rỗng, thiếu dòng tiêu đề");
      }
      return new Table(header, rows);
    } catch (IOException | RuntimeException e) {
      throw new FormatException("Tệp Excel không đọc được");
    }
  }

  private static RawRow row(int line, List<String> header, List<String> values) {
    Map<String, String> cells = new HashMap<>();
    for (int c = 0; c < header.size() && c < values.size(); c++) {
      cells.put(header.get(c), values.get(c));
    }
    boolean ragged = false;
    for (int c = header.size(); c < values.size(); c++) {
      if (!values.get(c).isBlank()) {
        ragged = true;
      }
    }
    return new RawRow(line, cells, ragged);
  }

  private static List<String> header(List<String> raw) throws FormatException {
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (String h : raw) {
      String c = column(h);
      if (!c.isEmpty() && seen.put(c, Boolean.TRUE) != null) {
        throw new FormatException("Tiêu đề có cột trùng tên: " + h.strip());
      }
    }
    List<String> header = new ArrayList<>();
    for (String h : raw) {
      header.add(column(h));
    }
    return header;
  }

  private static String text(Cell cell) {
    if (cell == null) {
      return "";
    }
    return switch (cell.getType()) {
      case NUMBER -> {
        BigDecimal n = cell.asNumber();
        yield n == null ? "" : n.stripTrailingZeros().toPlainString();
      }
      case BOOLEAN -> String.valueOf(cell.asBoolean());
      case EMPTY, ERROR -> "";
      default -> cell.getText() == null ? "" : cell.getText();
    };
  }

  private static char separator(String text) {
    int end = text.indexOf('\n');
    String first = end < 0 ? text : text.substring(0, end);
    int comma = 0;
    int semi = 0;
    int tab = 0;
    boolean quoted = false;
    for (char ch : first.toCharArray()) {
      if (ch == '"') {
        quoted = !quoted;
      } else if (!quoted) {
        switch (ch) {
          case ',' -> comma++;
          case ';' -> semi++;
          case '\t' -> tab++;
          default -> {}
        }
      }
    }
    if (tab > comma && tab > semi) {
      return '\t';
    }
    return semi > comma ? ';' : ',';
  }

  /** RFC 4180: ô trong ngoặc kép có thể chứa dấu phân cách, xuống dòng và {@code ""}. */
  private static List<List<String>> parse(String text, char sep, List<int[]> starts)
      throws FormatException {
    List<List<String>> out = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean any = false;
    int line = 1;
    int recordLine = 1;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          if (ch == '\n') {
            line++;
          }
          cell.append(ch);
        }
        continue;
      }
      if (ch == '"' && cell.isEmpty()) {
        quoted = true;
        any = true;
      } else if (ch == sep) {
        record.add(cell.toString());
        cell.setLength(0);
        any = true;
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        if (any || !cell.isEmpty()) {
          record.add(cell.toString());
          out.add(record);
          starts.add(new int[] {recordLine});
        }
        record = new ArrayList<>();
        cell.setLength(0);
        any = false;
        line++;
        recordLine = line;
      } else {
        cell.append(ch);
        any = true;
      }
    }
    if (quoted) {
      throw new FormatException("Dòng " + recordLine + ": ngoặc kép chưa đóng");
    }
    if (any || !cell.isEmpty()) {
      record.add(cell.toString());
      out.add(record);
      starts.add(new int[] {recordLine});
    }
    return out;
  }
}

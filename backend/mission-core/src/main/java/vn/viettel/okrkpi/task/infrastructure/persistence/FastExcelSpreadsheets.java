package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import vn.viettel.okrkpi.task.application.catalog.CatalogSpreadsheets;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorRow;

/**
 * {@link CatalogSpreadsheets} bằng FastExcel: đọc, ghi .xlsx theo luồng, không dựng cả tệp trong bộ
 * nhớ.
 */
public class FastExcelSpreadsheets implements CatalogSpreadsheets {

  /** Số cột đọc tối đa mỗi dòng; cột thừa bị bỏ qua. */
  private static final int MAX_COLUMNS = 64;

  @Override
  public void read(Path file, RowHandler handler) {
    try (ReadableWorkbook wb = new ReadableWorkbook(file.toFile())) {
      Sheet sheet = wb.getFirstSheet();
      try (Stream<Row> rows = sheet.openStream()) {
        Iterator<Row> it = rows.iterator();
        while (it.hasNext()) {
          Row row = it.next();
          int count = Math.min(row.getCellCount(), MAX_COLUMNS);
          List<String> cells = new ArrayList<>(count);
          for (int i = 0; i < count; i++) {
            String v = row.getOptionalCell(i).map(Cell::getText).orElse("");
            cells.add(v);
          }
          if (!handler.row(row.getRowNum(), cells)) {
            return;
          }
        }
      }
    } catch (IOException | ExcelReaderException | IllegalStateException e) {
      throw new UnreadableFileException(e);
    }
  }

  @Override
  public void writeErrors(OutputStream out, List<String> header, List<ErrorRow> rows)
      throws IOException {
    Workbook wb = new Workbook(out, "Mission", "1.0");
    Worksheet ws = wb.newWorksheet("Dòng lỗi");
    for (int c = 0; c < header.size(); c++) {
      ws.value(0, c, header.get(c));
    }
    int r = 1;
    for (ErrorRow row : rows) {
      ws.value(r, 0, row.rowNo());
      for (int c = 0; c < row.values().size(); c++) {
        ws.value(r, c + 1, row.values().get(c));
      }
      ws.value(r, row.values().size() + 1, row.reason());
      r++;
    }
    wb.finish();
  }
}

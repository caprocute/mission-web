package vn.viettel.okrkpi.appraisal.infrastructure.job;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewSheets;

/** Ghi tệp xuất danh sách hồ sơ đánh giá bằng FastExcel (thư viện đã duyệt ở CORE-02-04). */
public class FastExcelReviewSheets implements ReviewSheets {

  @Override
  public void write(
      OutputStream out, String sheet, List<String> header, Iterable<List<Object>> rows)
      throws IOException {
    Workbook wb = new Workbook(out, "Mission", "1.0");
    Worksheet ws = wb.newWorksheet(sheet);
    for (int c = 0; c < header.size(); c++) {
      ws.value(0, c, header.get(c));
      ws.style(0, c).bold().set();
    }
    int r = 1;
    for (List<Object> row : rows) {
      for (int c = 0; c < row.size(); c++) {
        Object v = row.get(c);
        if (v instanceof Number n) {
          ws.value(r, c, n);
        } else {
          ws.value(r, c, v == null ? "" : v.toString());
        }
      }
      r++;
    }
    wb.finish();
  }
}

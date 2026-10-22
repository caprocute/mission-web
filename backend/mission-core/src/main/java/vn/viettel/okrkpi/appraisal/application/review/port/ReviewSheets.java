package vn.viettel.okrkpi.appraisal.application.review.port;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Ghi bảng tính XLSX của danh sách hồ sơ xuất ra. */
@FunctionalInterface
public interface ReviewSheets {

  void write(OutputStream out, String sheet, List<String> header, Iterable<List<Object>> rows)
      throws IOException;
}

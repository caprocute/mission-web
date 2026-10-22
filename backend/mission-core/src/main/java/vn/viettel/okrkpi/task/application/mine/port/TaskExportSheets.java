package vn.viettel.okrkpi.task.application.mine.port;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Ghi bảng .xlsx một trang tính theo luồng. */
public interface TaskExportSheets {

  void write(OutputStream out, String sheet, List<String> header, Iterable<List<Object>> rows)
      throws IOException;
}

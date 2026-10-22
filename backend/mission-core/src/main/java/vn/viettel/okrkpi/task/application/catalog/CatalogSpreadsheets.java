package vn.viettel.okrkpi.task.application.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob;

/** Cổng đọc, ghi bảng tính của nhập danh mục; hiện thực bằng thư viện Excel ở hạ tầng. */
public interface CatalogSpreadsheets {

  /** Tệp không phải bảng tính đọc được. */
  final class UnreadableFileException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    public UnreadableFileException(Throwable cause) {
      super("Tệp không đọc được", cause);
    }
  }

  /** Nhận từng dòng của trang tính đầu (số dòng tính từ 1); trả {@code false} để dừng đọc. */
  @FunctionalInterface
  interface RowHandler {
    boolean row(int rowNo, List<String> cells);
  }

  /** Đọc theo luồng trang tính đầu tiên. */
  void read(Path file, RowHandler handler);

  /** Ghi tệp lỗi: dòng tiêu đề rồi các dòng lỗi. */
  void writeErrors(OutputStream out, List<String> header, List<CatalogImportJob.ErrorRow> rows)
      throws IOException;
}

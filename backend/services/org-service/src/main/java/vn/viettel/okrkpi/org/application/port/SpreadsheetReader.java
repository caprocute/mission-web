package vn.viettel.okrkpi.org.application.port;

import java.util.List;

/** Đọc trang tính đầu tiên của tệp Excel theo luồng, mỗi dòng một danh sách ô dạng chuỗi (ô trống là chuỗi rỗng). */
public interface SpreadsheetReader {

  /**
   * @param rowHandler nhận số dòng trong tệp (từ 1) và giá trị các ô theo cột
   * @throws InvalidSpreadsheetException khi tệp không phải bảng tính Excel hợp lệ
   */
  void read(byte[] xlsx, RowHandler rowHandler);

  @FunctionalInterface
  interface RowHandler {
    void row(int rowNumber, List<String> cells);
  }

  /** Tệp không đọc được như một bảng tính. */
  class InvalidSpreadsheetException extends RuntimeException {
    public InvalidSpreadsheetException(String message, Throwable cause) {
      super(message, cause);
    }
  }

}

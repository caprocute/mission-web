package vn.viettel.okrkpi.evidence.application;

import java.nio.file.Path;

/**
 * Cổng dựng ảnh xem trước: chuyển Office sang PDF (Gotenberg) và dựng ảnh trang (PDFBox, ImageIO).
 */
public interface PreviewRendering {

  /** Nhận ảnh PNG của từng trang theo thứ tự, trang đầu là 1. */
  @FunctionalInterface
  interface PageSink {
    void accept(int page, byte[] png, int width, int height);
  }

  /** Chuyển DOCX, XLSX sang PDF; ghi kết quả vào {@code target}. */
  void toPdf(Path source, String fileName, Path target);

  /**
   * Dựng ảnh các trang PDF ở {@code dpi}, tối đa {@code maxPages} trang.
   *
   * @return tổng số trang đã dựng
   */
  int rasterizePdf(Path pdf, float dpi, int maxPages, PageSink sink);

  /** Thu nhỏ ảnh JPEG, PNG về chiều rộng tối đa {@code maxWidth}, mã hoá lại PNG (bỏ EXIF). */
  void scaleImage(Path image, int maxWidth, PageSink sink);

  /** Lỗi dựng: {@code transientFailure} thì thử lại, ngược lại tệp không dựng được. */
  class RenderFailure extends RuntimeException {
    private final boolean transientFailure;

    public RenderFailure(String message, boolean transientFailure, Throwable cause) {
      super(message, cause);
      this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
      return transientFailure;
    }
  }
}

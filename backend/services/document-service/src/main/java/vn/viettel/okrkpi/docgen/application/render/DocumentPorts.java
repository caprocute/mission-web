package vn.viettel.okrkpi.docgen.application.render;

import java.util.Map;

/** Các cổng kỹ thuật của đường ống sinh: trộn mẫu, chuyển PDF/A, kiểm PDF/A, kho tệp. */
public final class DocumentPorts {

  private DocumentPorts() {}

  /** Trộn giá trị vào chỗ giữ {@code ${ten}} của mẫu DOCX (docx4j). */
  public interface DocxMerger {
    byte[] merge(byte[] template, Map<String, String> fields);
  }

  /** Chuyển DOCX sang PDF/A-2b (Gotenberg 8). */
  public interface PdfConverter {
    byte[] toPdfA2b(byte[] docx, String fileName);
  }

  /** Kiểm tệp có đạt PDF/A-2b không (veraPDF). */
  public interface PdfaValidator {
    Result validate(byte[] pdf);

    /** Kết quả kiểm; {@code summary} là mô tả ngắn các điều khoản vi phạm. */
    record Result(boolean compliant, String summary) {}
  }

  /** Kho đối tượng S3 (Ceph RGW), bucket {@code templates} và {@code dossier} (TL-03). */
  public interface ObjectStore {
    byte[] get(String bucket, String key);

    void put(String bucket, String key, byte[] content, String contentType);
  }

  /**
   * Lỗi tạm thời của hạ tầng (Gotenberg, kho tệp, module nguồn): tác vụ được thử lại thay vì chuyển
   * {@code LOI} ngay.
   */
  public static class TransientFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransientFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Dữ liệu, tệp mẫu hoặc tệp sinh ra không dùng được (băm lệch, mẫu hỏng, PDF không đạt chuẩn):
   * tác vụ chuyển {@code LOI}, không thử lại.
   */
  public static class RejectedDocument extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RejectedDocument(String message) {
      super(message);
    }
  }
}

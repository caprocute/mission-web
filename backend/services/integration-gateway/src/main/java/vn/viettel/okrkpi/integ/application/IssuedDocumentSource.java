package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * SPI tra cứu văn bản đã ban hành ở hệ văn bản ngoài (vOffice, ĐHTN) theo đơn vị phía ngoài
 * (BR-07-13): tra cứu chỉ trả siêu dữ liệu và tham chiếu tệp gốc; tải tệp gốc chỉ khi MOD-03 nhận
 * văn bản làm minh chứng (CORE-03-04).
 */
public interface IssuedDocumentSource {

  /** Một văn bản đã ban hành; {@code ref} là tham chiếu tệp gốc phía ngoài. */
  record IssuedDocument(
      String ref,
      String docNumber,
      LocalDate docDate,
      String summary,
      String signerName,
      boolean signatureValid) {}

  /** Một trang kết quả; {@code nextCursor} rỗng khi hết. */
  record Result(List<IssuedDocument> items, String nextCursor) {
    public Result {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }

  /**
   * Tệp gốc của văn bản: nội dung, kiểu, tên tệp và SHA-256 của bản đã ký mà hệ văn bản công bố, để
   * đối chiếu tệp nhận được không bị sửa sau khi ký.
   */
  record OriginalFile(
      IssuedDocument document,
      byte[] content,
      String mediaType,
      String fileName,
      byte[] signedSha256) {}

  /** Mã kết nối mà nguồn này phục vụ. */
  String connectorCode();

  Result search(Connector connector, String externalUnitId, String q, int limit, String cursor)
      throws IOException;

  /**
   * Tải tệp gốc của văn bản {@code ref} thuộc đơn vị phía ngoài.
   *
   * @return rỗng khi văn bản không có hoặc không thuộc đơn vị
   */
  default Optional<OriginalFile> fetch(Connector connector, String externalUnitId, String ref)
      throws IOException {
    return Optional.empty();
  }
}

package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * SPI tra cứu văn bản đã ban hành ở hệ văn bản ngoài (vOffice, ĐHTN) theo đơn vị phía ngoài
 * (BR-07-13): chỉ trả siêu dữ liệu và tham chiếu tệp gốc, không tải tệp.
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

  /** Mã kết nối mà nguồn này phục vụ. */
  String connectorCode();

  Result search(Connector connector, String externalUnitId, String q, int limit, String cursor)
      throws IOException;
}

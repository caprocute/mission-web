package vn.viettel.okrkpi.product.application.evidence.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Văn bản đã ban hành trên vOffice qua cổng nội bộ của SVC-13 integration-gateway (CORE-03-04,
 * BR-07-13): tìm theo đơn vị và chuyển tệp gốc sang SVC-08 để làm minh chứng mà trình duyệt không
 * tải lại tệp. SVC-13 lỗi, quá thời hạn hay mạch ngắt thì ném {@code
 * DependencyUnavailableException} (503 kèm {@code Retry-After}).
 */
public interface IssuedDocuments {

  /** Một văn bản; {@code signatureValid} là kết quả kiểm chữ ký số gốc khi nhận. */
  record Doc(
      String ref,
      String docNumber,
      LocalDate docDate,
      String summary,
      String signerName,
      boolean signatureValid) {}

  /** Một trang kết quả; {@code nextCursor} rỗng khi hết. */
  record Page(List<Doc> items, String nextCursor) {
    public Page {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }

  /**
   * Tệp gốc đã chuyển sang SVC-08: đối tượng, phiên bản, băm SHA-256 và kết quả kiểm chữ ký số của
   * chính tệp đã chuyển (tệp bị sửa so với bản ký thì không hợp lệ). Chữ ký không hợp lệ thì SVC-13
   * không chuyển tệp: {@code objectId}, {@code sha256} rỗng.
   */
  record Transferred(
      UUID objectId,
      int objectVersion,
      byte[] sha256,
      long sizeBytes,
      String mediaType,
      boolean signatureValid,
      Doc doc) {}

  Page search(TenantId tenant, UUID orgUnitId, String q, int limit, String cursor);

  /**
   * Chuyển tệp gốc của văn bản {@code ref} thuộc đơn vị sang SVC-08 làm tệp của minh chứng {@code
   * evidenceId}; gọi lại cùng {@code idempotencyKey} trả cùng đối tượng.
   *
   * @return rỗng khi văn bản không có hoặc không thuộc đơn vị
   */
  Optional<Transferred> transfer(
      TenantId tenant,
      UUID orgUnitId,
      String ref,
      UUID evidenceId,
      UUID requestedBy,
      String idempotencyKey);
}

package vn.viettel.okrkpi.esign.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Yêu cầu gửi nhà cung cấp: người ký, chứng thư đã chọn (nếu có) và danh sách băm tài liệu. Ký lô
 * là một phiên với nhiều băm, người ký xác nhận một lần.
 *
 * @param certificateSha256 dấu vân tay chứng thư người ký đã chọn; {@code null} để nhà cung cấp
 *     dùng chứng thư mặc định của người ký
 */
public record SignRequest(
    UUID requestId,
    UUID tenantId,
    UUID signerPersonId,
    byte[] certificateSha256,
    List<Document> documents,
    Instant expiresAt) {

  public SignRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(signerPersonId, "signerPersonId");
    documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    Objects.requireNonNull(expiresAt, "expiresAt");
    certificateSha256 = certificateSha256 == null ? null : certificateSha256.clone();
  }

  @Override
  public byte[] certificateSha256() {
    return certificateSha256 == null ? null : certificateSha256.clone();
  }

  /** Một tài liệu cần ký: chỉ băm SHA-256, không gửi nội dung. */
  public record Document(String documentRef, byte[] sha256, SignatureFormat format) {
    public Document {
      Objects.requireNonNull(documentRef, "documentRef");
      Objects.requireNonNull(sha256, "sha256");
      Objects.requireNonNull(format, "format");
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Document d
          && documentRef.equals(d.documentRef)
          && java.util.Arrays.equals(sha256, d.sha256)
          && format == d.format;
    }

    @Override
    public int hashCode() {
      return documentRef.hashCode();
    }

    @Override
    public String toString() {
      return "Document[" + documentRef + ", " + format + "]";
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SignRequest r && requestId.equals(r.requestId);
  }

  @Override
  public int hashCode() {
    return requestId.hashCode();
  }

  @Override
  public String toString() {
    return "SignRequest[" + requestId + ", " + documents.size() + " tài liệu]";
  }
}

package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSearch.Target;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.IssuedDocument;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.OriginalFile;
import vn.viettel.okrkpi.integ.application.port.EvidenceObjects;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Chuyển tệp gốc của văn bản đã ban hành sang kho minh chứng cho MOD-03 (CORE-03-04, BR-07-13): tải
 * tệp gốc của văn bản thuộc đơn vị, đối chiếu SHA-256 với bản đã ký mà hệ văn bản công bố, rồi mới
 * chuyển sang SVC-08. Chữ ký số chỉ hợp lệ khi hệ văn bản báo chữ ký gốc hợp lệ và tệp nhận được
 * trùng bản đã ký; không hợp lệ thì không ghi gì vào kho (kho có Object Lock, không xoá được) và
 * trả kết quả để MOD-03 từ chối theo BR-07-13.
 */
public class IssuedDocumentTransfer {

  /** Kết quả chuyển; {@code stored} rỗng khi chữ ký không hợp lệ. */
  public record Transfer(
      IssuedDocument document, boolean signatureValid, EvidenceObjects.Stored stored) {}

  private final IssuedDocumentSearch search;
  private final EvidenceObjects evidence;

  /**
   * @param evidence {@code null} khi chưa cấu hình SVC-08: lệnh chuyển báo lỗi để bên gọi thử lại
   */
  public IssuedDocumentTransfer(IssuedDocumentSearch search, EvidenceObjects evidence) {
    this.search = Objects.requireNonNull(search, "search");
    this.evidence = evidence;
  }

  /**
   * @return rỗng khi đơn vị chưa ánh xạ, văn bản không có hoặc không thuộc đơn vị
   */
  public Optional<Transfer> transfer(
      TenantId tenant,
      String connectorCode,
      UUID orgUnitId,
      String ref,
      UUID ownerRefId,
      UUID requestedBy,
      String idempotencyKey) {
    if (ref == null || ref.isBlank() || ref.length() > 200) {
      throw new IllegalArgumentException("Tham chiếu văn bản không hợp lệ");
    }
    Objects.requireNonNull(ownerRefId, "ownerRefId");
    Objects.requireNonNull(requestedBy, "requestedBy");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("Thiếu khoá chống ghi trùng");
    }
    Optional<Target> target = search.target(tenant, connectorCode, orgUnitId);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    Target t = target.get();
    try {
      Optional<OriginalFile> original = t.source().fetch(t.connector(), t.externalUnit(), ref);
      if (original.isEmpty()) {
        return Optional.empty();
      }
      OriginalFile f = original.get();
      if (!signatureValid(f)) {
        return Optional.of(new Transfer(f.document(), false, null));
      }
      if (evidence == null) {
        throw new IOException("Chưa cấu hình kho minh chứng");
      }
      EvidenceObjects.Stored stored =
          evidence.ingest(
              tenant,
              idempotencyKey,
              ownerRefId,
              requestedBy,
              f.fileName(),
              f.mediaType(),
              f.content());
      return Optional.of(new Transfer(f.document(), true, stored));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Chữ ký gốc hợp lệ theo hệ văn bản và tệp nhận được trùng bản đã ký. */
  public static boolean signatureValid(OriginalFile f) {
    if (!f.document().signatureValid() || f.signedSha256() == null) {
      return false;
    }
    try {
      byte[] actual = MessageDigest.getInstance("SHA-256").digest(f.content());
      return MessageDigest.isEqual(actual, f.signedSha256());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

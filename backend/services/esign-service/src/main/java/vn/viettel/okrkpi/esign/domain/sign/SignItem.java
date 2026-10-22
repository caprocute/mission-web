package vn.viettel.okrkpi.esign.domain.sign;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Một tài liệu trong yêu cầu ký ({@code esign.sign_item}): băm được ký và chữ ký nhận về. */
public final class SignItem {

  private final UUID id;
  private final String documentKind;
  private final String documentRef;
  private final byte[] documentSha256;
  private final SignatureFormat format;
  private byte[] signatureValue;
  private String signedObjectKey;
  private UUID certificateRefId;
  private Instant signedAt;

  public SignItem(
      UUID id,
      String documentKind,
      String documentRef,
      byte[] documentSha256,
      SignatureFormat format,
      byte[] signatureValue,
      String signedObjectKey,
      UUID certificateRefId,
      Instant signedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.documentKind = Objects.requireNonNull(documentKind, "documentKind");
    this.documentRef = Objects.requireNonNull(documentRef, "documentRef");
    Objects.requireNonNull(documentSha256, "documentSha256");
    if (documentSha256.length != 32) {
      throw new IllegalArgumentException("Băm tài liệu phải là SHA-256 (32 byte)");
    }
    this.documentSha256 = documentSha256.clone();
    this.format = Objects.requireNonNull(format, "format");
    this.signatureValue = signatureValue == null ? null : signatureValue.clone();
    this.signedObjectKey = signedObjectKey;
    this.certificateRefId = certificateRefId;
    this.signedAt = signedAt;
  }

  /** Tài liệu mới chờ ký. */
  public static SignItem pending(
      UUID id, String documentKind, String documentRef, byte[] sha256, SignatureFormat format) {
    return new SignItem(id, documentKind, documentRef, sha256, format, null, null, null, null);
  }

  void sign(byte[] signature, String objectKey, UUID certificateId, Instant at) {
    Objects.requireNonNull(signature, "signature");
    this.signatureValue = signature.clone();
    this.signedObjectKey = objectKey;
    this.certificateRefId = certificateId;
    this.signedAt = at;
  }

  public boolean signed() {
    return signatureValue != null;
  }

  public boolean matches(byte[] sha256) {
    return Arrays.equals(documentSha256, sha256);
  }

  public UUID id() {
    return id;
  }

  public String documentKind() {
    return documentKind;
  }

  public String documentRef() {
    return documentRef;
  }

  public byte[] documentSha256() {
    return documentSha256.clone();
  }

  public SignatureFormat format() {
    return format;
  }

  public byte[] signatureValue() {
    return signatureValue == null ? null : signatureValue.clone();
  }

  public String signedObjectKey() {
    return signedObjectKey;
  }

  public UUID certificateRefId() {
    return certificateRefId;
  }

  public Instant signedAt() {
    return signedAt;
  }
}

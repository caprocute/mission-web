package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nội dung góp ý C2 mã hoá phong bì bằng DEK mục đích {@code GOP_Y_KIEM_DIEM}; AAD là {@code
 * dossier.review_comment.comment_enc} và mã dòng, tenant đi kèm DEK (TL-03, ADR-06).
 */
public class FieldEncryptorCommentSealer implements CommentSealer {

  static final String COLUMN = "dossier.review_comment.comment_enc";

  private final FieldEncryptor encryptor;

  public FieldEncryptorCommentSealer(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
  }

  @Override
  public Sealed seal(TenantId tenant, UUID commentId, String plain) {
    EncryptedField f =
        encryptor.encryptField(
            tenant.value(),
            DekPurpose.GOP_Y_KIEM_DIEM,
            aad(tenant, commentId),
            plain.getBytes(StandardCharsets.UTF_8));
    return new Sealed(f.ciphertext(), f.dekId());
  }

  @Override
  public String open(TenantId tenant, UUID commentId, byte[] cipher) {
    return encryptor.decryptToString(
        tenant.value(), DekPurpose.GOP_Y_KIEM_DIEM, aad(tenant, commentId), cipher);
  }

  private static List<String> aad(TenantId tenant, UUID commentId) {
    return List.of(tenant.value().toString(), COLUMN, commentId.toString());
  }
}

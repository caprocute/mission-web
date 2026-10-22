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
 * Nội dung C2 của kiểm điểm (góp ý, hạn chế cần khắc phục) mã hoá phong bì bằng DEK mục đích {@code
 * GOP_Y_KIEM_DIEM}; AAD là tenant, tên cột đủ và mã dòng (TL-03, ADR-06).
 */
public class FieldEncryptorCommentSealer implements CommentSealer {

  private final FieldEncryptor encryptor;

  public FieldEncryptorCommentSealer(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
  }

  @Override
  public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
    EncryptedField f =
        encryptor.encryptField(
            tenant.value(),
            DekPurpose.GOP_Y_KIEM_DIEM,
            aad(tenant, column, rowId),
            plain.getBytes(StandardCharsets.UTF_8));
    return new Sealed(f.ciphertext(), f.dekId());
  }

  @Override
  public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
    return encryptor.decryptToString(
        tenant.value(), DekPurpose.GOP_Y_KIEM_DIEM, aad(tenant, column, rowId), cipher);
  }

  private static List<String> aad(TenantId tenant, String column, UUID rowId) {
    return List.of(tenant.value().toString(), column, rowId.toString());
  }
}

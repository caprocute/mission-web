package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.SealedComment;
import vn.viettel.okrkpi.product.application.evidence.port.CommentSealer;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhận xét nghiệm thu mã hoá phong bì bằng DEK mục đích {@code NHAN_XET_DANH_GIA}; AAD là {@code
 * product.acceptance.comment_enc} và mã dòng (TL-03, ADR-06).
 */
public class FieldEncryptorCommentSealer implements CommentSealer {

  static final String COLUMN = "product.acceptance.comment_enc";

  private final Supplier<FieldEncryptor> encryptor;

  public FieldEncryptorCommentSealer(Supplier<FieldEncryptor> encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
  }

  @Override
  public SealedComment seal(TenantId tenant, UUID acceptanceId, String plain) {
    if (plain == null || plain.isBlank()) {
      return null;
    }
    FieldEncryptor e =
        Objects.requireNonNull(encryptor.get(), "Chưa cấu hình mã hoá trường C2 (FieldEncryptor)");
    EncryptedField f =
        e.encryptField(
            tenant.value(),
            DekPurpose.NHAN_XET_DANH_GIA,
            List.of(COLUMN, acceptanceId.toString()),
            plain.getBytes(StandardCharsets.UTF_8));
    return new SealedComment(f.ciphertext(), f.dekId());
  }
}

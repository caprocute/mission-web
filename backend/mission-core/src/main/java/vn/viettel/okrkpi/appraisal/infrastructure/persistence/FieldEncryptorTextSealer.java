package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trường C2 của hồ sơ đánh giá mã hoá phong bì bằng DEK mục đích {@code NHAN_XET_DANH_GIA}; AAD là
 * {@code schema.bảng.cột} và mã dòng (TL-03, ADR-06).
 */
public class FieldEncryptorTextSealer implements TextSealer {

  private final FieldEncryptor encryptor;

  public FieldEncryptorTextSealer(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
  }

  @Override
  public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
    if (plain == null || plain.isEmpty()) {
      return null;
    }
    EncryptedField f =
        encryptor.encryptField(
            tenant.value(),
            DekPurpose.NHAN_XET_DANH_GIA,
            aad(column, rowId),
            plain.getBytes(StandardCharsets.UTF_8));
    return new SealedText(f.ciphertext(), f.dekId());
  }

  @Override
  public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
    if (sealed == null) {
      return null;
    }
    return encryptor.decryptToString(
        tenant.value(), DekPurpose.NHAN_XET_DANH_GIA, aad(column, rowId), sealed.cipher());
  }

  private static List<String> aad(String column, UUID rowId) {
    return List.of(column, rowId.toString());
  }
}

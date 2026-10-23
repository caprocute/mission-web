package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nội dung C2 của khiếu nại mã hoá phong bì bằng DEK mục đích {@code KHIEU_NAI}; AAD là tenant, tên
 * cột đủ và mã dòng (TL-03, ADR-06). Lần mở được {@code C2AccessAuditor} ghi sổ cái (BR-12-10).
 */
public class FieldEncryptorComplaintSealer implements ComplaintSealer {

  private final FieldEncryptor encryptor;

  public FieldEncryptorComplaintSealer(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
  }

  @Override
  public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
    EncryptedField f =
        encryptor.encryptField(
            tenant.value(),
            DekPurpose.KHIEU_NAI,
            aad(column, rowId),
            plain.getBytes(StandardCharsets.UTF_8));
    return new Sealed(f.ciphertext(), f.dekId());
  }

  @Override
  public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
    return encryptor.decryptToString(
        tenant.value(), DekPurpose.KHIEU_NAI, aad(column, rowId), cipher);
  }

  /**
   * {@code FieldEncryptor} tự ghép tenant vào đầu AAD; truyền {@code [cột, mã dòng]} để {@code
   * C2AccessAuditor} ghi đúng toạ độ (bản mã cũ vẫn mở được vì AAD không đổi).
   */
  private static List<String> aad(String column, UUID rowId) {
    return List.of(column, rowId.toString());
  }
}

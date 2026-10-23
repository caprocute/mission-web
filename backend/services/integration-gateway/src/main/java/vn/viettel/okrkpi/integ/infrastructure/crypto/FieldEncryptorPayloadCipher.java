package vn.viettel.okrkpi.integ.infrastructure.crypto;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá phong bì NEN-015 cho {@code raw_enc}, {@code payload_enc}. Thông điệp hệ ngoài mang định
 * danh cán bộ, đơn vị nên dùng DEK mục đích {@link DekPurpose#DINH_DANH_CA_NHAN} (chưa có mục đích
 * riêng cho tích hợp; wiki trang 14).
 */
public class FieldEncryptorPayloadCipher implements PayloadCipher {

  static final DekPurpose PURPOSE = DekPurpose.DINH_DANH_CA_NHAN;

  private final FieldEncryptor encryptor;

  public FieldEncryptorPayloadCipher(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain) {
    EncryptedField f =
        encryptor.encryptField(tenant.value(), PURPOSE, List.of(column, rowId.toString()), plain);
    return new Sealed(f.ciphertext(), f.dekId());
  }

  @Override
  public byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext) {
    return encryptor.decrypt(
        tenant.value(), PURPOSE, List.of(column, rowId.toString()), ciphertext);
  }
}

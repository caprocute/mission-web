package vn.viettel.okrkpi.org.infrastructure.crypto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.BlindIndexer;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;

/**
 * Mã hoá trường C2 bằng thư viện nền NEN-015: phong bì AES-256-GCM với DEK trong {@code keyring.dek} (bọc bằng KEK
 * của tenant trên OpenBao Transit), AAD {@code tenant|cột|id dòng}, chỉ mục mù HMAC theo cột. Số định danh, ngày sinh
 * và giá trị lệch đối soát là định danh cá nhân nên dùng DEK mục đích {@link DekPurpose#DINH_DANH_CA_NHAN}, cùng
 * integration-gateway.
 */
public class KeyringFieldEncryptor implements FieldEncryptor {
  static final DekPurpose PURPOSE = DekPurpose.DINH_DANH_CA_NHAN;

  private final vn.viettel.okrkpi.shared.crypto.FieldEncryptor encryptor;
  private final BlindIndexer indexer;

  public KeyringFieldEncryptor(vn.viettel.okrkpi.shared.crypto.FieldEncryptor encryptor, BlindIndexer indexer) {
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
    this.indexer = Objects.requireNonNull(indexer, "indexer");
  }

  @Override
  public Encrypted encrypt(UUID tenantId, String column, UUID rowId, byte[] plaintext) {
    EncryptedField f = encryptor.encryptField(tenantId, PURPOSE, aad(column, rowId), plaintext);
    return new Encrypted(f.dekId(), f.ciphertext());
  }

  /** Mã DEK nằm sẵn trong phong bì; {@code dekId} của cột {@code *_dek_id} chỉ để xoay khoá và tra cứu. */
  @Override
  public byte[] decrypt(UUID tenantId, String column, UUID rowId, UUID dekId, byte[] ciphertext) {
    return encryptor.decrypt(tenantId, PURPOSE, aad(column, rowId), ciphertext);
  }

  @Override
  public byte[] blindIndex(UUID tenantId, String column, String normalizedValue) {
    return indexer.compute(tenantId, column, normalizedValue);
  }

  private static List<String> aad(String column, UUID rowId) {
    return List.of(column, rowId.toString());
  }
}

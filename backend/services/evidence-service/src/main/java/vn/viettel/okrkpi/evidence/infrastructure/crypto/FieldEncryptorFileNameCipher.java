package vn.viettel.okrkpi.evidence.infrastructure.crypto;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.application.FileNameCipher;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;

/** Tên tệp gốc mã hoá phong bì (NEN-015), AAD là toạ độ cột và mã đối tượng. */
public class FieldEncryptorFileNameCipher implements FileNameCipher {

  static final String COLUMN = "evidence.object_meta.file_name_enc";

  private final FieldEncryptor encryptor;

  public FieldEncryptorFileNameCipher(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public Sealed seal(UUID tenantId, UUID objectId, String fileName) {
    EncryptedField f =
        encryptor.encryptField(
            tenantId,
            DekPurpose.TEP_MINH_CHUNG,
            List.of(COLUMN, objectId.toString()),
            fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new Sealed(f.ciphertext(), f.dekId());
  }
}

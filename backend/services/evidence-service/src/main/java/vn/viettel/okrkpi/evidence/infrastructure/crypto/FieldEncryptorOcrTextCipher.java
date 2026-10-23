package vn.viettel.okrkpi.evidence.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.application.OcrTextCipher;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;

/** Mã hoá văn bản OCR bằng DEK mục đích {@code TEP_MINH_CHUNG}, AAD gắn cột và phiên bản. */
public class FieldEncryptorOcrTextCipher implements OcrTextCipher {

  static final String COLUMN = "evidence.ocr_text.text_enc";

  private final FieldEncryptor encryptor;

  public FieldEncryptorOcrTextCipher(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public Sealed seal(UUID tenantId, UUID versionId, String text) {
    EncryptedField f =
        encryptor.encryptField(
            tenantId,
            DekPurpose.TEP_MINH_CHUNG,
            List.of(COLUMN, versionId.toString()),
            (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    return new Sealed(f.ciphertext(), f.dekId());
  }
}
